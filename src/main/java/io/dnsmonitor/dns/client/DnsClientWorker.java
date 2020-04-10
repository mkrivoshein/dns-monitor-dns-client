package io.dnsmonitor.dns.client;

import io.dnsmonitor.dns.client.dnsjava.DnsJavaAdapter;
import io.dnsmonitor.dns.client.model.DnsRecord;

import java.util.List;

public class DnsClientWorker {
    private final DnsJavaAdapter dnsJavaAdapter;

    public DnsClientWorker(DnsJavaAdapter dnsJavaAdapter) {
        this.dnsJavaAdapter = dnsJavaAdapter;
    }

    public List<DnsRecord> lookup(String recordType, String domain) {
        DnsLookupCommand dnsLookupCommand = dnsJavaAdapter.prepareLookup(recordType, domain);
        return dnsLookupCommand.lookupDnsRecords();
    }
}
