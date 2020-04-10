package io.dnsmonitor.dns.client.dnsjava;

import io.dnsmonitor.dns.client.DnsLookupCommand;
import io.dnsmonitor.dns.client.model.ADnsRecord;
import io.dnsmonitor.dns.client.model.AaaaDnsRecord;
import io.dnsmonitor.dns.client.model.CaaDnsRecord;
import io.dnsmonitor.dns.client.model.CnameDnsRecord;
import io.dnsmonitor.dns.client.model.DnsRecord;
import io.dnsmonitor.dns.client.model.MxDnsRecord;
import io.dnsmonitor.dns.client.model.NsDnsRecord;
import io.dnsmonitor.dns.client.model.SrvDnsRecord;
import io.dnsmonitor.dns.client.model.TxtDnsRecord;
import io.dnsmonitor.dns.client.model.UnknownDnsRecord;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CAARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DnsJavaAdapter {
    public DnsLookupCommand prepareLookup(String recordType, String domain) {
        return () -> {
            int recordTypeInt = convertRecordType(recordType.toUpperCase());
            try {
                Record[] records = new Lookup(domain, recordTypeInt).run();

                if (records == null || records.length == 0) {
                    return List.of();
                }

                return Arrays.stream(records)
                        .flatMap(DnsJavaAdapter::convertRecord)
                        .collect(Collectors.toList());
            } catch (TextParseException e) {
                throw new RuntimeException(e);
            }
        };
    }

    static int convertRecordType(String recordType) {
        return Type.value(recordType, false);
    }

    static Stream<DnsRecord> convertRecord(Record record) {
        final DnsRecord answer;
        if (record instanceof ARecord) {
            answer = convertARecord((ARecord) record);
        } else if (record instanceof AAAARecord) {
            answer = convertAaaaRecord((AAAARecord) record);
        } else if (record instanceof CNAMERecord) {
            answer = convertCnameRecord((CNAMERecord) record);
        } else if (record instanceof TXTRecord) {
            return convertTxtRecord((TXTRecord) record);
        } else if (record instanceof MXRecord) {
            answer = convertMxRecord((MXRecord) record);
        } else if (record instanceof NSRecord) {
            answer = convertNsRecord((NSRecord) record);
        } else if (record instanceof SRVRecord) {
            answer = convertSrvRecord((SRVRecord) record);
        } else if (record instanceof CAARecord) {
            answer = convertCaaRecord((CAARecord) record);
        } else {
            answer = new UnknownDnsRecord(Type.string(record.getType()));
        }

        return Stream.of(answer);
    }

    private static DnsRecord convertARecord(ARecord record) {
        return new ADnsRecord(record.rdataToString());
    }

    private static DnsRecord convertAaaaRecord(AAAARecord record) {
        return new AaaaDnsRecord(record.rdataToString());
    }

    private static DnsRecord convertCnameRecord(CNAMERecord record) {
        return new CnameDnsRecord(record.getTarget().toString(true));
    }

    private static Stream<DnsRecord> convertTxtRecord(TXTRecord record) {
        return record.getStrings().stream().map(TxtDnsRecord::new);
    }

    private static DnsRecord convertMxRecord(MXRecord record) {
        return new MxDnsRecord(record.getPriority(), record.getTarget().toString(true));
    }

    private static DnsRecord convertNsRecord(NSRecord record) {
        return new NsDnsRecord(record.getTarget().toString(true));
    }

    private static DnsRecord convertSrvRecord(SRVRecord record) {
        return new SrvDnsRecord(record.getPriority(),
                record.getWeight(),
                record.getPort(),
                record.getTarget().toString(true));
    }

    private static DnsRecord convertCaaRecord(CAARecord record) {
        return new CaaDnsRecord(record.getFlags(), record.getTag(), record.getValue());
    }
}
