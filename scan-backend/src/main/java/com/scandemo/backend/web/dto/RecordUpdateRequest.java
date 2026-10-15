package com.scandemo.backend.web.dto;

import java.math.BigDecimal;

/**
 * 编辑草稿记录：整单替换语义——请求里给什么值就设什么值，null 表示清空该字段
 * （草稿允许暂时不完整，推送时才会校验齐全）。
 */
public class RecordUpdateRequest {

    private String sku;
    private BigDecimal len;
    private BigDecimal wid;
    private BigDecimal hei;
    private BigDecimal wgt;
    private Integer qty;
    /** 单箱数量（每箱内装多少） */
    private Integer units;

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public BigDecimal getLen() {
        return len;
    }

    public void setLen(BigDecimal len) {
        this.len = len;
    }

    public BigDecimal getWid() {
        return wid;
    }

    public void setWid(BigDecimal wid) {
        this.wid = wid;
    }

    public BigDecimal getHei() {
        return hei;
    }

    public void setHei(BigDecimal hei) {
        this.hei = hei;
    }

    public BigDecimal getWgt() {
        return wgt;
    }

    public void setWgt(BigDecimal wgt) {
        this.wgt = wgt;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public Integer getUnits() {
        return units;
    }

    public void setUnits(Integer units) {
        this.units = units;
    }
}
