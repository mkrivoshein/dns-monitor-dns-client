package io.dnsmonitor.dns.client.model;

public class NsDnsRecord extends DnsRecord {
    private final String value;

    public NsDnsRecord(String value) {
        super("NS");
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
