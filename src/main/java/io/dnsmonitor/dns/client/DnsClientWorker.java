package io.dnsmonitor.dns.client;

import io.dnsmonitor.dns.client.dnsjava.DnsJavaAdapter;
import io.dnsmonitor.dns.client.model.DnsRecord;

import java.util.List;
import java.util.Set;

public class DnsClientWorker {
    private final static Set<String> RECORD_TYPES = Set.of(
            "A",
            "AAAA",
            "CAA",
            "CNAME",
            "MX",
            "NS",
            "SOA",
            "SRV",
            "TXT"
    );

    private final DnsJavaAdapter dnsJavaAdapter;

    public DnsClientWorker(DnsJavaAdapter dnsJavaAdapter) {
        this.dnsJavaAdapter = dnsJavaAdapter;
    }

    public List<DnsRecord> lookup(String domain) {
        return RECORD_TYPES.stream()
                .parallel()
                .flatMap(recordType -> lookup(recordType, domain).stream())
                .toList();
    }

    public List<DnsRecord> lookup(String recordType, String domain) {
        if (RECORD_TYPES.contains(recordType.toUpperCase())) {
            DnsLookupCommand dnsLookupCommand = dnsJavaAdapter.prepareLookup(recordType, domain);
            return dnsLookupCommand.lookupDnsRecords();
        } else {
            throw new IllegalArgumentException("Unsupported record type: " + recordType);
        }
    }
}
