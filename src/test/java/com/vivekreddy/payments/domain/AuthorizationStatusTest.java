package com.vivekreddy.payments.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationStatusTest {

    @Test
    @DisplayName("APPROVED may be captured or voided")
    void approvedCanMove() {
        assertThat(AuthorizationStatus.APPROVED
                .canTransitionTo(AuthorizationStatus.CAPTURED)).isTrue();
        assertThat(AuthorizationStatus.APPROVED
                .canTransitionTo(AuthorizationStatus.VOIDED)).isTrue();
    }

    @Test
    @DisplayName("a captured authorization cannot be captured again")
    void noDoubleCapture() {
        // The one that costs money if it is wrong.
        assertThat(AuthorizationStatus.CAPTURED
                .canTransitionTo(AuthorizationStatus.CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("a voided authorization cannot be captured")
    void noCaptureAfterVoid() {
        assertThat(AuthorizationStatus.VOIDED
                .canTransitionTo(AuthorizationStatus.CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("a decline goes nowhere")
    void declinedIsTerminal() {
        for (AuthorizationStatus target : AuthorizationStatus.values()) {
            assertThat(AuthorizationStatus.DECLINED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("every status except APPROVED is terminal")
    void terminality() {
        assertThat(AuthorizationStatus.APPROVED.isTerminal()).isFalse();
        assertThat(AuthorizationStatus.CAPTURED.isTerminal()).isTrue();
        assertThat(AuthorizationStatus.VOIDED.isTerminal()).isTrue();
        assertThat(AuthorizationStatus.DECLINED.isTerminal()).isTrue();
    }
}
