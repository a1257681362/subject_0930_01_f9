package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 设备证书登记请求（V9）。
 */
public class DeviceCertRegisterRequest {
    @NotBlank(message = "设备序列号不能为空")
    private String deviceSerial;

    @NotBlank(message = "证书指纹不能为空")
    private String certFingerprint;

    @NotBlank(message = "证书内容不能为空")
    private String certContent;

    @NotNull(message = "证书有效期不能为空")
    private LocalDateTime validUntil;

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getCertFingerprint() { return certFingerprint; }
    public void setCertFingerprint(String certFingerprint) { this.certFingerprint = certFingerprint; }
    public String getCertContent() { return certContent; }
    public void setCertContent(String certContent) { this.certContent = certContent; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
}
