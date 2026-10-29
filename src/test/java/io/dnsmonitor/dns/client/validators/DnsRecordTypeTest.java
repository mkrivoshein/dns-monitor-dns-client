package io.dnsmonitor.dns.client.validators;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsRecordTypeTest {
    private final DnsRecordType.Validator validator = new DnsRecordType.Validator();

    @ParameterizedTest
    @ValueSource(strings = {"A", "AAAA", "CAA", "CNAME", "MX", "NS", "SOA", "SRV", "TXT", "mx", "Txt"})
    void acceptsSupportedTypes(String type) {
        assertTrue(validator.isValid(type, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PTR", "ptr", "DNSKEY", "ANY", "AXFR", "BOGUS", ""})
    void rejectsUnsupportedOrUnknownTypes(String type) {
        assertFalse(validator.isValid(type, null));
    }
}
