package com.scandemo.backend.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scandemo.backend.config.OmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OMS OpenAPI 客户端（JDK 自带 HttpURLConnection，零额外依赖） */
@Component
public class OmsClient {

    private static final Logger log = LoggerFactory.getLogger(OmsClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OmsProperties props;

    public OmsClient(OmsProperties props) {
        this.props = props;
    }

    /** 当前 10 位 UNIX 秒时间戳 */
    public String nowReqTime() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    /**
     * 创建入库单：POST /openapi/v1/inboundOrder/create?authcode=xxx
     * 返回 OMS 的原始 JSON（{code,msg,data:[{success,orderNo,thirdOrderNo,msg}]}）。
     * appKey 来自当前登录账号（每个账号一套 key）。
     */
    public JsonNode createInboundOrder(String authcode, String dataJson, String reqTime, String appKey) throws IOException {
        String url = props.getBaseUrl() + "/openapi/v1/inboundOrder/create?authcode=" + authcode;
        String body = OmsSigner.body(appKey, dataJson, reqTime);
        log.info("推送 OMS 入库单: body 长度={}", body.length());
        return postJson(url, body);
    }

    /**
     * 回查入库单状态：POST /openapi/v1/inboundOrder/pageList
     * 返回 单号 -> status（0 新建/草稿 1 待入库 2 收货中 3 已收货 4 已上架 5 已取消 6 待审核 7 驳回）
     */
    public Map<String, Integer> queryInboundStatuses(List<String> orderNos, String appKey, String appSecret) throws IOException {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (orderNos == null || orderNos.isEmpty()) {
            return out;
        }
        for (int i = 0; i < orderNos.size(); i += 50) {
            List<String> chunk = orderNos.subList(i, Math.min(orderNos.size(), i + 50));
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("inboundOrderNoList", new ArrayList<>(chunk));
            data.put("page", 1);
            data.put("pageSize", chunk.size());
            // reqTime 只取一次：签名和请求体必须用同一个值，否则验签不通过
            String reqTime = nowReqTime();
            OmsSigner.Signed s = OmsSigner.sign(appKey, appSecret, data, reqTime);
            String url = props.getBaseUrl() + "/openapi/v1/inboundOrder/pageList?authcode=" + s.authcode;
            JsonNode resp = postJson(url, OmsSigner.body(appKey, s.dataJson, reqTime));
            // 注意：签名用的 reqTime 必须与发送的一致，这里统一取一次
            log.info("回查 OMS 入库单状态：请求 {} 个单号，返回 code={}", chunk.size(), resp.path("code").asInt());
            JsonNode records = resp.path("data").path("records");
            if (records.isArray()) {
                for (JsonNode r : records) {
                    String no = r.path("inboundOrderNo").asText(null);
                    if (no != null && r.hasNonNull("status")) {
                        out.put(no, r.path("status").asInt());
                    }
                }
            }
        }
        return out;
    }

    private JsonNode postJson(String url, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(props.getConnectTimeoutMs());
            conn.setReadTimeout(props.getReadTimeoutMs());
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (status >= 200 && status < 300) {
                return MAPPER.readTree(text);
            }
            // 500 时按文档提示：不能直接视为创建失败，记录原文交由上层决定是否重试
            throw new IOException("OMS HTTP " + status + "：" + abbreviate(text));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
