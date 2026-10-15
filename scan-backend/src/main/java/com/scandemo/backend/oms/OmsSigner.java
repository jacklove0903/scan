package com.scandemo.backend.oms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OMS OpenAPI 加签工具。
 *
 * 依据官方「开发验签工具」文档：
 * 1. data 内的业务字段按“字典顺序（不区分字母大小写）”升序排列；
 * 2. appKey、data（序列化后的 JSON 串）、reqTime 三者再按同样的字典序排列后直接拼接（无分隔符）;
 * 3. 以 appSecret 为密钥做 HmacSHA256，结果转 16 进制字符串即 authcode；
 * 4. authcode 通过 URL 的 GET 参数传递，其余参数走 POST。
 *
 * 注意：文档给出的拼接样例（page 排在 outboundOrderNos 前）与上述文字规则不一致，
 * 本实现严格按文字规则（字典序）输出；上线前请用 /api/inbound/push?dryRun=true
 * 拿 signString 到 OMS 后台「开发验签工具」里比对确认。若比对不符，只需调整本类的
 * {@link #canonicalDataJson} 排序策略（这里是唯一的规则落点）。
 */
public final class OmsSigner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private OmsSigner() {
    }

    /** data 的规范化 JSON：嵌套对象/数组里的 Map 同样按键字典序（不区分大小写）递归排序，紧凑无空格 */
    public static String canonicalDataJson(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(sortDeep(data));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("data 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 签名明文 = appKey + dataJson + reqTime（三者本身也满足字典序 appKey < data < reqTime，故顺序拼接即排序结果） */
    public static String signString(String appKey, String dataJson, String reqTime) {
        return appKey + dataJson + reqTime;
    }

    /** 完整签名：返回 signString 与 authcode */
    public static Signed sign(String appKey, String appSecret, Map<String, Object> data, String reqTime) {
        String dataJson = canonicalDataJson(data);
        String text = signString(appKey, dataJson, reqTime);
        return new Signed(dataJson, text, hmacSha256Hex(appSecret, text));
    }

    /** POST body：保证其中 data 子串与参与签名的 dataJson 逐字节一致 */
    public static String body(String appKey, String dataJson, String reqTime) {
        try {
            return "{\"appKey\":" + MAPPER.writeValueAsString(appKey)
                    + ",\"data\":" + dataJson
                    + ",\"reqTime\":" + MAPPER.writeValueAsString(reqTime) + "}";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("body 组装失败: " + e.getMessage(), e);
        }
    }

    public static String hmacSha256Hex(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 计算失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sortDeep(Object value) {
        if (value instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) value;
            Map<String, Object> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, Object> e : src.entrySet()) {
                sorted.put(e.getKey(), sortDeep(e.getValue()));
            }
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                out.add(sortDeep(item));
            }
            return out;
        }
        return value;
    }

    /** 签名结果：给 dryRun 输出用 */
    public static final class Signed {
        public final String dataJson;
        public final String signString;
        public final String authcode;

        Signed(String dataJson, String signString, String authcode) {
            this.dataJson = dataJson;
            this.signString = signString;
            this.authcode = authcode;
        }
    }
}
