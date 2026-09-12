package com.vivekreddy.payments.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of an authorization.
 *
 * <p>The permitted transitions live here rather than in the service, so there is
 * exactly one place to look when asking "can this move to that?". A state
 * machine scattered across if-statements in a service layer is the usual way
 * these grow a hole -- some path forgets a check and a voided authorization
 * gets captured.
 */
public enum AuthorizationStatus {

    /** Approved and holding funds. The only state that can still move. */
    APPROVED,

    /** The issuer said no. Terminal. */
    DECLINED,

    /** Funds taken. Terminal. */
    CAPTURED,

    /** Released before capture. Terminal. */
    VOIDED;

    private static final Set<AuthorizationStatus> FROM_APPROVED =
            EnumSet.of(CAPTURED, VOIDED);

    /**
     * Whether this status may legally become {@code target}.
     *
     * <p>Only APPROVED has anywhere to go. Everything else is terminal, and
     * attempting to move a terminal authorization is a caller error rather than
     * a no-op -- silently ignoring it hides a double-capture bug in the caller.
     */
    public boolean canTransitionTo(AuthorizationStatus target) {
        return this == APPROVED && FROM_APPROVED.contains(target);
    }

    public boolean isTerminal() {
        return this != APPROVED;
    }
}
