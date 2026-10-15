package com.scandemo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 登录、权限与账号隔离：不同账号的 key 和数据互不相通 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder b, String token) {
        return token == null ? b : b.header("X-Token", token);
    }

    private String login(String name, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + name + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }

    @Test
    void noToken_rejected_401() throws Exception {
        mvc.perform(get("/api/records")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/config")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inbound/push")).andExpect(status().isUnauthorized());
        // 健康检查免登录（部署自检用）
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void badPassword_rejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("账号或密码不对"));
    }

    @Test
    void sessionAndLogout_flow() throws Exception {
        String tok = login("admin", "itest-admin-seed-9");
        mvc.perform(with(get("/api/session"), tok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginName").value("admin"))
                .andExpect(jsonPath("$.data.isAdmin").value(true));
        mvc.perform(with(post("/api/auth/logout"), tok)).andExpect(status().isOk());
        // 退出后令牌失效
        mvc.perform(with(get("/api/session"), tok)).andExpect(status().isUnauthorized());
    }

    @Test
    void accountIsolation_andPerAccountCredentials() throws Exception {
        String adminTok = login("admin", "itest-admin-seed-9");

        // 管理员建第二个账号（另一套 OMS key）
        String name = "wh" + System.currentTimeMillis() % 100000;
        mvc.perform(with(post("/api/accounts"), adminTok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + name + "\",\"password\":\"pass1234\",\"displayName\":\"二号仓\","
                                + "\"appKey\":\"KEY-SECOND-ACCOUNT\",\"appSecret\":\"SECRET-2\",\"whCode\":\"WH2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canPush").value(true));
        String tok2 = login(name, "pass1234");

        // admin 上传一条记录；二号账号看不到（数据不互通）
        mvc.perform(with(post("/api/records/batch"), adminTok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"no\":1,\"sku\":\"ISO-A\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"1\",\"units\":\"6\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(with(get("/api/records").param("status", "TEMP"), tok2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(with(get("/api/records").param("status", "TEMP"), adminTok))
                .andExpect(jsonPath("$.data.length()").value(1));

        // 二号账号上传自己的记录，dryRun 的签名用的是它自己的 appKey
        mvc.perform(with(post("/api/records/batch"), tok2).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"no\":1,\"sku\":\"ISO-B\",\"len\":\"60\",\"wid\":\"40\",\"hei\":\"30\",\"wgt\":\"10\",\"qty\":\"2\",\"units\":\"1\"}]}"))
                .andExpect(status().isOk());
        MvcResult dry = mvc.perform(with(post("/api/inbound/push").param("dryRun", "true"), tok2))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = MAPPER.readTree(dry.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertTrue(data.path("signString").asText().startsWith("KEY-SECOND-ACCOUNT"), "签名必须用该账号自己的 appKey");
        JsonNode d = MAPPER.readTree(data.path("dataJson").asText());
        assertTrue(d.path("whCode").asText().equals("WH2"), "仓库代码必须是该账号自己的");

        // 二号账号（非管理员）不能做账号维护
        mvc.perform(with(get("/api/accounts"), tok2)).andExpect(status().isForbidden());
        mvc.perform(with(get("/api/accounts"), adminTok)).andExpect(status().isOk());

        // 别人的记录不能删（跨账号按 id 操作视为不存在）
        long adminRecId = MAPPER.readTree(mvc.perform(with(get("/api/records").param("status", "TEMP"), adminTok))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").get(0).path("id").asLong();
        mvc.perform(with(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/records/" + adminRecId), tok2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("记录不存在")));

        // 清理：各删各家
        mvc.perform(with(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/records").param("status", "ALL"), adminTok)).andExpect(status().isOk());
        mvc.perform(with(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/records").param("status", "ALL"), tok2)).andExpect(status().isOk());
    }

    @Test
    void picker_select_noPasswordLogin() throws Exception {
        String adminTok = login("admin", "itest-admin-seed-9");
        String name = "pk" + System.currentTimeMillis() % 100000;
        // 建免密业务账号（无 password、带部门）
        mvc.perform(with(post("/api/accounts"), adminTok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + name + "\",\"displayName\":\" picker测试员 \",\"dept\":\"百货\",\"whCode\":\"WH-PK\"}"))
                .andExpect(status().isOk());

        // options 免令牌可取：按部门分组、不含管理员、不含任何敏感字段
        MvcResult opt = mvc.perform(get("/api/auth/options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.dept=='百货')]").exists())
                .andReturn();
        JsonNode groups = MAPPER.readTree(opt.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        long accId = -1; long adminId = -1;
        JsonNode all = MAPPER.readTree(mvc.perform(with(get("/api/accounts"), adminTok))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        for (JsonNode a : all) {
            if (name.equals(a.path("loginName").asText())) accId = a.path("id").asLong();
            if ("admin".equals(a.path("loginName").asText())) adminId = a.path("id").asLong();
        }
        assertTrue(accId > 0 && adminId > 0);
        boolean listedInPicker = false;
        for (JsonNode g : groups) {
            for (JsonNode it : g.path("accounts")) {
                if (it.path("id").asLong() == accId) listedInPicker = true;
                if (it.path("id").asLong() == adminId) org.junit.jupiter.api.Assertions.fail("管理员不该出现在选择器里");
            }
        }
        assertTrue(listedInPicker, "业务账号应出现在 options 里");

        // 免密选择直接进入：拿到的令牌可访问业务接口
        MvcResult sel = mvc.perform(post("/api/auth/select").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + accId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isAdmin").value(false)).andReturn();
        String tok = MAPPER.readTree(sel.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("token").asText();
        mvc.perform(with(get("/api/session"), tok)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginName").value(name))
                .andExpect(jsonPath("$.data.dept").value("百货"));
        mvc.perform(with(get("/api/accounts"), tok)).andExpect(status().isForbidden());

        // 管理员账号不许免密选择（维护页门禁）
        mvc.perform(post("/api/auth/select").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + adminId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("管理员")));
    }

    @Test
    void changePassword_invalidatesOldTokens() throws Exception {
        String name = "pw" + System.currentTimeMillis() % 100000;
        String adminTok = login("admin", "itest-admin-seed-9");
        mvc.perform(with(post("/api/accounts"), adminTok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + name + "\",\"password\":\"pass1234\",\"whCode\":\"WH9\"}"))
                .andExpect(status().isOk());
        String tok = login(name, "pass1234");

        mvc.perform(with(post("/api/auth/password"), tok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"pass1234\",\"newPassword\":\"newpass99\"}"))
                .andExpect(status().isOk());
        // 旧令牌已失效
        mvc.perform(with(get("/api/session"), tok)).andExpect(status().isUnauthorized());
        // 新密码可登录
        String tok2 = login(name, "newpass99");
        mvc.perform(with(get("/api/session"), tok2)).andExpect(status().isOk());
    }
}
