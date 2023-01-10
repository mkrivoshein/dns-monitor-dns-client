package io.dnsmonitor.dns.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.sleuth.autoconfig.otel.OtelAutoConfiguration;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(classes = {OtelAutoConfiguration.class})
class DnsClientApplicationTests {

	@Test
	void contextLoads() {
	}

}
