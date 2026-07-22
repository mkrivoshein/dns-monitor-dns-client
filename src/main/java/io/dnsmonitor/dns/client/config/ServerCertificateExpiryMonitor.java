package io.dnsmonitor.dns.client.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Component
public class ServerCertificateExpiryMonitor implements ApplicationRunner, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(ServerCertificateExpiryMonitor.class);
    private static final Duration EXPIRY_EXIT_WINDOW = Duration.ofMinutes(10);
    private static final Duration CHECK_INTERVAL = Duration.ofMinutes(5);
    private static final String THREAD_NAME = "dns-client-cert-expiry-monitor";

    private final Environment environment;
    private final ResourceLoader resourceLoader;
    private final ConfigurableApplicationContext applicationContext;
    private final Clock clock;
    private ScheduledExecutorService executorService;
    private Runnable exitAction = this::exitApplication;

    @Autowired
    public ServerCertificateExpiryMonitor(Environment environment,
                                          ResourceLoader resourceLoader,
                                          ConfigurableApplicationContext applicationContext) {
        this(environment, resourceLoader, applicationContext, Clock.systemUTC());
    }

    ServerCertificateExpiryMonitor(Environment environment,
                                   ResourceLoader resourceLoader,
                                   ConfigurableApplicationContext applicationContext,
                                   Clock clock) {
        this.environment = environment;
        this.resourceLoader = resourceLoader;
        this.applicationContext = applicationContext;
        this.clock = clock;
    }

    void setExitAction(Runnable exitAction) {
        this.exitAction = exitAction;
    }

    @Override
    public void run(ApplicationArguments args) {
        var certificateLocation = TlsConfigurationProperties.certificate(environment);
        var clientCaLocation = TlsConfigurationProperties.clientCa(environment);
        if (!StringUtils.hasText(certificateLocation) && !StringUtils.hasText(clientCaLocation)) {
            return;
        }

        executorService = Executors.newSingleThreadScheduledExecutor(certificateMonitorThreadFactory());
        executorService.scheduleWithFixedDelay(
                () -> checkCertificates(certificateLocation, clientCaLocation),
                0,
                CHECK_INTERVAL.toSeconds(),
                TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    void checkCertificates(String certificateLocation, String clientCaLocation) {
        if (StringUtils.hasText(certificateLocation)) {
            checkCertificate(certificateLocation, "server TLS certificate");
        }
        if (StringUtils.hasText(clientCaLocation)) {
            checkCertificate(clientCaLocation, "client CA certificate");
        }
    }

    private void checkCertificate(String certificateLocation, String description) {
        try {
            var certificates = loadCertificates(certificateLocation);
            var exitThreshold = clock.instant().plus(EXPIRY_EXIT_WINDOW);
            for (var certificate : certificates) {
                var expiresAt = certificate.getNotAfter().toInstant();
                if (!expiresAt.isAfter(exitThreshold)) {
                    logger.error("{} chain member '{}' expires at {}, within the {} minute exit window. Exiting.",
                            description, certificate.getSubjectX500Principal().getName(), expiresAt,
                            EXPIRY_EXIT_WINDOW.toMinutes());
                    exitAction.run();
                    return;
                }
                logger.debug("{} chain member '{}' expires at {}",
                        description, certificate.getSubjectX500Principal().getName(), expiresAt);
            }
        } catch (Exception e) {
            logger.error("Unable to verify {} expiry. Exiting.", description, e);
            exitAction.run();
        }
    }

    private List<X509Certificate> loadCertificates(String certificateLocation) throws Exception {
        var resourceLocation = TlsConfigurationProperties.normalizeResourceLocation(certificateLocation);
        var resource = resourceLoader.getResource(resourceLocation);
        try (InputStream inputStream = resource.getInputStream()) {
            var certificateFactory = CertificateFactory.getInstance("X.509");
            var certificates = certificateFactory.generateCertificates(inputStream)
                    .stream()
                    .filter(X509Certificate.class::isInstance)
                    .map(X509Certificate.class::cast)
                    .sorted(Comparator.comparing(cert -> cert.getNotAfter().toInstant()))
                    .toList();
            if (certificates.isEmpty()) {
                throw new IllegalStateException("No X.509 certificate found in " + certificateLocation);
            }
            return certificates;
        }
    }

    private void exitApplication() {
        var exitCode = SpringApplication.exit(applicationContext, () -> 1);
        System.exit(exitCode);
    }

    static ThreadFactory certificateMonitorThreadFactory() {
        return runnable -> {
            var thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        };
    }
}
