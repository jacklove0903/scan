package com.scandemo.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scandemo.backend.auth.AccountService;
import com.scandemo.backend.auth.CurrentAccount;
import com.scandemo.backend.config.OmsProperties;
import com.scandemo.backend.domain.Account;
import com.scandemo.backend.domain.RecordStatus;
import com.scandemo.backend.domain.ScanRecord;
import com.scandemo.backend.oms.OmsClient;
import com.scandemo.backend.oms.OmsSigner;
import com.scandemo.backend.repo.ScanRecordRepository;
import com.scandemo.backend.web.dto.BatchUploadRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把当前账号的 TEMP 记录组装成 OMS「创建入库单」请求并推送。
 * OMS 凭证（appKey/appSecret/whCode）来自当前登录账号——每个账号推自己 OMS 的单。
 *
 * 组装规则：
 * - 带 boxGroup 的记录（同箱多 SKU）：同一 boxGroup 合成一个箱型，箱级尺寸/重量/箱子数量取组内第一条，
 *   组内每条记录的 SKU+单箱数量作为 productList 的一个元素；
 * - 不带 boxGroup 的记录：每条记录各自成一条箱型（不合并），productList 一条，箱子数量取本条 qty；
 * - thirdOrderNo 按推送时刻生成（SCAN-yyyyMMddHHmmss）。
 */
@Service
public class InboundService {

    private static final Logger log = LoggerFactory.getLogger(InboundService.class);

    private final ScanRecordRepository repo;
    private final OmsProperties props;
    private final OmsClient omsClient;
    private final AccountService accountService;
    private final RecordService recordService;

    public InboundService(ScanRecordRepository repo, OmsProperties props, OmsClient omsClient,
                          AccountService accountService, RecordService recordService) {
        this.repo = repo;
        this.props = props;
        this.omsClient = omsClient;
        this.accountService = accountService;
        this.recordService = recordService;
    }

    /**
     * 手机页「上传并推OMS」：本批记录落库 + 组装 + 推送一步完成。
     * 任何一步失败（校验不过/OMS 拒绝）整个事务回滚：后台不留记录，手机本地记录还在，改完重传即可。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadAndPush(BatchUploadRequest req) throws IOException {
        Account acc = accountService.require(CurrentAccount.get());
        List<ScanRecord> batch = recordService.saveBatch(req);
        return pushRecords(batch, acc, false);
    }

    /**
     * @param dryRun true=只组装并返回签名素材（不发送、不改状态），用于和 OMS 官方验签工具比对
     */
    @Transactional
    public Map<String, Object> push(boolean dryRun) throws IOException {
        Long accId = CurrentAccount.get();
        Account acc = accountService.require(accId);
        List<ScanRecord> temps = repo.findByAccountIdAndStatusOrderByNoAscIdAsc(accId, RecordStatus.TEMP);
        if (temps.isEmpty()) {
            throw new IllegalStateException("没有待推送的临时记录");
        }
        return pushRecords(temps, acc, dryRun);
    }

    /** 把一批记录组装成创建入库单请求并推送（push 与 uploadAndPush 共用） */
    private Map<String, Object> pushRecords(List<ScanRecord> temps, Account acc, boolean dryRun) throws IOException {
        List<ScanRecord> incomplete = new ArrayList<>();
        for (ScanRecord r : temps) {
            if (!r.isComplete()) {
                incomplete.add(r);
            }
        }
        if (!incomplete.isEmpty()) {
            StringBuilder sb = new StringBuilder("以下记录缺 SKU/尺寸/重量/箱子数量/单箱数量，不能推送：");
            for (ScanRecord r : incomplete) {
                sb.append(" #").append(r.getNo() == null ? r.getId() : r.getNo());
            }
            throw new IllegalStateException(sb.toString());
        }

        String thirdOrderNo = "SCAN-" + LocalDateTime.now().toString().replaceAll("[-:T]", "").substring(0, 14);
        Map<String, Object> data = buildData(temps, thirdOrderNo, acc);
        String dataJson = OmsSigner.canonicalDataJson(data);
        String reqTime = omsClient.nowReqTime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("thirdOrderNo", thirdOrderNo);
        result.put("recordCount", temps.size());
        result.put("boxTypeCount", ((List<?>) data.get("boxTypeList")).size());
        result.put("dataJson", dataJson);
        result.put("reqTime", reqTime);
        result.put("signString", OmsSigner.signString(acc.getAppKey(), dataJson, reqTime));
        if (notBlank(acc.getAppKey()) && notBlank(acc.getAppSecret())) {
            result.put("authcode", OmsSigner.hmacSha256Hex(acc.getAppSecret(),
                    OmsSigner.signString(acc.getAppKey(), dataJson, reqTime)));
        }

        if (dryRun) {
            result.put("hint", "把 signString 贴到 OMS 后台「开发验签工具」比对 authcode；" +
                    "确认无误后再用 dryRun=false 正式推送");
            return result;
        }

        if (!acc.canPush()) {
            throw new IllegalStateException("当前账号（" + acc.getLoginName()
                    + "）的 OMS 配置不完整：请在账号维护页里填好 appKey / appSecret / 仓库代码");
        }

        String signText = OmsSigner.signString(acc.getAppKey(), dataJson, reqTime);
        String authcode = OmsSigner.hmacSha256Hex(acc.getAppSecret(), signText);
        JsonNode resp = omsClient.createInboundOrder(authcode, dataJson, reqTime, acc.getAppKey());
        result.put("omsResponse", resp);

        boolean ok = resp.path("code").asInt(-1) == 200;
        List<JsonNode> orders = new ArrayList<>();
        if (resp.path("data").isArray()) {
            resp.path("data").forEach(orders::add);
        }
        boolean allSuccess = ok && !orders.isEmpty();
        String orderNo = null;
        StringBuilder failMsg = new StringBuilder();
        for (JsonNode o : orders) {
            if (!o.path("success").asBoolean(false)) {
                allSuccess = false;
                failMsg.append(o.path("msg").asText("")).append("; ");
            } else if (orderNo == null) {
                orderNo = o.path("orderNo").asText(null);
            }
        }
        if (!allSuccess) {
            throw new IllegalStateException("OMS 返回失败：code=" + resp.path("code").asInt()
                    + " msg=" + resp.path("msg").asText("") + " " + failMsg);
        }

        LocalDateTime now = LocalDateTime.now();
        for (ScanRecord r : temps) {
            r.setStatus(RecordStatus.PUSHED);
            r.setOmsOrderNo(orderNo);
            r.setPushedAt(now);
        }
        repo.saveAll(temps);
        result.put("omsOrderNo", orderNo);
        result.put("pushedCount", temps.size());
        log.info("账号[{}]推送成功：{} 条记录 -> OMS 单号 {}", acc.getLoginName(), temps.size(), orderNo);
        return result;
    }

    /**
     * 回查当前账号已推送单据在 OMS 里的真实状态（草稿/待入库/已取消…），写回记录。
     * 这样本地就不会和 OMS 对不上（比如单子在 OMS 被取消了，这边能看到）。
     */
    @Transactional
    public Map<String, Object> refreshOmsStatus() throws IOException {
        Long accId = CurrentAccount.get();
        Account acc = accountService.require(accId);
        if (!notBlank(acc.getAppKey()) || !notBlank(acc.getAppSecret())) {
            throw new IllegalStateException("当前账号缺 appKey/appSecret，无法回查 OMS 状态");
        }
        List<ScanRecord> pushed = repo.findByAccountIdAndStatusOrderByNoAscIdAsc(accId, RecordStatus.PUSHED);
        List<String> nos = new ArrayList<>();
        for (ScanRecord r : pushed) {
            String no = r.getOmsOrderNo();
            if (notBlank(no) && !nos.contains(no)) {
                nos.add(no);
            }
        }
        if (nos.isEmpty()) {
            throw new IllegalStateException("还没有带 OMS 单号的已推送记录");
        }
        Map<String, Integer> statusMap = omsClient.queryInboundStatuses(nos, acc.getAppKey(), acc.getAppSecret());
        LocalDateTime now = LocalDateTime.now();
        int updated = 0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (ScanRecord r : pushed) {
            Integer st = statusMap.get(r.getOmsOrderNo());
            if (st != null) {
                r.setOmsStatus(st);
                r.setOmsStatusAt(now);
                updated++;
                byStatus.merge(String.valueOf(st), 1, Integer::sum);
            }
        }
        repo.saveAll(pushed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderCount", nos.size());
        out.put("updated", updated);
        out.put("byStatus", byStatus);
        out.put("statusText", byStatus.keySet().stream().map(k -> statusName(Integer.parseInt(k))).collect(java.util.stream.Collectors.joining("、")));
        log.info("账号[{}]回查 OMS 状态：{} 个单号，更新 {} 条", acc.getLoginName(), nos.size(), updated);
        return out;
    }

    /** OMS 入库单状态文案 */
    public static String statusName(int s) {
        switch (s) {
            case 0: return "新建/草稿";
            case 1: return "待入库";
            case 2: return "收货中";
            case 3: return "已收货";
            case 4: return "已上架";
            case 5: return "已取消";
            case 6: return "待审核";
            case 7: return "驳回";
            default: return "未知(" + s + ")";
        }
    }

    /** 组装 data（字段依据「创建入库单」接口文档；whCode 用当前账号的仓库） */
    @SuppressWarnings("unchecked")
    Map<String, Object> buildData(List<ScanRecord> temps, String thirdOrderNo, Account acc) {
        // 按箱归组：
        //  - 有 boxGroup：同一组 = 一个箱型（同箱多 SKU），箱级尺寸/重量/箱子数量取组内第一条；
        //  - 无 boxGroup：每条记录独立成一条箱型（不合并，箱子数量取本条 qty）。
        Map<String, Map<String, Object>> boxes = new LinkedHashMap<>();
        for (ScanRecord r : temps) {
            String bg = r.getBoxGroup();
            boolean grouped = bg != null && !bg.trim().isEmpty();
            String key = grouped
                    ? "BG:" + bg.trim()
                    // 不合并：每条记录各自成一条箱型（key 按记录唯一）
                    : "REC:" + (r.getId() != null ? r.getId() : System.identityHashCode(r));

            Map<String, Object> box = boxes.get(key);
            if (box == null) {
                box = new LinkedHashMap<>();
                // 组箱：箱子数量取第一条（后续同箱记录只贡献 SKU）；单条记录从 0 累加本条 qty
                box.put("ctns", grouped ? r.getQty() : 0);
                // 入库回读的 BigDecimal 带 .000 尾零（列精度 scale=3），这里规范化成最简形式
                box.put("length", normalized(r.getLength()));
                box.put("width", normalized(r.getWidth()));
                box.put("height", normalized(r.getHeight()));
                box.put("weight", normalized(r.getWeight()));
                box.put("productList", new ArrayList<Map<String, Object>>());
                boxes.put(key, box);
            }

            Map<String, Object> product = new LinkedHashMap<>();
            product.put("sku", r.getSku());
            // 单箱数量：优先用记录上的值（必填），老数据兜底用全局配置
            product.put("quantity", r.getUnitsPerBox() != null ? r.getUnitsPerBox() : props.getUnitsPerBox());
            List<Map<String, Object>> productList = (List<Map<String, Object>>) box.get("productList");

            if (grouped) {
                // 同箱多 SKU：每条记录都是 productList 的一个元素
                productList.add(product);
            } else {
                // 不合并：本条记录独立成箱，只有一个 product，箱子数量 = 本条 qty
                if (productList.isEmpty()) {
                    productList.add(product);
                }
                box.put("ctns", ((Integer) box.get("ctns")) + r.getQty());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("whCode", acc.getWhCode());
        data.put("inboundType", props.getInboundType());
        data.put("thirdOrderNo", thirdOrderNo);
        data.put("arrivalMode", props.getArrivalMode());
        data.put("isSubmit", props.getIsSubmit());
        data.put("forecastType", 1);
        data.put("boxMarkIncrementType", props.getBoxMarkIncrementType());
        data.put("boxTypeList", new ArrayList<>(boxes.values()));
        return data;
    }

    /** 52.000 -> 52，12.500 -> 12.5；用 toPlainString 避免 stripTrailingZeros 产生科学计数法（5.2E+1） */
    private static BigDecimal normalized(BigDecimal v) {
        return v == null ? null : new BigDecimal(v.stripTrailingZeros().toPlainString());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
