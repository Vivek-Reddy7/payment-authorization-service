package com.vivekreddy.payments.api.dto;

import com.vivekreddy.payments.api.validation.Pan;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;

/**
 * An authorization request.
 *
 * <p>Every field is constrained, because this is the boundary: past this point
 * the code assumes the data is well formed, and that assumption is only safe if
 * something enforced it here. Validation failures become a 400 with the offending
 * fields named, handled once in {@code ApiExceptionHandler}.
 *
 * <p>{@code amountMinor} is a long of minor units, matching the entity. Accepting
 * a decimal here and converting would reintroduce the floating point problem at
 * the edge, which is where it is hardest to see.
 */
public record AuthorizationRequest(

        @NotNull(message = "amountMinor is required")
        @Min(value = 1, message = "amountMinor must be positive")
        Long amountMinor,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$",
                 message = "currency must be a three-letter uppercase ISO-4217 code")
        String currency,

        @NotBlank(message = "pan is required")
        @Pan
        String pan,

        @NotNull(message = "expiry is required")
        YearMonth expiry,

        @NotBlank(message = "merchantReference is required")
        @Size(max = 64, message = "merchantReference must be at most 64 characters")
        String merchantReference) {
}
