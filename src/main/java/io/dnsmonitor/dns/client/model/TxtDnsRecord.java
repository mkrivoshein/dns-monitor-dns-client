package io.dnsmonitor.dns.client.model;

public class TxtDnsRecord extends DnsRecord {
    private final String value;

    public TxtDnsRecord(String value) {
        super("TXT");
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
