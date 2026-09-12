package com.vivekreddy.payments.service;

import com.vivekreddy.payments.domain.DeclineReason;
import java.time.YearMonth;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision rules, tested without a Spring context.
 *
 * <p>Possible because the engine is a pure function -- it reads no clock and
 * touches no database. That is the payoff for passing "today" in as an argument:
 * these run in milliseconds and the month-boundary cases can actually be
 * asserted rather than hoped for.
 */
class DecisionEngineTest {

    private static final YearMonth TODAY = YearMonth.of(2026, 9);

    // 500.00 limit; BINs 400000 and 411111 blocked.
    private final DecisionEngine engine =
            new DecisionEngine(50_000L, Set.of("400000", "411111"));

    private static final String GOOD_PAN = "4242424242424242";   // BIN 424242
    private static final String BLOCKED_PAN = "4111111111111111"; // BIN 411111

    @Test
    @DisplayName("approves a good card inside the limit")
    void approvesGoodCard() {
        Decision decision = engine.decide(GOOD_PAN, YearMonth.of(2030, 1), 10_000L, TODAY);
        assertThat(decision.approved()).isTrue();
        assertThat(decision.reason()).isNull();
    }

    @Nested
    @DisplayName("the per-transaction limit")
    class Limit {

        @Test
        @DisplayName("approves exactly at the limit")
        void approvesAtLimit() {
            // The boundary is the bug that actually happens: > vs >=. Asserting
            // both sides of it is the only way to pin which one is meant.
            assertThat(engine.decide(GOOD_PAN, YearMonth.of(2030, 1), 50_000L, TODAY).approved())
                    .isTrue();
        }

        @Test
        @DisplayName("declines one minor unit over")
        void declinesOverLimit() {
            Decision decision =
                    engine.decide(GOOD_PAN, YearMonth.of(2030, 1), 50_001L, TODAY);
            assertThat(decision.approved()).isFalse();
            assertThat(decision.reason()).isEqualTo(DeclineReason.LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("card expiry")
    class Expiry {

        @Test
        @DisplayName("a card expiring this month is still good")
        void validInExpiryMonth() {
            // Cards are valid through the END of their expiry month, so "expires
            // September" is usable all of September. Getting this wrong rejects
            // live cards for up to 30 days, and does it silently.
            assertThat(engine.decide(GOOD_PAN, TODAY, 1_000L, TODAY).approved()).isTrue();
        }

        @Test
        @DisplayName("declines the month after expiry")
        void declinesAfterExpiry() {
            Decision decision =
                    engine.decide(GOOD_PAN, YearMonth.of(2026, 8), 1_000L, TODAY);
            assertThat(decision.approved()).isFalse();
            assertThat(decision.reason()).isEqualTo(DeclineReason.CARD_EXPIRED);
        }
    }

    @Test
    @DisplayName("declines a blocked BIN")
    void declinesBlockedBin() {
        Decision decision = engine.decide(BLOCKED_PAN, YearMonth.of(2030, 1), 1_000L, TODAY);
        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).isEqualTo(DeclineReason.BLOCKED_BIN);
    }

    @Test
    @DisplayName("reports expiry first when a card is both expired and over the limit")
    void expiryBeatsLimit() {
        // Rule order is a product decision, not an implementation detail: the
        // cardholder should be told the card is dead rather than being sent off
        // to retry a smaller amount that will fail for the same reason.
        Decision decision =
                engine.decide(GOOD_PAN, YearMonth.of(2020, 1), 999_999L, TODAY);
        assertThat(decision.reason()).isEqualTo(DeclineReason.CARD_EXPIRED);
    }
}
