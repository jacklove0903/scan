package com.scandemo.backend.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/** 登录令牌（存库，重启不失效；默认 30 天过期） */
@Entity
@Table(name = "auth_token")
public class AuthToken {

    @Id
    @Column(length = 64)
    private String token;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
