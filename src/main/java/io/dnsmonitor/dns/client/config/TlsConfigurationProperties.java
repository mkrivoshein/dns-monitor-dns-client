package io.dnsmonitor.dns.client.config;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

final class TlsConfigurationProperties {
    private static final String CLASSPATH_SCHEME = "classpath:";
    private static final String FILE_SCHEME = "file:";
    private static final String HTTP_SCHEME = "http:";
    private static final String HTTPS_SCHEME = "https:";
    static final String CERTIFICATE_PROPERTY = "dns.client.tls.certificate";
    static final String CERTIFICATE_ENV = "DNS_CLIENT_TLS_CERTIFICATE";
    static final String CERTIFICATE_SHORT_ENV = "DNS_CLIENT_TLS_CERT";
    static final String PRIVATE_KEY_PROPERTY = "dns.client.tls.private-key";
    static final String PRIVATE_KEY_ENV = "DNS_CLIENT_TLS_PRIVATE_KEY";
    static final String PRIVATE_KEY_SHORT_ENV = "DNS_CLIENT_TLS_KEY";
    static final String CLIENT_CA_PROPERTY = "dns.client.tls.client-ca";
    static final String CLIENT_CA_ENV = "DNS_CLIENT_TLS_CLIENT_CA";

    private TlsConfigurationProperties() {
    }

    static String certificate(Environment environment) {
        return getFirstPresent(environment, CERTIFICATE_PROPERTY, CERTIFICATE_ENV, CERTIFICATE_SHORT_ENV);
    }

    static String privateKey(Environment environment) {
        return getFirstPresent(environment, PRIVATE_KEY_PROPERTY, PRIVATE_KEY_ENV, PRIVATE_KEY_SHORT_ENV);
    }

    static String clientCa(Environment environment) {
        return getFirstPresent(environment, CLIENT_CA_PROPERTY, CLIENT_CA_ENV);
    }

    static String getFirstPresent(Environment environment, String... names) {
        for (var name : names) {
            var value = environment.getProperty(name);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    static String normalizeResourceLocation(String location) {
        if (hasScheme(location, HTTP_SCHEME)) {
            throw new IllegalStateException("TLS resources must not be loaded from plain HTTP: " + location);
        }

        if (hasScheme(location, CLASSPATH_SCHEME)
                || hasScheme(location, FILE_SCHEME)
                || hasScheme(location, HTTPS_SCHEME)) {
            return location;
        }

        var path = Path.of(location);
        if (path.isAbsolute()) {
            return path.toUri().toString();
        }

        return location;
    }

    private static boolean hasScheme(String location, String scheme) {
        return location.regionMatches(true, 0, scheme, 0, scheme.length());
    }
}
