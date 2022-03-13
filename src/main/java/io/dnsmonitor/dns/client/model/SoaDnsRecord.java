package io.dnsmonitor.dns.client.model;

public class SoaDnsRecord extends DnsRecord {
    private final String value;
    private final String admin;
    private final long serial;

    public SoaDnsRecord(String value, String admin, long serial) {
        super("SOA");
        this.value = value;
        this.admin = admin;
        this.serial = serial;
    }

    public String getValue() {
        return value;
    }

    public String getAdmin() {
        return admin;
    }

    public long getSerial() {
        return serial;
    }
}
