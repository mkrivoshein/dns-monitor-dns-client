package io.dnsmonitor.dns.client.model;

public abstract class DnsRecord {
    private final String recordType;

    DnsRecord(String recordType) {
        this.recordType = recordType;
    }

    public String getRecordType() {
        return recordType;
    }
}
