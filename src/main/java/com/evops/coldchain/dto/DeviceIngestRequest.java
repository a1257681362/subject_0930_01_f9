package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备温度批量上报请求（V9）。
 * 每批数据携带 requestId（幂等键）、设备序列号、证书指纹、签名和数据点数组。
 */
public class DeviceIngestRequest {
    /** 客户端幂等键（补传/补录/重试共用，只能形成一条可用结果） */
    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    /** 设备序列号 */
    @NotBlank(message = "设备序列号不能为空")
    private String deviceSerial;

    /** 设备证书指纹（SHA-256 hex） */
    @NotBlank(message = "证书指纹不能为空")
    private String certFingerprint;

    /** 数据签名（HMAC-SHA256 hex） */
    @NotBlank(message = "数据签名不能为空")
    private String signature;

    /** 关联运输批次 */
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;

    /** 数据点数组 */
    @NotEmpty(message = "数据点数组不能为空")
    @Valid
    private List<TempPoint> points;

    /** 单个温度数据点 */
    public static class TempPoint {
        @NotNull(message = "采样时间不能为空")
        private LocalDateTime recordTime;
        @NotNull(message = "温度值不能为空")
        private BigDecimal temperature;
        private BigDecimal mileageKm;
        private String remark;

        public LocalDateTime getRecordTime() { return recordTime; }
        public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }
        public BigDecimal getTemperature() { return temperature; }
        public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
        public BigDecimal getMileageKm() { return mileageKm; }
        public void setMileageKm(BigDecimal mileageKm) { this.mileageKm = mileageKm; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getCertFingerprint() { return certFingerprint; }
    public void setCertFingerprint(String certFingerprint) { this.certFingerprint = certFingerprint; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public List<TempPoint> getPoints() { return points; }
    public void setPoints(List<TempPoint> points) { this.points = points; }
}
