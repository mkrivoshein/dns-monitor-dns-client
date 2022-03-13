package io.dnsmonitor.dns.client;

import io.dnsmonitor.dns.client.dnsjava.DnsJavaAdapter;
import io.dnsmonitor.dns.client.model.ADnsRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DnsClientWorkerTest {
    private final ADnsRecord aDnsRecord0 = new ADnsRecord("127.0.0.1");
    private final ADnsRecord aDnsRecord1 = new ADnsRecord("127.0.1.1");

    @Mock
    DnsLookupCommand dnsLookupCommand;

    @Mock
    DnsJavaAdapter dnsJavaAdapter;

    @Test
    public void lookupARecords() {
        when(dnsJavaAdapter.prepareLookup("A", "example.test")).thenReturn(dnsLookupCommand);
        when(dnsLookupCommand.lookupDnsRecords()).thenReturn(List.of(aDnsRecord0, aDnsRecord1));

        DnsClientWorker worker = new DnsClientWorker(dnsJavaAdapter);
        var result = worker.lookup("A", "example.test");
        assertThat(result, is(List.of(aDnsRecord0, aDnsRecord1)));
    }

    @Test
    public void lookupAaaaRecordsWithLowercaseRecordType() {
        when(dnsJavaAdapter.prepareLookup("aaaa", "example.test")).thenReturn(dnsLookupCommand);
        when(dnsLookupCommand.lookupDnsRecords()).thenReturn(List.of()); // for purposes of this test data doesn't matter

        DnsClientWorker worker = new DnsClientWorker(dnsJavaAdapter);
        worker.lookup("aaaa", "example.test");
    }

    @Test
    public void lookupAllRecordsForUnknownDomain() {
        when(dnsJavaAdapter.prepareLookup(anyString(), eq("unknown.test"))).thenReturn(dnsLookupCommand);
        when(dnsLookupCommand.lookupDnsRecords()).thenReturn(List.of());

        DnsClientWorker worker = new DnsClientWorker(dnsJavaAdapter);
        var result = worker.lookup("unknown.test");
        assertThat(result, is(List.of()));

        verify(dnsJavaAdapter).prepareLookup("A", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("AAAA", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("CAA", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("CNAME", "unknown.test");
//        verify(dnsJavaAdapter).prepareLookup("DNSKEY", "unknown.test");
//        verify(dnsJavaAdapter).prepareLookup("DS", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("MX", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("NS", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("SOA", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("SRV", "unknown.test");
//        verify(dnsJavaAdapter).prepareLookup("TLSA", "unknown.test");
//        verify(dnsJavaAdapter).prepareLookup("TSIG", "unknown.test");
        verify(dnsJavaAdapter).prepareLookup("TXT", "unknown.test");
    }
}