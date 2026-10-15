package com.scandemo.backend.web;

import com.scandemo.backend.auth.AccountService;
import com.scandemo.backend.auth.AuthInterceptor;
import com.scandemo.backend.auth.CurrentAccount;
import com.scandemo.backend.domain.Account;
import com.scandemo.backend.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 登录 / 退出 / 会话信息 / 改密码，以及部署自检用的健康检查 */
@RestController
public class AuthController {

    private final AccountService service;

    public AuthController(AccountService service) {
        this.service = service;
    }

    /** 无需登录：部署健康检查用 */
    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ok");
        return ApiResponse.ok(data);
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.login(body.get("loginName"), body.get("password")));
    }

    /** 采集页选择器数据与免密选择登录（均免令牌）：select 只放行启用中的业务账号，管理员账号会被拒绝 */
    @GetMapping("/api/auth/options")
    public ApiResponse<java.util.List<Map<String, Object>>> options() {
        return ApiResponse.ok(service.pickerOptions());
    }

    @PostMapping("/api/auth/select")
    public ApiResponse<Map<String, Object>> select(@RequestBody Map<String, Object> body) {
        Object id = body.get("accountId");
        Long accountId = id == null ? null : Long.parseLong(String.valueOf(id));
        return ApiResponse.ok(service.selectAccount(accountId));
    }

    @PostMapping("/api/auth/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false) String token) {
        service.logout(token);
        return ApiResponse.ok();
    }

    @GetMapping("/api/session")
    public ApiResponse<Map<String, Object>> session() {
        Account acc = service.require(CurrentAccount.get());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loginName", acc.getLoginName());
        data.put("displayName", acc.getDisplayName() == null ? acc.getLoginName() : acc.getDisplayName());
        data.put("dept", acc.getDept());
        data.put("isAdmin", acc.isAdmin());
        return ApiResponse.ok(data);
    }

    @PostMapping("/api/auth/password")
    public ApiResponse<Void> password(@RequestBody Map<String, String> body) {
        service.changePassword(CurrentAccount.get(), body.get("oldPassword"), body.get("newPassword"));
        return ApiResponse.ok();
    }
}
