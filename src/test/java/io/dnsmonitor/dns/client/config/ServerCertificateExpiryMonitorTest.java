package io.dnsmonitor.dns.client.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ServerCertificateExpiryMonitorTest {
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private ListAppender<ILoggingEvent> logAppender;
    private Logger monitorLogger;
    private AtomicInteger exitCounter;

    @BeforeEach
    void attachLogAppender() {
        monitorLogger = (Logger) LoggerFactory.getLogger(ServerCertificateExpiryMonitor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        monitorLogger.addAppender(logAppender);
        exitCounter = new AtomicInteger();
    }

    @AfterEach
    void detachLogAppender() {
        monitorLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void certificateMonitorThreadFactoryCreatesNamedDaemonThread() {
        var thread = ServerCertificateExpiryMonitor.certificateMonitorThreadFactory()
                .newThread(() -> {
                });

        assertThat(thread.getName()).isEqualTo("dns-client-cert-expiry-monitor");
        assertThat(thread.isDaemon()).isTrue();
    }

    @Test
    void checkServerChainExitsOnEarliestExpiringIntermediate() throws Exception {
        var pem = buildPemBundle(
                selfSignedCertificate("CN=leaf.example.com", NOW.plus(Duration.ofDays(30))),
                selfSignedCertificate("CN=intermediate-ca", NOW.plus(Duration.ofMinutes(5)))
        );
        var monitor = monitorWith(pem);

        monitor.checkCertificates("file:server.pem", null);

        assertThat(exitCounter.get()).isEqualTo(1);
        assertThat(errorMessages()).anyMatch(m -> m.contains("server TLS certificate")
                && m.contains("intermediate-ca")
                && m.contains("within the 10 minute exit window"));
    }

    @Test
    void checkServerChainDoesNotExitWhenAllCertsValid() throws Exception {
        var pem = buildPemBundle(
                selfSignedCertificate("CN=leaf.example.com", NOW.plus(Duration.ofDays(30))),
                selfSignedCertificate("CN=intermediate-ca", NOW.plus(Duration.ofDays(60)))
        );
        var monitor = monitorWith(pem);

        monitor.checkCertificates("file:server.pem", null);

        assertThat(exitCounter.get()).isZero();
        assertThat(errorMessages()).isEmpty();
    }

    @Test
    void checkServerChainWithSingleCertBehavesAsBefore() throws Exception {
        var pem = buildPemBundle(
                selfSignedCertificate("CN=leaf.example.com", NOW.plus(Duration.ofDays(30)))
        );
        var monitor = monitorWith(pem);

        monitor.checkCertificates("file:server.pem", null);

        assertThat(exitCounter.get()).isZero();
    }

    @Test
    void checkServerChainExitsWhenLeafIsTheEarlyOne() throws Exception {
        var pem = buildPemBundle(
                selfSignedCertificate("CN=leaf.example.com", NOW.plus(Duration.ofMinutes(5))),
                selfSignedCertificate("CN=intermediate-ca", NOW.plus(Duration.ofDays(30)))
        );
        var monitor = monitorWith(pem);

        monitor.checkCertificates("file:server.pem", null);

        assertThat(exitCounter.get()).isEqualTo(1);
        assertThat(errorMessages()).anyMatch(m -> m.contains("leaf.example.com"));
    }

    @Test
    void checkClientCaChainExitsOnAnyCert() throws Exception {
        var pem = buildPemBundle(
                selfSignedCertificate("CN=client-root-ca", NOW.plus(Duration.ofDays(365))),
                selfSignedCertificate("CN=client-intermediate-ca", NOW.plus(Duration.ofMinutes(3)))
        );
        var monitor = monitorWith(pem);

        monitor.checkCertificates(null, "file:client-ca.pem");

        assertThat(exitCounter.get()).isEqualTo(1);
        assertThat(errorMessages()).anyMatch(m -> m.contains("client CA certificate")
                && m.contains("client-intermediate-ca"));
    }

    @Test
    void loadCertificatesFailsAndExitsWhenResourceMissing() {
        var monitor = monitorWith("not-a-pem".getBytes());

        monitor.checkCertificates("file:does-not-exist.pem", null);

        assertThat(exitCounter.get()).isEqualTo(1);
        assertThat(errorMessages()).anyMatch(m -> m.contains("Unable to verify server TLS certificate expiry"));
    }

    private ServerCertificateExpiryMonitor monitorWith(byte[] pemBytes) {
        var resourceLoader = new StaticResourceLoader(pemBytes);
        var monitor = new ServerCertificateExpiryMonitor(
                new MockEnvironment(),
                resourceLoader,
                mock(ConfigurableApplicationContext.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        monitor.setExitAction(exitCounter::incrementAndGet);
        return monitor;
    }

    private List<String> errorMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static byte[] buildPemBundle(X509Certificate... certs) throws Exception {
        var sb = new StringBuilder();
        for (var cert : certs) {
            sb.append("-----BEGIN CERTIFICATE-----\n");
            sb.append(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded()));
            sb.append("\n-----END CERTIFICATE-----\n");
        }
        return sb.toString().getBytes();
    }

    private static X509Certificate selfSignedCertificate(String subjectDn, Instant notAfter) throws Exception {
        var keyPair = generateRsa();
        var name = new X500Name(subjectDn);
        var builder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(System.nanoTime()),
                Date.from(NOW.minusSeconds(60)),
                Date.from(notAfter),
                name,
                keyPair.getPublic());
        var holder = builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate()));
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static KeyPair generateRsa() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static class StaticResourceLoader implements ResourceLoader {
        private final Resource resource;

        StaticResourceLoader(byte[] content) {
            this.resource = new ByteArrayResource(content);
        }

        @Override
        public Resource getResource(String location) {
            return resource;
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }
}
