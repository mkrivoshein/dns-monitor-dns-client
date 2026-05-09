package io.dnsmonitor.dns.client.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionalTlsEnvironmentPostProcessorTest {
    private final ConditionalTlsEnvironmentPostProcessor postProcessor = new ConditionalTlsEnvironmentPostProcessor();

    @Test
    void leavesHttpEnabledWhenTlsCertificateAndKeyAreMissing() {
        var environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.ssl.enabled")).isNull();
        assertThat(environment.getProperty("server.ssl.client-auth")).isNull();
    }

    @Test
    void enablesHttpsWhenCertificateAndPrivateKeyAreProvided() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dns.client.tls.certificate", "/run/tls/server.crt",
                "dns.client.tls.private-key", "/run/tls/server.key"
        )));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.ssl.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("server.ssl.bundle")).isEqualTo("dns-client");
        assertThat(environment.getProperty("spring.ssl.bundle.pem.dns-client.keystore.certificate"))
                .isEqualTo("file:///run/tls/server.crt");
        assertThat(environment.getProperty("spring.ssl.bundle.pem.dns-client.keystore.private-key"))
                .isEqualTo("file:///run/tls/server.key");
        assertThat(environment.getProperty("server.ssl.client-auth")).isNull();
    }

    @Test
    void enablesMutualTlsWhenClientCaIsProvided() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dns.client.tls.certificate", "/run/tls/server.crt",
                "dns.client.tls.private-key", "/run/tls/server.key",
                "dns.client.tls.client-ca", "/run/tls/client-ca.crt"
        )));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.ssl.bundle.pem.dns-client.truststore.certificate"))
                .isEqualTo("file:///run/tls/client-ca.crt");
        assertThat(environment.getProperty("server.ssl.client-auth")).isEqualTo("need");
    }

    @Test
    void failsFastWhenTlsConfigurationIsIncomplete() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dns.client.tls.certificate", "/run/tls/server.crt"
        )));

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Both dns.client.tls.certificate and dns.client.tls.private-key");
    }

    @Test
    void failsFastWhenClientCaIsConfiguredWithoutServerTls() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dns.client.tls.client-ca", "/run/tls/client-ca.crt"
        )));

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Both dns.client.tls.certificate and dns.client.tls.private-key");
    }

    @Test
    void rejectsPlainHttpTlsResourceLocations() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dns.client.tls.certificate", "http://example.test/server.crt",
                "dns.client.tls.private-key", "/run/tls/server.key"
        )));

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be loaded from plain HTTP");
    }

    @Test
    void preservesHttpsTlsResourceLocations() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dns.client.tls.certificate", "https://example.test/server.crt",
                "dns.client.tls.private-key", "https://example.test/server.key",
                "dns.client.tls.client-ca", "https://example.test/client-ca.crt"
        )));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.ssl.bundle.pem.dns-client.keystore.certificate"))
                .isEqualTo("https://example.test/server.crt");
        assertThat(environment.getProperty("spring.ssl.bundle.pem.dns-client.keystore.private-key"))
                .isEqualTo("https://example.test/server.key");
        assertThat(environment.getProperty("spring.ssl.bundle.pem.dns-client.truststore.certificate"))
                .isEqualTo("https://example.test/client-ca.crt");
    }
}
