package com.scandemo.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scandemo.backend.domain.Account;
import com.scandemo.backend.web.dto.ApiResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/** 除登录/健康检查外，所有 /api/** 都要带令牌（X-Token 请求头） */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Token";

    private final AccountService accountService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthInterceptor(AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Account acc = accountService.validateToken(request.getHeader(TOKEN_HEADER));
        if (acc == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(mapper.writeValueAsBytes(
                    ApiResponse.fail("未登录或登录已过期")));
            return false;
        }
        CurrentAccount.set(acc.getId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentAccount.clear();
    }
}
