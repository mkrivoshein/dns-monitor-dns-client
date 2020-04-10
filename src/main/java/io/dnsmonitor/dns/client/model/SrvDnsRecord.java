package io.dnsmonitor.dns.client.model;

public class SrvDnsRecord extends DnsRecord {
    private final int priority;
    private final int weight;
    private final int port;
    private final String value;

    public SrvDnsRecord(int priority, int weight, int port, String value) {
        super("SRV");
        this.priority = priority;
        this.weight = weight;
        this.port = port;
        this.value = value;
    }

    public int getPriority() {
        return priority;
    }

    public int getWeight() {
        return weight;
    }

    public int getPort() {
        return port;
    }

    public String getValue() {
        return value;
    }
}
