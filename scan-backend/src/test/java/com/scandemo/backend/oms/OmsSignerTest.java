package com.scandemo.backend.oms;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmsSignerTest {

    /**
     * 官方文档的请求示例（原文）：
     * {"appKey":"YOUR_API_KEY","data":{"page":1,"pageSize":10,"outboundOrderNos":"OB..."},"reqTime":"1653640516"}
     *
     * 注意：文档给出的“拼接结果”样例把 page 排在了 outboundOrderNos 前面，与文字规则
     * （按字典顺序不区分大小写升序）矛盾。本实现按文字规则：outboundOrderNos < page < pageSize。
     * 上线前用 /api/inbound/push?dryRun=true 的 signString 到 OMS 官方验签工具比对；
     * 若官方以样例为准，只需调整 OmsSigner.canonicalDataJson 的排序策略。
     */
    @Test
    void signString_docExample_usesLexicographicOrderPerDocText() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", 1);
        data.put("pageSize", 10);
        data.put("outboundOrderNos", "OB069220519008,OB069220519007,OB069220519006");

        String dataJson = OmsSigner.canonicalDataJson(data);
        assertEquals(
                "{\"outboundOrderNos\":\"OB069220519008,OB069220519007,OB069220519006\",\"page\":1,\"pageSize\":10}",
                dataJson);

        assertEquals(
                "YOUR_API_KEY{\"outboundOrderNos\":\"OB069220519008,OB069220519007,OB069220519006\",\"page\":1,\"pageSize\":10}1653640516",
                OmsSigner.signString("YOUR_API_KEY", dataJson, "1653640516"));
    }

    /** 嵌套结构（boxTypeList/productList）也要递归排序、紧凑无空格；数字保持原样 */
    @Test
    void canonicalDataJson_sortsNestedAndKeepsNumbersPlain() {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("sku", "W063-GQ118lanL");
        product.put("quantity", 1);
        List<Object> productList = new ArrayList<>();
        productList.add(product);

        Map<String, Object> box = new LinkedHashMap<>();
        box.put("weight", new BigDecimal("18"));
        box.put("length", new BigDecimal("52"));
        box.put("width", new BigDecimal("41"));
        box.put("height", new BigDecimal("50"));
        box.put("ctns", 12);
        box.put("productList", productList);
        List<Object> boxTypeList = new ArrayList<>();
        boxTypeList.add(box);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("whCode", "WH1");
        data.put("boxTypeList", boxTypeList);

        assertEquals(
                "{\"boxTypeList\":[{\"ctns\":12,\"height\":50,\"length\":52,"
                        + "\"productList\":[{\"quantity\":1,\"sku\":\"W063-GQ118lanL\"}],"
                        + "\"weight\":18,\"width\":41}],\"whCode\":\"WH1\"}",
                OmsSigner.canonicalDataJson(data));
    }

    /** HmacSHA256 标准测试向量（RFC 风格）：key=key, 文本=The quick brown fox... */
    @Test
    void hmacSha256Hex_matchesKnownVector() {
        String hex = OmsSigner.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog");
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", hex);
        assertEquals(64, hex.length());
        assertTrue(hex.matches("[0-9a-f]{64}"));
    }

    /** body 里的 data 子串必须与参与签名的 dataJson 逐字节一致 */
    @Test
    void body_embedsExactDataJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("b", 2);
        data.put("a", "x\"y");
        String dataJson = OmsSigner.canonicalDataJson(data);
        String body = OmsSigner.body("AK", dataJson, "1653640516");
        assertEquals("{\"appKey\":\"AK\",\"data\":" + dataJson + ",\"reqTime\":\"1653640516\"}", body);
        assertTrue(body.contains(dataJson));
    }
}
