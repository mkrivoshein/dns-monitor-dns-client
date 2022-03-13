package io.dnsmonitor.dns.client;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DnsClientController {
    private final DnsClientWorker worker;

    public DnsClientController(DnsClientWorker worker) {
        this.worker = worker;
    }

    @GetMapping("/dns/{recordtype}/{domain}")
    @SuppressWarnings("unused")
    public DnsClientReply dnsQuery(@PathVariable("recordtype") String recordType, @PathVariable("domain") String domain) {
        return new DnsClientReply(domain, worker.lookup(recordType, domain));
    }
}
