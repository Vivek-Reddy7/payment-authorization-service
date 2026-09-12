package com.vivekreddy.payments.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Luhn check on a card number.
 *
 * <p>Worth doing at the edge because it is free and catches the overwhelmingly
 * common case: a mistyped digit. Rejecting those here means they never reach the
 * decision engine, never occupy a row, and never produce a decline that support
 * has to explain.
 *
 * <p>It proves only that the digits are internally consistent. It says nothing
 * about whether the card exists, which is the issuer's business.
 */
public class PanValidator implements ConstraintValidator<Pan, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null and blank are @NotBlank's job. Reporting both here would show the
        // caller two errors for one mistake.
        if (value == null || value.isBlank()) {
            return true;
        }
        if (!value.matches("^\\d{13,19}$")) {
            return false;
        }
        return luhnValid(value);
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubling) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
