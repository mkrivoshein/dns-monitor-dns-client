package io.dnsmonitor.dns.client.model;

public class ADnsRecord extends DnsRecord {
    private final String ipv4;

    public ADnsRecord(String ipv4) {
        super("A");
        this.ipv4 = ipv4;
    }

    public String getIpv4() {
        return ipv4;
    }
}
