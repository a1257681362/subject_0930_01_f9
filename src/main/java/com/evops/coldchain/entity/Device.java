package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 车载温控设备（V9）。
 * 登记即备案其 X.509 证书（PEM 原文、SHA-256 指纹、有效期等），作为温度报文验签的信任锚。
 * ACTIVE 在册 / REVOKED 作废：作废只追加原因与时间，不删行，历史报文仍可用备案证书验证。
 */
@TableName("t_cc_device")
public class Device extends BaseEntity {
    private String deviceNo;
    private String deviceSerial;
    private String deviceModel;
    private String firmwareVersion;
    private String certSubject;
    private String certIssuer;
    private String certSerial;
    private String certFingerprintSha256;
    private LocalDateTime certNotBefore;
    private LocalDateTime certNotAfter;
    private String certContent;
    private Long vehicleId;
    private LocalDateTime bindTime;
    private String status;
    private String revokeReason;
    private LocalDateTime revokeTime;
    private String remark;

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    public String getCertSubject() { return certSubject; }
    public void setCertSubject(String certSubject) { this.certSubject = certSubject; }
    public String getCertIssuer() { return certIssuer; }
    public void setCertIssuer(String certIssuer) { this.certIssuer = certIssuer; }
    public String getCertSerial() { return certSerial; }
    public void setCertSerial(String certSerial) { this.certSerial = certSerial; }
    public String getCertFingerprintSha256() { return certFingerprintSha256; }
    public void setCertFingerprintSha256(String certFingerprintSha256) { this.certFingerprintSha256 = certFingerprintSha256; }
    public LocalDateTime getCertNotBefore() { return certNotBefore; }
    public void setCertNotBefore(LocalDateTime certNotBefore) { this.certNotBefore = certNotBefore; }
    public LocalDateTime getCertNotAfter() { return certNotAfter; }
    public void setCertNotAfter(LocalDateTime certNotAfter) { this.certNotAfter = certNotAfter; }
    public String getCertContent() { return certContent; }
    public void setCertContent(String certContent) { this.certContent = certContent; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public LocalDateTime getBindTime() { return bindTime; }
    public void setBindTime(LocalDateTime bindTime) { this.bindTime = bindTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public LocalDateTime getRevokeTime() { return revokeTime; }
    public void setRevokeTime(LocalDateTime revokeTime) { this.revokeTime = revokeTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
