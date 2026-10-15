package com.scandemo.backend.web.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 采集页「统一上传」的请求体（与页面 POST 的 JSON 完全一致）：
 * {"count":2,"records":[{"no":1,"sku":"...","len":"52",...,"ts":169...}]}
 */
public class BatchUploadRequest {

    /** 页面传的条数，仅参考，以 records 实际内容为准 */
    private Integer count;

    @NotEmpty(message = "records 不能为空")
    @Valid
    private List<RecordDto> records;

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public List<RecordDto> getRecords() {
        return records;
    }

    public void setRecords(List<RecordDto> records) {
        this.records = records;
    }
}
