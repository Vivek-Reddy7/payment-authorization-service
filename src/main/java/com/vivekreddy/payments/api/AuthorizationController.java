package com.vivekreddy.payments.api;

import com.vivekreddy.payments.api.dto.AuthorizationRequest;
import com.vivekreddy.payments.api.dto.AuthorizationResponse;
import com.vivekreddy.payments.domain.Authorization;
import com.vivekreddy.payments.domain.AuthorizationStatus;
import com.vivekreddy.payments.service.AuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authorization API.
 *
 * <p>Thin on purpose: bind, delegate, map the result to a status code. No
 * business rules live here, so they cannot be bypassed by another entry point
 * later -- a scheduled job or a message consumer calls the same service and gets
 * the same rules.
 *
 * <p>The version is in the path. Payment integrations are long-lived and
 * partners upgrade on their own schedule, so a second version has to be able to
 * exist beside the first rather than replacing it.
 */
@RestController
@RequestMapping("/api/v1/authorizations")
@Validated
public class AuthorizationController {

    private final AuthorizationService service;

    public AuthorizationController(AuthorizationService service) {
        this.service = service;
    }

    /**
     * Creates an authorization.
     *
     * <p>{@code Idempotency-Key} is required rather than optional. Making it
     * optional means the unsafe path is the default, and the callers most likely
     * to omit it are exactly the ones whose retries will double-charge.
     *
     * <p>Returns 201 for both approvals and declines: a decline is a decision the
     * system made and recorded, and it has a URI. Only failures to decide are
     * errors.
     */
    @PostMapping
    public ResponseEntity<AuthorizationResponse> authorize(
            @Valid @RequestBody AuthorizationRequest request,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key must not be blank")
            @Size(max = 64, message = "Idempotency-Key must be at most 64 characters")
            String idempotencyKey) {

        Authorization authorization = service.authorize(request, idempotencyKey);
        return ResponseEntity
                .created(URI.create("/api/v1/authorizations/" + authorization.getId()))
                .body(AuthorizationResponse.from(authorization));
    }

    @GetMapping("/{id}")
    public AuthorizationResponse get(@PathVariable UUID id) {
        return AuthorizationResponse.from(service.get(id));
    }

    @GetMapping
    public Page<AuthorizationResponse> list(
            @RequestParam(required = false) AuthorizationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable).map(AuthorizationResponse::from);
    }

    /** Takes the funds held by an approved authorization. */
    @PostMapping("/{id}/capture")
    public AuthorizationResponse capture(@PathVariable UUID id) {
        return AuthorizationResponse.from(service.capture(id));
    }

    /** Releases an approved authorization without taking funds. */
    @PostMapping("/{id}/void")
    public AuthorizationResponse voidAuthorization(@PathVariable UUID id) {
        return AuthorizationResponse.from(service.voidAuthorization(id));
    }
}
