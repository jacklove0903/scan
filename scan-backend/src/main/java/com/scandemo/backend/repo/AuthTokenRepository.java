package com.scandemo.backend.repo;

import com.scandemo.backend.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

    List<AuthToken> findByAccountId(Long accountId);

    void deleteByExpiresAtBefore(LocalDateTime time);
}
