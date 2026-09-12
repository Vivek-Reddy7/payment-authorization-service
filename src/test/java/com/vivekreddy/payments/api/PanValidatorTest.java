package com.vivekreddy.payments.api;

import com.vivekreddy.payments.api.validation.PanValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class PanValidatorTest {

    private final PanValidator validator = new PanValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "4242424242424242",
            "4111111111111111",
            "5555555555554444",
            "378282246310005"     // 15 digits, Amex length
    })
    @DisplayName("accepts card numbers that pass Luhn")
    void acceptsValid(String pan) {
        assertThat(validator.isValid(pan, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4242424242424241",   // last digit wrong
            "4242424242424243",
            "1234567890123456"
    })
    @DisplayName("rejects a mistyped digit")
    void rejectsBadChecksum(String pan) {
        assertThat(validator.isValid(pan, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"424242424242", "42424242424242424242", "4242-4242-4242-4242", "abcd"})
    @DisplayName("rejects wrong length and non-digits")
    void rejectsMalformed(String pan) {
        assertThat(validator.isValid(pan, null)).isFalse();
    }

    @Test
    @DisplayName("passes null and blank through to @NotBlank")
    void defersEmptyToNotBlank() {
        // Otherwise one missing field produces two error messages, and the
        // caller cannot tell whether they sent something invalid or nothing.
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("  ", null)).isTrue();
    }
}
