package com.scandemo.backend.domain;

/** 记录状态：TEMP=后台暂存待推送；PUSHED=已成功推送到 OMS */
public enum RecordStatus {
    TEMP, PUSHED
}
