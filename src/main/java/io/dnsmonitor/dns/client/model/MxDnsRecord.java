package io.dnsmonitor.dns.client.model;

public class MxDnsRecord extends DnsRecord {
    private final int priority;
    private final String value;

    public MxDnsRecord(int priority, String value) {
        super("MX");
        this.priority = priority;
        this.value = value;
    }

    public int getPriority() {
        return priority;
    }

    public String getValue() {
        return value;
    }
}
