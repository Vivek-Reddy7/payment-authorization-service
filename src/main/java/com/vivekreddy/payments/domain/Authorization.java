package com.vivekreddy.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

/**
 * A single card authorization.
 *
 * <p>Two things about this entity are deliberate and worth knowing before
 * changing it.
 *
 * <p><b>The card number is not here.</b> Only the last four digits and a hash
 * are stored. Nothing in this service can reconstruct a PAN, which is the
 * point: data you do not hold cannot leak. The last four exist because support
 * staff and cardholders identify a card by them; the hash exists so the same
 * card can be recognised across requests without storing it.
 *
 * <p><b>Money is a long of minor units, never a double.</b> 0.1 + 0.2 is not
 * 0.3 in binary floating point, and a rounding error in a payment system is a
 * reconciliation break. {@code amountMinor = 1050} with {@code currency = "USD"}
 * is $10.50. The currency is required alongside it because a bare number is
 * meaningless -- 1050 JPY and 1050 USD differ by two orders of magnitude, since
 * JPY has no minor unit at all.
 */
@Entity
@Table(name = "authorizations")
public class Authorization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    // Stored as CHAR(36) rather than a native UUID column. MySQL has no UUID
    // type, so a portable schema has to pick a representation -- and the text
    // form keeps ids readable in a query result, which matters when someone is
    // reading rows during an incident.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    /** Minor units. 1050 USD is $10.50. Never a floating point type. */
    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    /** ISO-4217 alphabetic code, uppercase. */
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "card_last4", nullable = false, updatable = false, length = 4)
    private String cardLast4;

    /** SHA-256 of the PAN with a configured pepper. Not reversible. */
    @Column(name = "card_fingerprint", nullable = false, updatable = false, length = 64)
    private String cardFingerprint;

    @Column(name = "merchant_reference", nullable = false, updatable = false, length = 64)
    private String merchantReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AuthorizationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason", length = 32)
    private DeclineReason declineReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Guards against lost updates when two requests touch one authorization at
     * once. Without it, a simultaneous capture and void can both read APPROVED
     * and both write, and the second silently wins.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Authorization() {
        // JPA.
    }

    private Authorization(long amountMinor, String currency, String cardLast4,
                          String cardFingerprint, String merchantReference,
                          AuthorizationStatus status, DeclineReason declineReason,
                          Instant now) {
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.cardLast4 = cardLast4;
        this.cardFingerprint = cardFingerprint;
        this.merchantReference = merchantReference;
        this.status = status;
        this.declineReason = declineReason;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Authorization approved(long amountMinor, String currency, String cardLast4,
                                         String cardFingerprint, String merchantReference,
                                         Instant now) {
        return new Authorization(amountMinor, currency, cardLast4, cardFingerprint,
                merchantReference, AuthorizationStatus.APPROVED, null, now);
    }

    public static Authorization declined(long amountMinor, String currency, String cardLast4,
                                         String cardFingerprint, String merchantReference,
                                         DeclineReason reason, Instant now) {
        return new Authorization(amountMinor, currency, cardLast4, cardFingerprint,
                merchantReference, AuthorizationStatus.DECLINED, reason, now);
    }

    /**
     * Moves to {@code target}, or throws if the transition is not permitted.
     *
     * <p>The check lives on the entity rather than in the service so that no
     * caller can bypass it. An entity that lets any code put it in any state is
     * a data structure, not a domain object.
     *
     * @throws IllegalStateTransitionException if the move is not allowed
     */
    public void transitionTo(AuthorizationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(id, status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public String getCardFingerprint() {
        return cardFingerprint;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public AuthorizationStatus getStatus() {
        return status;
    }

    public DeclineReason getDeclineReason() {
        return declineReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
