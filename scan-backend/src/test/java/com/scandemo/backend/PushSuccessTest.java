package com.scandemo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scandemo.backend.oms.OmsClient;
import com.scandemo.backend.repo.ScanRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 正式推送成功分支：用 mock 替掉 OMS 客户端，验证状态流转与单号回填 */
@SpringBootTest
@AutoConfigureMockMvc
class PushSuccessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScanRecordRepository repo;

    @MockBean
    private OmsClient omsClient;

    private String tok;

    @BeforeEach
    void setup() throws Exception {
        tok = login("admin", "itest-admin-seed-9");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/records").param("status", "ALL").header("X-Token", tok))
                .andExpect(status().isOk());
        Mockito.when(omsClient.nowReqTime()).thenReturn("1791595118");
        Mockito.when(omsClient.createInboundOrder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"code\":200,\"msg\":\"\",\"data\":[{\"success\":true,"
                        + "\"orderNo\":\"IB-MOCK-1\",\"thirdOrderNo\":\"SCAN-TEST\",\"msg\":\"\"}]}"));
    }

    private String login(String name, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + name + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }

    @Test
    void push_mockSuccess_marksPushedWithOrderNo() throws Exception {
        mvc.perform(post("/api/records/batch").header("X-Token", tok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"no\":1,\"sku\":\"PUSH-OK\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"2\",\"units\":\"18\"}]}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/inbound/push").header("X-Token", tok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.omsOrderNo").value("IB-MOCK-1"))
                .andExpect(jsonPath("$.data.pushedCount").value(1));

        // 状态流转：TEMP 清空，记录变 PUSHED 且带上 OMS 单号
        mvc.perform(get("/api/records").param("status", "TEMP").header("X-Token", tok))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/records").param("status", "PUSHED").header("X-Token", tok))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].omsOrderNo").value("IB-MOCK-1"));
        assertEquals(1, repo.count());

        // 推送请求确实带了该账号的 appKey（签名字符串以它开头）
        Mockito.verify(omsClient).createInboundOrder(anyString(), anyString(), anyString(), Mockito.eq("test-key"));

        // OMS 返回失败时不改状态，草稿保留
        Mockito.when(omsClient.createInboundOrder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"code\":200,\"msg\":\"\",\"data\":[{\"success\":false,"
                        + "\"orderNo\":null,\"msg\":\"箱子尺寸超限\"}]}"));
        mvc.perform(post("/api/records/batch").header("X-Token", tok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"no\":2,\"sku\":\"PUSH-FAIL\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"1\",\"units\":\"1\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inbound/push").header("X-Token", tok))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("箱子尺寸超限")));
        mvc.perform(get("/api/records").param("status", "TEMP").header("X-Token", tok))
                .andExpect(jsonPath("$.data.length()").value(1));

        // 清理
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/records").param("status", "ALL").header("X-Token", tok)).andExpect(status().isOk());
    }

    @Test
    void uploadAndPush_success_marksPushed_andFailure_rollsBackNothing() throws Exception {
        // 上传即推送：一次请求完成落库+推 OMS，成功后直接变 PUSHED，TEMP 不残留
        mvc.perform(post("/api/inbound/upload").header("X-Token", tok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"records\":[{\"no\":1,\"sku\":\"UP-OK\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"2\",\"units\":\"18\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.omsOrderNo").value("IB-MOCK-1"))
                .andExpect(jsonPath("$.data.pushedCount").value(1));
        mvc.perform(get("/api/records").param("status", "TEMP").header("X-Token", tok))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/records").param("status", "PUSHED").header("X-Token", tok))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sku").value("UP-OK"));

        // OMS 拒绝时：返回 400，且这批记录整体回滚——后台不攒 TEMP（手机本地自己保留重传）
        Mockito.when(omsClient.createInboundOrder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"code\":200,\"msg\":\"操作成功\",\"data\":[{\"success\":false,\"orderNo\":null,\"msg\":\"传入产品sku有误，失败SKU: [BAD-1]\"}]}"));
        mvc.perform(post("/api/inbound/upload").header("X-Token", tok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"records\":[{\"no\":1,\"sku\":\"BAD-1\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"1\",\"units\":\"1\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("传入产品sku有误")));
        mvc.perform(get("/api/records").param("status", "TEMP").header("X-Token", tok))
                .andExpect(jsonPath("$.data.length()").value(0));
        assertEquals(1, repo.count());

        // 清理
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/records").param("status", "ALL").header("X-Token", tok)).andExpect(status().isOk());
    }

    @Test
    void refreshStatus_writesOmsStatusBack() throws Exception {
        // 先推一单（mock 创建成功 -> IB-MOCK-1）
        mvc.perform(post("/api/records/batch").header("X-Token", tok).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"no\":1,\"sku\":\"REF-1\",\"len\":\"52\",\"wid\":\"41\",\"hei\":\"50\",\"wgt\":\"18\",\"qty\":\"1\",\"units\":\"1\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inbound/push").header("X-Token", tok)).andExpect(status().isOk());

        // 回查：mock OMS 返回该单状态 0（新建/草稿）
        Mockito.when(omsClient.queryInboundStatuses(anyList(), anyString(), anyString()))
                .thenReturn(Collections.singletonMap("IB-MOCK-1", 0));
        mvc.perform(post("/api/inbound/refresh-status").header("X-Token", tok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderCount").value(1))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.byStatus.0").value(1))
                .andExpect(jsonPath("$.data.statusText").value("新建/草稿"));

        // 状态写回记录
        mvc.perform(get("/api/records").param("status", "PUSHED").header("X-Token", tok))
                .andExpect(jsonPath("$.data[0].omsOrderNo").value("IB-MOCK-1"))
                .andExpect(jsonPath("$.data[0].omsStatus").value(0));

        // OMS 侧被取消（5）时，本地能看到
        Mockito.when(omsClient.queryInboundStatuses(anyList(), anyString(), anyString()))
                .thenReturn(Collections.singletonMap("IB-MOCK-1", 5));
        mvc.perform(post("/api/inbound/refresh-status").header("X-Token", tok)).andExpect(status().isOk());
        mvc.perform(get("/api/records").param("status", "PUSHED").header("X-Token", tok))
                .andExpect(jsonPath("$.data[0].omsStatus").value(5));

        // 清理
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/records").param("status", "ALL").header("X-Token", tok)).andExpect(status().isOk());
    }
}
