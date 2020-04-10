package io.dnsmonitor.dns.client.model;

public class AaaaDnsRecord extends DnsRecord {
    private final String ipv6;

    public AaaaDnsRecord(String ipv6) {
        super("AAAA");
        this.ipv6 = ipv6;
    }

    public String getIpv6() {
        return ipv6;
    }
}
