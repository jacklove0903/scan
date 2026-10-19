package com.scandemo.backend.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条采集记录（扫描一个箱面 = 一条记录）。
 * 字段名与前端 localStorage 里的 scanRecs 结构一一对应。
 */
@Entity
@Table(name = "scan_record")
public class ScanRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 采集端自己的编号（#1、#2…），仅用于对照，不参与唯一性 */
    @Column(name = "seq_no")
    private Integer no;

    /** 归属账号：每个账号的数据互不相通 */
    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false, length = 100)
    private String sku;

    @Column(name = "len_cm", precision = 8, scale = 3)
    private BigDecimal length;

    @Column(name = "wid_cm", precision = 8, scale = 3)
    private BigDecimal width;

    @Column(name = "hei_cm", precision = 8, scale = 3)
    private BigDecimal height;

    /** 箱重 kg，OMS 创建入库单必填 */
    @Column(name = "wgt_kg", precision = 8, scale = 3)
    private BigDecimal weight;

    /** 箱数 */
    private Integer qty;

    /** 单箱数量：一个外箱里装多少个小箱/件（OMS productList[].quantity） */
    @Column(name = "units_per_box")
    private Integer unitsPerBox;

    /**
     * 箱分组号：同一箱（同箱多 SKU）的记录共享同一个值。
     * 组装入库单时，同一 boxGroup 的记录合并成一个箱型（boxTypeList 一条），
     * 箱级尺寸/重量/箱子数量取组内第一条，每条记录的 SKU+单箱数量进 productList。
     * 为空表示独立成箱（单 SKU 箱，沿用旧的逐条合并逻辑）。
     */
    @Column(name = "box_group", length = 40)
    private String boxGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecordStatus status = RecordStatus.TEMP;

    /** 上传批次号，一次页面“统一上传”= 一个批次 */
    @Column(name = "batch_no", length = 40)
    private String batchNo;

    /** OMS 返回的入库单号（推送成功后回填） */
    @Column(name = "oms_order_no", length = 64)
    private String omsOrderNo;

    /** OMS 侧该单的最新状态：0 新建/草稿 1 待入库 2 收货中 3 已收货 4 已上架 5 已取消 6 待审核 7 驳回 */
    @Column(name = "oms_status")
    private Integer omsStatus;

    @Column(name = "oms_status_at")
    private LocalDateTime omsStatusAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "pushed_at")
    private LocalDateTime pushedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** 是否 SKU + 三段尺寸 + 重量 + 箱数 + 单箱数量 齐全（推送 OMS 的最低要求） */
    public boolean isComplete() {
        return sku != null && !sku.trim().isEmpty()
                && length != null && width != null && height != null
                && weight != null && qty != null && qty > 0
                && unitsPerBox != null && unitsPerBox > 0;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getNo() {
        return no;
    }

    public void setNo(Integer no) {
        this.no = no;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public BigDecimal getLength() {
        return length;
    }

    public void setLength(BigDecimal length) {
        this.length = length;
    }

    public BigDecimal getWidth() {
        return width;
    }

    public void setWidth(BigDecimal width) {
        this.width = width;
    }

    public BigDecimal getHeight() {
        return height;
    }

    public void setHeight(BigDecimal height) {
        this.height = height;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public Integer getUnitsPerBox() {
        return unitsPerBox;
    }

    public void setUnitsPerBox(Integer unitsPerBox) {
        this.unitsPerBox = unitsPerBox;
    }

    public String getBoxGroup() {
        return boxGroup;
    }

    public void setBoxGroup(String boxGroup) {
        this.boxGroup = boxGroup;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public void setStatus(RecordStatus status) {
        this.status = status;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getOmsOrderNo() {
        return omsOrderNo;
    }

    public void setOmsOrderNo(String omsOrderNo) {
        this.omsOrderNo = omsOrderNo;
    }

    public Integer getOmsStatus() {
        return omsStatus;
    }

    public void setOmsStatus(Integer omsStatus) {
        this.omsStatus = omsStatus;
    }

    public LocalDateTime getOmsStatusAt() {
        return omsStatusAt;
    }

    public void setOmsStatusAt(LocalDateTime omsStatusAt) {
        this.omsStatusAt = omsStatusAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getPushedAt() {
        return pushedAt;
    }

    public void setPushedAt(LocalDateTime pushedAt) {
        this.pushedAt = pushedAt;
    }
}
