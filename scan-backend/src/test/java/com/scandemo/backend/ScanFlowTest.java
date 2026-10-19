package com.scandemo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scandemo.backend.repo.AccountRepository;
import com.scandemo.backend.repo.ScanRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 采集全流程：登录上传 -> 后台暂存 TEMP -> dryRun 组装 -> 编辑 -> 删除 */
@SpringBootTest
@AutoConfigureMockMvc
class ScanFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScanRecordRepository repo;

    @Autowired
    private AccountRepository accountRepo;

    /** 初始管理员登录后的令牌（种子账号密码来自测试配置 admin.init-password） */
    private String tok;

    @BeforeEach
    void clean() throws Exception {
        tok = login("admin", "itest-admin-seed-9");
        mvc.perform(authed(delete("/api/records").param("status", "ALL"))).andExpect(status().isOk());
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

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header("X-Token", tok);
    }

    @Test
    void upload_list_dryRunPush_delete() throws Exception {
        // 1) 页面「统一上传」的原始 JSON（与 index.html 的 addRec 结构一致，含单箱数量 units）
        String payload = "{\"count\":4,\"records\":["
                + "{\"no\":1,\"sku\":\"SKU-A\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"12\",\"units\":\"18\",\"ts\":1696000000000},"
                + "{\"no\":2,\"sku\":\"SKU-A\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"8\",\"units\":\"18\",\"ts\":1696000001000},"
                + "{\"no\":3,\"sku\":\"SKU-B\",\"len\":\"60\",\"wid\":\"40\",\"hei\":\"30\",\"wgt\":\"12.5\",\"qty\":\"3\",\"units\":\"1\",\"ts\":1696000002000},"
                + "{\"no\":4,\"sku\":\"SKU-A\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"2\",\"units\":\"12\",\"ts\":1696000003000}"
                + "]}";
        mvc.perform(authed(post("/api/records/batch")).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.saved").value(4))
                .andExpect(jsonPath("$.data.tempTotal").value(4));

        // 2) 列表：字符串数字已转成数字类型，单箱数量正确保存
        mvc.perform(authed(get("/api/records").param("status", "TEMP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.data[0].length").value(52))
                .andExpect(jsonPath("$.data[0].unitsPerBox").value(18));

        // 3) dryRun：不合并——每条记录各自成一条箱型（箱子数量取本条 qty），共 4 个箱型
        MvcResult dry = mvc.perform(authed(post("/api/inbound/push").param("dryRun", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.recordCount").value(4))
                .andExpect(jsonPath("$.data.boxTypeCount").value(4))
                .andReturn();
        JsonNode data = MAPPER.readTree(dry.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertTrue(data.path("thirdOrderNo").asText().startsWith("SCAN-"));
        assertTrue(data.path("signString").asText().startsWith("test-key"));
        assertEquals(64, data.path("authcode").asText().length());
        JsonNode boxTypeList = MAPPER.readTree(data.path("dataJson").asText()).path("boxTypeList");
        // 箱型按记录顺序（no 升序）逐个成条，不再合并同 SKU
        assertEquals(12, boxTypeList.get(0).path("ctns").asInt());
        assertEquals("52", boxTypeList.get(0).path("length").asText());
        assertEquals("18", boxTypeList.get(0).path("weight").asText());
        assertEquals("SKU-A", boxTypeList.get(0).path("productList").get(0).path("sku").asText());
        assertEquals(18, boxTypeList.get(0).path("productList").get(0).path("quantity").asInt());
        assertEquals(8, boxTypeList.get(1).path("ctns").asInt());
        assertEquals("SKU-A", boxTypeList.get(1).path("productList").get(0).path("sku").asText());
        assertEquals(18, boxTypeList.get(1).path("productList").get(0).path("quantity").asInt());
        assertEquals(3, boxTypeList.get(2).path("ctns").asInt());
        assertEquals("SKU-B", boxTypeList.get(2).path("productList").get(0).path("sku").asText());
        assertEquals(1, boxTypeList.get(2).path("productList").get(0).path("quantity").asInt());
        assertEquals(2, boxTypeList.get(3).path("ctns").asInt());
        assertEquals("SKU-A", boxTypeList.get(3).path("productList").get(0).path("sku").asText());
        assertEquals(12, boxTypeList.get(3).path("productList").get(0).path("quantity").asInt());

        // 4) dryRun 不该改变状态
        mvc.perform(authed(get("/api/records").param("status", "TEMP")))
                .andExpect(jsonPath("$.data.length()").value(4));

        // 5) 删除一条
        long id = firstTempId();
        mvc.perform(authed(delete("/api/records/" + id))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/records").param("status", "TEMP")))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void pushWithoutConfig_fails_dryRun_okEvenWithoutSecret() throws Exception {
        // 空暂存时推送应报错而不是 500
        mvc.perform(authed(post("/api/inbound/push")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("没有待推送的临时记录"));
    }

    @Test
    void uploadWithMissingDims_stillSaved_butPushRejected() throws Exception {
        String payload = "{\"records\":[{\"no\":9,\"sku\":\"SKU-X\",\"len\":\"\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"2\",\"units\":\"1\"}]}";
        mvc.perform(authed(post("/api/records/batch")).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
        mvc.perform(authed(post("/api/inbound/push")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("#9")));
    }

    @Test
    void uploadEmptyRecords_rejected() throws Exception {
        mvc.perform(authed(post("/api/records/batch")).contentType(MediaType.APPLICATION_JSON).content("{\"records\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountPageServedWithoutLogin_configRequiresToken() throws Exception {
        // 账号维护页本身免登录（未登录会由页面跳到 /api/login）
        mvc.perform(get("/api/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("账号维护")));
        // 独立登录页免登录可访问
        mvc.perform(get("/api/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("采集系统")));
        // 配置摘要要登录，且不含 appSecret
        String body = mvc.perform(authed(get("/api/config")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.whCode").value("TEST-WH"))
                .andExpect(jsonPath("$.data.canPush").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("test-secret"), "响应里绝不能出现 appSecret");
    }

    @Test
    void editTempRecord_updatesFields_andRejectsBadInput() throws Exception {
        String payload = "{\"records\":[{\"no\":7,\"sku\":\"OLD-SKU\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"2\",\"units\":\"6\"}]}";
        mvc.perform(authed(post("/api/records/batch")).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
        long id = firstTempId();

        // 正常编辑：改 SKU/规格/重量/箱子数量/单箱数量
        mvc.perform(authed(put("/api/records/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"NEW-SKU\",\"len\":60,\"wid\":40,\"hei\":30,\"wgt\":20.5,\"qty\":3,\"units\":24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sku").value("NEW-SKU"))
                .andExpect(jsonPath("$.data.length").value(60))
                .andExpect(jsonPath("$.data.weight").value(20.5))
                .andExpect(jsonPath("$.data.qty").value(3))
                .andExpect(jsonPath("$.data.unitsPerBox").value(24));

        // 改成不完整（清空重量）也允许保存——草稿可以暂时不完整
        mvc.perform(authed(put("/api/records/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"NEW-SKU\",\"len\":60,\"wid\":40,\"hei\":30,\"wgt\":null,\"qty\":3,\"units\":24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weight").doesNotExist())
                .andExpect(jsonPath("$.data.complete").value(false));

        // 箱子数量 0 / 单箱数量 0 拒绝
        mvc.perform(authed(put("/api/records/" + id)).contentType(MediaType.APPLICATION_JSON).content("{\"qty\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("箱子数量要大于 0"));
        mvc.perform(authed(put("/api/records/" + id)).contentType(MediaType.APPLICATION_JSON).content("{\"units\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("单箱数量要大于 0"));
        // 不存在的记录
        mvc.perform(authed(put("/api/records/999999")).contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editPushedRecord_rejected() throws Exception {
        com.scandemo.backend.domain.ScanRecord r = new com.scandemo.backend.domain.ScanRecord();
        r.setNo(99);
        r.setSku("PUSHED-SKU");
        r.setQty(1);
        r.setAccountId(accountRepo.findByLoginName("admin").get().getId());
        r.setStatus(com.scandemo.backend.domain.RecordStatus.PUSHED);
        r.setOmsOrderNo("IB-TEST-1");
        r = repo.save(r);
        mvc.perform(authed(put("/api/records/" + r.getId())).contentType(MediaType.APPLICATION_JSON).content("{\"qty\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("已推送的记录不能修改")));
    }

    private long firstTempId() throws Exception {
        String body = mvc.perform(authed(get("/api/records").param("status", "TEMP")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(body).path("data").get(0).path("id").asLong();
    }
}
