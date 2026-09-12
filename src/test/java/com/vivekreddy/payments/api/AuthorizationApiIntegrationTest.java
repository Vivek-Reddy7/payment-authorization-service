package com.vivekreddy.payments.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API end to end: real HTTP binding, real validation, real Flyway schema,
 * real transactions against H2.
 *
 * <p>Not transactional and not rolled back, deliberately. Idempotency is about
 * what survives between requests, so a test that discards state between calls
 * would assert nothing about it. Each test uses its own idempotency keys and
 * merchant references instead of a shared clean slate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationApiIntegrationTest {

    private static final String GOOD_PAN = "4242424242424242";
    private static final String BLOCKED_PAN = "4111111111111111";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String body(String pan, long amountMinor, String reference) {
        return """
               {
                 "amountMinor": %d,
                 "currency": "USD",
                 "pan": "%s",
                 "expiry": "2030-12",
                 "merchantReference": "%s"
               }
               """.formatted(amountMinor, pan, reference);
    }

    @Test
    @DisplayName("approves a valid request and never echoes the card number")
    void approves() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", "it-approve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_PAN, 10_000L, "order-approve-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.declineReason").doesNotExist())
                .andExpect(jsonPath("$.amountMinor").value(10_000))
                .andExpect(jsonPath("$.cardLast4").value("4242"))
                .andReturn();

        // The response must not carry the PAN or the fingerprint anywhere, under
        // any key. Asserting on the raw payload catches a field added later that
        // no jsonPath assertion is looking at.
        String payload = result.getResponse().getContentAsString();
        assertThat(payload).doesNotContain(GOOD_PAN);
        assertThat(payload).doesNotContain("fingerprint");
    }

    @Test
    @DisplayName("a decline is a 201 with a reason, not an error")
    void declineIsNotAnError() throws Exception {
        mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", "it-decline-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(BLOCKED_PAN, 10_000L, "order-decline-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value("BLOCKED_BIN"));
    }

    @Test
    @DisplayName("replaying the same key and body returns the original authorization")
    void idempotentReplay() throws Exception {
        String key = "it-replay-1";
        String payload = body(GOOD_PAN, 25_000L, "order-replay-1");

        String first = mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode a = objectMapper.readTree(first);
        JsonNode b = objectMapper.readTree(second);

        // Same id means one authorization exists, not two. This is the whole
        // point of the feature: a client that retries after a timeout must not
        // authorize the cardholder twice.
        assertThat(b.get("id").asText()).isEqualTo(a.get("id").asText());
        assertThat(b.get("createdAt").asText()).isEqualTo(a.get("createdAt").asText());
    }

    @Test
    @DisplayName("reusing a key with a different amount is refused, not replayed")
    void idempotencyConflict() throws Exception {
        String key = "it-conflict-1";

        mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_PAN, 1_000L, "order-conflict-1")))
                .andExpect(status().isCreated());

        // Replaying the cached $10 approval for a $400 request would be the
        // dangerous failure. 409 is the only safe answer.
        mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_PAN, 40_000L, "order-conflict-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));
    }

    @Test
    @DisplayName("rejects a mistyped card number with a named field error")
    void rejectsBadPan() throws Exception {
        mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", "it-badpan-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("4242424242424241", 1_000L, "order-badpan-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.pan").exists());
    }

    @Test
    @DisplayName("rejects a zero amount")
    void rejectsZeroAmount() throws Exception {
        mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", "it-zero-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_PAN, 0L, "order-zero-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists());
    }

    @Test
    @DisplayName("captures an approved authorization, then refuses to capture it again")
    void captureThenDoubleCapture() throws Exception {
        String id = createApproved("it-capture-1", "order-capture-1");

        mockMvc.perform(post("/api/v1/authorizations/{id}/capture", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        // Taking the money twice is the worst outcome this service can produce,
        // so it gets an explicit test rather than relying on the enum's own.
        mockMvc.perform(post("/api/v1/authorizations/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.requestedStatus").value("CAPTURED"));
    }

    @Test
    @DisplayName("refuses to capture a voided authorization")
    void captureAfterVoid() throws Exception {
        String id = createApproved("it-void-1", "order-void-1");

        mockMvc.perform(post("/api/v1/authorizations/{id}/void", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        mockMvc.perform(post("/api/v1/authorizations/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("VOIDED"));
    }

    @Test
    @DisplayName("an unknown id is a 404")
    void unknownId() throws Exception {
        mockMvc.perform(get("/api/v1/authorizations/{id}",
                        "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Authorization not found"));
    }

    @Test
    @DisplayName("a missing Idempotency-Key is refused")
    void missingIdempotencyKey() throws Exception {
        // The header is required precisely because the callers most likely to
        // omit it are the ones whose retries would double-charge.
        mockMvc.perform(post("/api/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_PAN, 1_000L, "order-nokey-1")))
                .andExpect(status().isBadRequest());
    }

    private String createApproved(String key, String reference) throws Exception {
        String response = mockMvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GOOD_PAN, 5_000L, reference)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }
}
