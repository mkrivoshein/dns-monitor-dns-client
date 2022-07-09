package io.dnsmonitor.dns.client.validators;

import io.dnsmonitor.dns.client.dnsjava.DnsJavaAdapter;

import javax.validation.Constraint;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.validation.Payload;
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
