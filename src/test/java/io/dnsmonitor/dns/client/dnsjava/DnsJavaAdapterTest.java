package io.dnsmonitor.dns.client.dnsjava;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.Type;

import static io.dnsmonitor.dns.client.dnsjava.DnsJavaAdapter.convertRecordType;
import static org.junit.jupiter.api.Assertions.*;

class DnsJavaAdapterTest {
    @Test
    public void testRecordTypeConvertor() {
        assertEquals(Type.AAAA, convertRecordType("AAAA"));
        assertEquals(Type.A, convertRecordType("A"));
        assertEquals(Type.CAA, convertRecordType("CAA"));
        assertEquals(Type.CNAME, convertRecordType("CNAME"));
        assertEquals(Type.MX, convertRecordType("MX"));
        assertEquals(Type.NS, convertRecordType("NS"));
        assertEquals(Type.SRV, convertRecordType("SRV"));
        assertEquals(Type.TXT, convertRecordType("TXT"));
    }
}