package com.scandemo.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 里 oms.* 的绑定 */
@ConfigurationProperties(prefix = "oms")
public class OmsProperties {

    private String baseUrl = "https://api.xlwms.com";
    private String appKey = "";
    private String appSecret = "";
    /** 目标仓库代码 */
    private String whCode = "";
    /** 1-一件代发入库 2-备货中转入库 */
    private int inboundType = 1;
    /** 到仓方式：1 快递包裹 2 箱 3 托盘/卡板 4-9 货柜 10 其他 11 平台自提 12 三方头程 13 自发头程 */
    private int arrivalMode = 2;
    /** 0 不自动提交 1 自动提交（默认 1） */
    private int isSubmit = 1;
    /** 1 自增 2 相同箱型合并（默认 2） */
    private int boxMarkIncrementType = 2;
    /** 每箱产品数量（OMS productList[].quantity），待与业务确认 */
    private int unitsPerBox = 1;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;

    public boolean canSign() {
        return notBlank(appKey) && notBlank(appSecret);
    }

    public boolean canPush() {
        return canSign() && notBlank(whCode);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getWhCode() {
        return whCode;
    }

    public void setWhCode(String whCode) {
        this.whCode = whCode;
    }

    public int getInboundType() {
        return inboundType;
    }

    public void setInboundType(int inboundType) {
        this.inboundType = inboundType;
    }

    public int getArrivalMode() {
        return arrivalMode;
    }

    public void setArrivalMode(int arrivalMode) {
        this.arrivalMode = arrivalMode;
    }

    public int getIsSubmit() {
        return isSubmit;
    }

    public void setIsSubmit(int isSubmit) {
        this.isSubmit = isSubmit;
    }

    public int getBoxMarkIncrementType() {
        return boxMarkIncrementType;
    }

    public void setBoxMarkIncrementType(int boxMarkIncrementType) {
        this.boxMarkIncrementType = boxMarkIncrementType;
    }

    public int getUnitsPerBox() {
        return unitsPerBox;
    }

    public void setUnitsPerBox(int unitsPerBox) {
        this.unitsPerBox = unitsPerBox;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
