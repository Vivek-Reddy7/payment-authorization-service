package com.vivekreddy.payments.service;

import com.vivekreddy.payments.domain.DeclineReason;

/**
 * The decision engine's answer: approve, or decline with a reason.
 *
 * <p>A record rather than a bare boolean so the reason cannot go missing. A
 * method returning {@code false} forces the caller to reconstruct why, and the
 * reconstruction drifts from the rule that produced it.
 */
public record Decision(boolean approved, DeclineReason reason) {

    private static final Decision APPROVED = new Decision(true, null);

    public static Decision approve() {
        return APPROVED;
    }

    public static Decision decline(DeclineReason reason) {
        return new Decision(false, reason);
    }
}
