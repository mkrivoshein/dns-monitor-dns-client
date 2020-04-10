package io.dnsmonitor.dns.client.model;

public class CnameDnsRecord extends DnsRecord {
    private final String value;

    public CnameDnsRecord(String value) {
        super("CNAME");
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
