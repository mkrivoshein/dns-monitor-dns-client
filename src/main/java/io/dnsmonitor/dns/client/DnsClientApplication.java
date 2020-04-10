package io.dnsmonitor.dns.client;

import io.dnsmonitor.dns.client.dnsjava.DnsJavaAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DnsClientApplication {

	public static void main(String[] args) {
		SpringApplication.run(DnsClientApplication.class, args);
	}

	@Bean
	public DnsClientWorker dnsClientWorker() {
		return new DnsClientWorker(new DnsJavaAdapter());
	}
}
