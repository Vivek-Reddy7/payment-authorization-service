package com.vivekreddy.payments.api.dto;

import com.vivekreddy.payments.domain.Authorization;
import com.vivekreddy.payments.domain.AuthorizationStatus;
import com.vivekreddy.payments.domain.DeclineReason;
import java.time.Instant;
import java.util.UUID;

/**
 * What the caller gets back.
 *
 * <p>A separate type from the entity on purpose. Serialising the entity would
 * publish the card fingerprint and the JPA version column, and would mean any
 * future column is exposed by default -- the wrong direction for a payments API,
 * where the safe default is that a new field stays private until someone decides
 * otherwise. Here that decision is this file.
 */
public record AuthorizationResponse(
        UUID id,
        AuthorizationStatus status,
        DeclineReason declineReason,
        long amountMinor,
        String currency,
        String cardLast4,
        String merchantReference,
        Instant createdAt,
        Instant updatedAt) {

    public static AuthorizationResponse from(Authorization authorization) {
        return new AuthorizationResponse(
                authorization.getId(),
                authorization.getStatus(),
                authorization.getDeclineReason(),
                authorization.getAmountMinor(),
                authorization.getCurrency(),
                authorization.getCardLast4(),
                authorization.getMerchantReference(),
                authorization.getCreatedAt(),
                authorization.getUpdatedAt());
    }
}
