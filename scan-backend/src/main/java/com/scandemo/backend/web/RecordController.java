package com.scandemo.backend.web;

import com.scandemo.backend.domain.RecordStatus;
import com.scandemo.backend.domain.ScanRecord;
import com.scandemo.backend.service.RecordService;
import com.scandemo.backend.web.dto.ApiResponse;
import com.scandemo.backend.web.dto.BatchUploadRequest;
import com.scandemo.backend.web.dto.RecordUpdateRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临时记录接口。页面「统一上传」把 OMS_URL 指向 POST /api/records/batch 即可，
 * 请求体与页面现有格式一致（{count, records:[...]}）。
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService service;

    public RecordController(RecordService service) {
        this.service = service;
    }

    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> upload(@Valid @RequestBody BatchUploadRequest req) {
        List<ScanRecord> saved = service.saveBatch(req);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNo", saved.isEmpty() ? null : saved.get(0).getBatchNo());
        data.put("saved", saved.size());
        data.put("tempTotal", service.count(RecordStatus.TEMP));
        return ApiResponse.ok(data);
    }

    @GetMapping
    public ApiResponse<List<ScanRecord>> list(@RequestParam(defaultValue = "TEMP") String status) {
        return ApiResponse.ok(service.list(parseStatus(status)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** 编辑草稿（整单替换；草稿可暂时不完整，推送时再校验） */
    @PutMapping("/{id}")
    public ApiResponse<ScanRecord> update(@PathVariable Long id, @RequestBody RecordUpdateRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    /** 清空某状态（默认 TEMP） */
    @DeleteMapping
    public ApiResponse<Map<String, Object>> clear(@RequestParam(defaultValue = "TEMP") String status) {
        int n = service.clear(parseStatus(status));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", n);
        return ApiResponse.ok(data);
    }

    private static RecordStatus parseStatus(String status) {
        if (status == null || "ALL".equalsIgnoreCase(status) || "all".equals(status)) {
            return null;
        }
        try {
            return RecordStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 只支持 TEMP / PUSHED / ALL");
        }
    }
}
