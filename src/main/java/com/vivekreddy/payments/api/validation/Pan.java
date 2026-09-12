package com.vivekreddy.payments.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;

/** A card number that is 13-19 digits and passes the Luhn check. */
@Documented
@Constraint(validatedBy = PanValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE, RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pan {

    String message() default "pan must be 13-19 digits and pass the Luhn checksum";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
