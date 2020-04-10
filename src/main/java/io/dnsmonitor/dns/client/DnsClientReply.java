package io.dnsmonitor.dns.client;

import io.dnsmonitor.dns.client.model.DnsRecord;

import java.util.List;

public class DnsClientReply {
    private final String domain;
    private final List<DnsRecord> records;

    public DnsClientReply(String domain, List<DnsRecord> records) {
        this.domain = domain;
        this.records = records;
    }

    public String getDomain() {
        return domain;
    }

    public List<DnsRecord> getRecords() {
        return records;
    }
}
