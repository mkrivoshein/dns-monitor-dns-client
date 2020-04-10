package io.dnsmonitor.dns.client.model;

public class CaaDnsRecord extends DnsRecord {
    private final String flags;
    private final String type;
    private final String value;

    public CaaDnsRecord(int flags, String type, String value) {
        this(String.valueOf(flags), type, value);
    }

    public CaaDnsRecord(String flags, String type, String value) {
        super("CAA");
        this.flags = flags;
        this.type = type;
        this.value = value;
    }

    public String getFlags() {
        return flags;
    }

    public String getType() {
        return type;
    }

    public String getValue() {
        return value;
    }
}
