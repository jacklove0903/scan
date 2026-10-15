package com.scandemo.backend.web.dto;

import java.math.BigDecimal;

/** 采集页 scanRecs 里的一条记录（字段名与前端一致：no/sku/len/wid/hei/wgt/qty/ts） */
public class RecordDto {

    private Integer no;
    private String sku;
    private BigDecimal len;
    private BigDecimal wid;
    private BigDecimal hei;
    private BigDecimal wgt;
    private Integer qty;
    /** 单箱数量（每箱内装多少个小箱/件） */
    private Integer units;
    /** 采集时间（毫秒），仅作参考 */
    private Long ts;

    public Integer getNo() {
        return no;
    }

    public void setNo(Integer no) {
        this.no = no;
    }

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

    public Long getTs() {
        return ts;
    }

    public void setTs(Long ts) {
        this.ts = ts;
    }
}
