package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 温度探头建档请求：登记序列号、量程、校准机构和有效期。
 * 可选同时上传首份校准凭证（证书字段齐全时建档即生成凭证与摘要）；
 * 不传证书也可建档，但绑定车辆/发运前必须先上传有效凭证。
 */
public class ProbeCreateRequest {
    @NotBlank(message = "探头序列号不能为空")
    private String serialNo;
    private String model;
    @NotNull(message = "量程下限不能为空")
    private BigDecimal rangeMin;
    @NotNull(message = "量程上限不能为空")
    private BigDecimal rangeMax;
    @NotBlank(message = "校准机构不能为空")
    private String calibrateOrg;
    @NotNull(message = "校准有效期不能为空")
    private LocalDateTime validUntil;
    private String remark;

    // —— 可选首份校准凭证 ——
    private String certNo;
    private LocalDateTime calibrateDate;
    private String certFileName;
    /** 证书文本内容（演示环境直接入库并据此生成 SHA-256 摘要） */
    private String certFileContent;

    public String getSerialNo() { return serialNo; }
    public void setSerialNo(String serialNo) { this.serialNo = serialNo; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public BigDecimal getRangeMin() { return rangeMin; }
    public void setRangeMin(BigDecimal rangeMin) { this.rangeMin = rangeMin; }
    public BigDecimal getRangeMax() { return rangeMax; }
    public void setRangeMax(BigDecimal rangeMax) { this.rangeMax = rangeMax; }
    public String getCalibrateOrg() { return calibrateOrg; }
    public void setCalibrateOrg(String calibrateOrg) { this.calibrateOrg = calibrateOrg; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCertNo() { return certNo; }
    public void setCertNo(String certNo) { this.certNo = certNo; }
    public LocalDateTime getCalibrateDate() { return calibrateDate; }
    public void setCalibrateDate(LocalDateTime calibrateDate) { this.calibrateDate = calibrateDate; }
    public String getCertFileName() { return certFileName; }
    public void setCertFileName(String certFileName) { this.certFileName = certFileName; }
    public String getCertFileContent() { return certFileContent; }
    public void setCertFileContent(String certFileContent) { this.certFileContent = certFileContent; }
}
