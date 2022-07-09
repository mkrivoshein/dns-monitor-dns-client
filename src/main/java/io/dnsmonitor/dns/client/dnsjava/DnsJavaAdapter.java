package io.dnsmonitor.dns.client.dnsjava;

import io.dnsmonitor.dns.client.DnsLookupCommand;
import io.dnsmonitor.dns.client.model.*;
import org.xbill.DNS.Record;
import org.xbill.DNS.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DnsJavaAdapter {
    public DnsLookupCommand prepareLookup(String recordType, String domain) {
        return () -> {
            int recordTypeInt = convertRecordType(recordType.toUpperCase());

            if (recordTypeInt == -1) {
                throw new IllegalArgumentException("Unsupported record type: " + recordType);
            }

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

    public static int convertRecordType(String recordType) {
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
        } else if (record instanceof SOARecord) {
            answer = convertSoaRecord((SOARecord) record);
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

    private static DnsRecord convertSoaRecord(SOARecord record) {
        return new SoaDnsRecord(record.getHost().toString(true),
                record.getAdmin().toString(true),
                record.getSerial());
    }
}
