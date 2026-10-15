package com.scandemo.backend.web;

import com.scandemo.backend.auth.AccountService;
import com.scandemo.backend.auth.CurrentAccount;
import com.scandemo.backend.config.OmsProperties;
import com.scandemo.backend.domain.Account;
import com.scandemo.backend.web.dto.ApiResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 账号维护页（登录/改密/账号维护）与配置摘要；页面走现有 /api/ 反代，无需额外 nginx 配置 */
@RestController
public class DraftController {

    private final OmsProperties props;
    private final AccountService accountService;

    public DraftController(OmsProperties props, AccountService accountService) {
        this.props = props;
        this.accountService = accountService;
    }

    /** 独立登录页。charset 必须显式给出，否则按 ISO-8859-1 写出会乱码 */
    @GetMapping(value = "/api/login", produces = "text/html;charset=UTF-8")
    public Resource loginPage() {
        return new ClassPathResource("static/login.html");
    }

    /** 账号维护页 HTML（原草稿页改造，路由保持 /api/draft 不变）。charset 必须显式给出，否则按 ISO-8859-1 写出会乱码 */
    @GetMapping(value = "/api/draft", produces = "text/html;charset=UTF-8")
    public Resource page() {
        return new ClassPathResource("static/draft.html");
    }

    /** 配置摘要（当前账号视角）：只暴露非敏感项，appSecret 绝不返回 */
    @GetMapping("/api/config")
    public ApiResponse<Map<String, Object>> config() {
        Account acc = accountService.require(CurrentAccount.get());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loginName", acc.getLoginName());
        data.put("displayName", acc.getDisplayName() == null ? acc.getLoginName() : acc.getDisplayName());
        data.put("isAdmin", acc.isAdmin());
        data.put("whCode", acc.getWhCode());
        data.put("inboundType", props.getInboundType());
        data.put("arrivalMode", props.getArrivalMode());
        data.put("unitsPerBox", props.getUnitsPerBox());
        data.put("boxMarkIncrementType", props.getBoxMarkIncrementType());
        data.put("appKey", AccountService.mask(acc.getAppKey()));
        data.put("canPush", acc.canPush());
        return ApiResponse.ok(data);
    }
}
