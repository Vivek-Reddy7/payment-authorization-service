package com.vivekreddy.payments.service;

import com.vivekreddy.payments.domain.DeclineReason;
import java.time.YearMonth;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether to approve an authorization.
 *
 * <p>Deliberately a stand-in. A real switch routes to the issuer and relays the
 * issuer's answer; it does not decide anything itself. What is real here is the
 * shape: rules are evaluated in a fixed order, each returns a machine-readable
 * reason, and the whole thing is a pure function of its inputs -- no clock
 * reads, no database, no network -- so it can be unit tested exhaustively
 * without a Spring context.
 *
 * <p>Order matters and is not arbitrary. An expired card is checked before the
 * amount limit, so a cardholder fixing a decline is told the card is expired
 * rather than being sent to try a smaller amount that will also fail.
 */
@Component
public class DecisionEngine {

    private final long perTransactionLimitMinor;
    private final Set<String> blockedBins;

    public DecisionEngine(
            @Value("${payments.limits.per-transaction-minor}") long perTransactionLimitMinor,
            @Value("${payments.blocked-bins}") Set<String> blockedBins) {
        this.perTransactionLimitMinor = perTransactionLimitMinor;
        this.blockedBins = Set.copyOf(blockedBins);
    }

    /**
     * @param pan          the full card number, used only to read its BIN; never stored
     * @param expiry       the card's expiry month
     * @param amountMinor  amount in minor units
     * @param today        the current month, passed in rather than read, so the
     *                     boundary cases are testable without waiting for a month to turn
     */
    public Decision decide(String pan, YearMonth expiry, long amountMinor, YearMonth today) {
        if (expiry.isBefore(today)) {
            return Decision.decline(DeclineReason.CARD_EXPIRED);
        }
        if (blockedBins.contains(bin(pan))) {
            return Decision.decline(DeclineReason.BLOCKED_BIN);
        }
        if (amountMinor > perTransactionLimitMinor) {
            return Decision.decline(DeclineReason.LIMIT_EXCEEDED);
        }
        return Decision.approve();
    }

    /** The Bank Identification Number: the first six digits, identifying the issuer. */
    private static String bin(String pan) {
        return pan.substring(0, 6);
    }
}
