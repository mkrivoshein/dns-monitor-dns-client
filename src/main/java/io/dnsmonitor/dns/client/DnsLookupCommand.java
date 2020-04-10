package io.dnsmonitor.dns.client;

import io.dnsmonitor.dns.client.model.DnsRecord;

import java.util.List;

@FunctionalInterface
public interface DnsLookupCommand {
    List<DnsRecord> lookupDnsRecords();
}
