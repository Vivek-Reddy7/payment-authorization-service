package com.vivekreddy.payments.domain;

/**
 * Why an authorization was declined.
 *
 * <p>An enum rather than free text because this field is read by machines: it
 * drives retry behaviour in callers, and a decline a caller may safely retry
 * (LIMIT_EXCEEDED on a smaller amount) must be distinguishable from one it must
 * not (BLOCKED_BIN). Free-text reasons cannot be switched on.
 */
public enum DeclineReason {

    /** Amount is above the per-transaction ceiling. */
    LIMIT_EXCEEDED,

    /** The issuing range is blocked. Retrying will not help. */
    BLOCKED_BIN,

    /** The card's expiry date has passed. */
    CARD_EXPIRED
}
