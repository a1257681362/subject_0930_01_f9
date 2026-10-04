package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 设备签名证书（V9）：每台车载设备登记签名证书用于数据批次签名验证。
 * 证书只追加不删除；作废置 REVOKED + 原因/时间。
 */
@TableName("t_cc_device_cert")
public class DeviceCert extends BaseEntity {
    /** 设备序列号（= 探头序列号），库内唯一 */
    private String deviceSerial;
    /** 证书指纹（SHA-256 hex，64位） */
    private String certFingerprint;
    /** 证书内容（签名验证密钥，HMAC-SHA256 shared secret） */
    private String certContent;
    /** ACTIVE / REVOKED */
    private String status;
    /** 证书有效期 */
    private LocalDateTime validUntil;
    private String revokeReason;
    private LocalDateTime revokeTime;

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getCertFingerprint() { return certFingerprint; }
    public void setCertFingerprint(String certFingerprint) { this.certFingerprint = certFingerprint; }
    public String getCertContent() { return certContent; }
    public void setCertContent(String certContent) { this.certContent = certContent; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public LocalDateTime getRevokeTime() { return revokeTime; }
    public void setRevokeTime(LocalDateTime revokeTime) { this.revokeTime = revokeTime; }
}
