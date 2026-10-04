package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车载温度探头主档：一支探头一行，出厂序列号库内唯一。
 * 建档即登记序列号、量程、校准机构与当前校准有效期（当前凭证见 t_cc_probe_cert）。
 * 探头状态 ACTIVE / REVOKED；校准历史只追加，不随证书轮换删行。
 */
@TableName("t_cc_temp_probe")
public class TempProbe extends BaseEntity {
    private String probeNo;
    private String serialNo;
    private String model;
    private BigDecimal rangeMin;
    private BigDecimal rangeMax;
    private String calibrateOrg;
    /** 当前校准有效期至（随当前有效凭证更新；发运前以此判定“仍在有效期内”） */
    private LocalDateTime validUntil;
    /** 当前有效凭证ID */
    private Long currentCertId;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getProbeNo() { return probeNo; }
    public void setProbeNo(String probeNo) { this.probeNo = probeNo; }
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
    public Long getCurrentCertId() { return currentCertId; }
    public void setCurrentCertId(Long currentCertId) { this.currentCertId = currentCertId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
