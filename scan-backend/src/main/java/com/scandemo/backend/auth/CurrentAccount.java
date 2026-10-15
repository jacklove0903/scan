package com.scandemo.backend.auth;

/** 当前请求所属账号（由 AuthInterceptor 从令牌解析后设置） */
public final class CurrentAccount {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private CurrentAccount() {
    }

    public static void set(Long accountId) {
        HOLDER.set(accountId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
