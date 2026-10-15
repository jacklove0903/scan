package com.scandemo.backend.web;

import com.scandemo.backend.service.InboundService;
import com.scandemo.backend.web.dto.ApiResponse;
import com.scandemo.backend.web.dto.BatchUploadRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.io.IOException;
import java.util.Map;

/** OMS 推送入口：/upload 上传即推送（手机页用）；/push 推后台已暂存记录，dryRun=true 只组装不发送 */
@RestController
@RequestMapping("/api/inbound")
public class InboundController {

    private final InboundService service;

    public InboundController(InboundService service) {
        this.service = service;
    }

    /** 上传并推送：本批记录直接推 OMS，失败回滚（后台不留记录），请求体与 /api/records/batch 一致 */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@Valid @RequestBody BatchUploadRequest req) throws IOException {
        return ApiResponse.ok(service.uploadAndPush(req));
    }

    @PostMapping("/push")
    public ApiResponse<Map<String, Object>> push(
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) throws IOException {
        return ApiResponse.ok(service.push(dryRun));
    }

    /** 回查已推送单据在 OMS 上的真实状态（草稿/待入库/已取消…）并写回本地记录 */
    @PostMapping("/refresh-status")
    public ApiResponse<Map<String, Object>> refreshStatus() throws IOException {
        return ApiResponse.ok(service.refreshOmsStatus());
    }
}
