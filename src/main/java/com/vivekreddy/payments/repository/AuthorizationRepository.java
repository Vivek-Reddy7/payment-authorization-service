package com.vivekreddy.payments.repository;

import com.vivekreddy.payments.domain.Authorization;
import com.vivekreddy.payments.domain.AuthorizationStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorizationRepository extends JpaRepository<Authorization, UUID> {

    Page<Authorization> findByStatus(AuthorizationStatus status, Pageable pageable);

    Page<Authorization> findByCardFingerprint(String cardFingerprint, Pageable pageable);
}
