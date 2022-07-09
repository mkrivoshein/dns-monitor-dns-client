package io.dnsmonitor.dns.client;

import io.dnsmonitor.dns.client.validators.DnsRecordType;
import io.dnsmonitor.dns.client.validators.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.ConstraintViolationException;

@RestController
@Validated
public class DnsClientController {
    private final Logger logger = LoggerFactory.getLogger(DnsClientController.class);

    private final DnsClientWorker worker;

    public DnsClientController(DnsClientWorker worker) {
        this.worker = worker;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String handleConstraintViolationException(ConstraintViolationException e) {
        logger.info("Input validation error: " + e.getMessage());
        return "Input validation error: " + e.getMessage();
    }

    @GetMapping("/dns/{domain}")
    @SuppressWarnings("unused")
    public DnsClientReply dnsQuery(@PathVariable("domain") @Domain String domain) {
        logger.info("DNS query for {}", domain);
        var result = worker.lookup(domain);
        try {
            return new DnsClientReply(domain, result);
        } finally {
            logger.info("DNS query for {} returned {} records", domain, result.size());
        }
    }

    @GetMapping("/dns/{recordtype}/{domain}")
    @SuppressWarnings("unused")
    public DnsClientReply dnsQuery(@PathVariable("recordtype") @DnsRecordType String recordType,
                                   @PathVariable("domain") @Domain String domain) {
        logger.info("DNS dig {} for {}", recordType, domain);
        var result = worker.lookup(recordType, domain);
        try {
            return new DnsClientReply(domain, result);
        } finally {
            logger.info("DNS dig {} for {} returned {} records", recordType, domain, result.size());
        }
    }
}
