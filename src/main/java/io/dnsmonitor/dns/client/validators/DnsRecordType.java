package io.dnsmonitor.dns.client.validators;

import io.dnsmonitor.dns.client.dnsjava.DnsJavaAdapter;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = { DnsRecordType.Validator.class })
public @interface DnsRecordType {
    String message() default "value should be a valid DNS record type";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    class Validator implements ConstraintValidator<DnsRecordType, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return DnsJavaAdapter.convertRecordType(value.toUpperCase()) != -1;
        }
    }
}
