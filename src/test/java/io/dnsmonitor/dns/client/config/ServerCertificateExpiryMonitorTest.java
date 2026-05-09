package io.dnsmonitor.dns.client.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerCertificateExpiryMonitorTest {
    @Test
    void certificateMonitorThreadFactoryCreatesNamedDaemonThread() {
        var thread = ServerCertificateExpiryMonitor.certificateMonitorThreadFactory()
                .newThread(() -> {
                });

        assertThat(thread.getName()).isEqualTo("dns-client-cert-expiry-monitor");
        assertThat(thread.isDaemon()).isTrue();
    }
}
