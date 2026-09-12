package com.vivekreddy.payments.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a card number into something safe to store.
 *
 * <p>The service never persists a PAN. It keeps the last four digits, which
 * identify a card to a human without identifying it to an attacker, and a
 * SHA-256 fingerprint, which lets the same card be recognised across requests
 * without being recoverable from the database.
 *
 * <p>The pepper is what makes the fingerprint worth anything. A card number is
 * 16 digits with a checksum, so the space of valid PANs is small enough to
 * enumerate: an unsalted SHA-256 of a PAN can be brute-forced on a laptop, and
 * a leaked column of bare digests would be a leaked column of card numbers. The
 * pepper is a server-side secret that is never stored with the data, so an
 * attacker holding only the database cannot run that attack.
 *
 * <p>SHA-256 rather than a slow KDF is deliberate here and the reasoning is the
 * opposite of the password case: this runs on every authorization, the input is
 * not user-chosen or low-entropy, and the secret carrying the strength is the
 * pepper rather than the input.
 */
@Component
public class CardFingerprinter {

    private final byte[] pepper;

    public CardFingerprinter(@Value("${payments.card.pepper}") String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "payments.card.pepper must be set; an unpeppered card hash is "
                    + "brute-forceable because the PAN space is small");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    /** Hex SHA-256 of the pepper followed by the PAN. */
    public String fingerprint(String pan) {
        MessageDigest digest = sha256();
        digest.update(pepper);
        digest.update(pan.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Hex SHA-256 of an arbitrary string. Used for request bodies, not cards. */
    public String hash(String value) {
        return HexFormat.of().formatHex(
                sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String last4(String pan) {
        return pan.substring(pan.length() - 4);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to ship SHA-256, so this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
