package io.dnsmonitor.dns.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DnsClientController {
    private final Logger logger = LoggerFactory.getLogger(DnsClientController.class);

    private final Tracer tracer;
    private final DnsClientWorker worker;

    public DnsClientController(Tracer tracer, DnsClientWorker worker) {
        this.tracer = tracer;
        this.worker = worker;
    }

    @GetMapping("/dns/{domain}")
    @SuppressWarnings("unused")
    public DnsClientReply dnsQuery(@PathVariable("domain") String domain) {
        var newSpan = tracer.nextSpan().name("dnsQuery");
        try (var withSpan = tracer.withSpan(newSpan.start())) {
            logger.info("DNS query for {}", domain);
            var result = worker.lookup(domain);
            try {
                return new DnsClientReply(domain, worker.lookup(domain));
            } finally {
                logger.info("DNS query for {} returned {} records", domain, result.size());
            }
        } finally {
            newSpan.end();
        }
    }

    @GetMapping("/dns/{recordtype}/{domain}")
    @SuppressWarnings("unused")
    public DnsClientReply dnsQuery(@PathVariable("recordtype") String recordType, @PathVariable("domain") String domain) {
        var newSpan = tracer.nextSpan().name("dnsQuery");
        try (var withSpan = tracer.withSpan(newSpan.start())) {
            logger.info("DNS dig {} for {}", recordType, domain);
            var result = worker.lookup(recordType, domain);
            try {
                return new DnsClientReply(domain, result);
            } finally {
                logger.info("DNS dig {} for {} returned {} records", recordType, domain, result.size());
            }
        } finally {
            newSpan.end();
        }
    }
}
