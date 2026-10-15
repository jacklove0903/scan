package com.scandemo.backend.web;

import com.scandemo.backend.auth.AccountService;
import com.scandemo.backend.auth.CurrentAccount;
import com.scandemo.backend.domain.Account;
import com.scandemo.backend.web.dto.ApiResponse;
import com.scandemo.backend.web.dto.AccountRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 账号维护表（仅管理员）：每个账号一套 OMS key，数据互不相通 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        service.requireAdmin(CurrentAccount.get());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Account a : service.listAccounts()) {
            out.add(toView(a, CurrentAccount.get()));
        }
        return ApiResponse.ok(out);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody AccountRequest req) {
        service.requireAdmin(CurrentAccount.get());
        Account a = service.createAccount(req.getLoginName(), req.getPassword(), req.getDisplayName(),
                req.getAppKey(), req.getAppSecret(), req.getWhCode(),
                Boolean.TRUE.equals(req.getIsAdmin()), !Boolean.FALSE.equals(req.getEnabled()));
        return ApiResponse.ok(toView(a, CurrentAccount.get()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody AccountRequest req) {
        service.requireAdmin(CurrentAccount.get());
        Account a = service.updateAccount(CurrentAccount.get(), id, req.getDisplayName(),
                req.getAppKey(), req.getAppSecret(), req.getWhCode(),
                req.getIsAdmin(), req.getEnabled(), req.getPassword());
        return ApiResponse.ok(toView(a, CurrentAccount.get()));
    }

    /** 密钥不回传明文：只露前 6 位，改的话在编辑里填新的 */
    private static Map<String, Object> toView(Account a, Long meId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("loginName", a.getLoginName());
        m.put("displayName", a.getDisplayName());
        m.put("whCode", a.getWhCode());
        m.put("appKey", AccountService.mask(a.getAppKey()));
        m.put("appSecretSet", a.getAppSecret() != null && !a.getAppSecret().isEmpty());
        m.put("isAdmin", a.isAdmin());
        m.put("enabled", a.isEnabled());
        m.put("canPush", a.canPush());
        m.put("self", a.getId().equals(meId));
        return m;
    }
}
