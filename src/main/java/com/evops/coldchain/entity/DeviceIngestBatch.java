package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 设备数据批次登记（V9）：每批设备温度数据的接入登记。
 * requestId 幂等键唯一约束兜底——补传/补录/重试只能形成一条可用结果。
 */
@TableName("t_cc_device_ingest_batch")
public class DeviceIngestBatch extends BaseEntity {
    /** 客户端幂等键（补传/补录/重试共用） */
    private String requestId;
    /** 设备序列号 */
    private String deviceSerial;
    /** 关联运输批次 */
    private Long batchId;
    /** 设备证书指纹 */
    private String certFingerprint;
    /** 数据签名（HMAC-SHA256 hex） */
    private String signature;
    /** 数据内容摘要（同 requestId 换参数拒绝） */
    private String payloadHash;
    /** 数据点总数 */
    private Integer pointCount;
    /** 入账数（去重后新点） */
    private Integer acceptedCount;
    /** 重复数（已入账跳过） */
    private Integer duplicateCount;
    /** 补传保护数（已审核窗口内，alert_exempt=TRUE） */
    private Integer suppressedCount;
    /** POSTED 已入账 / QUARANTINED 已隔离 */
    private String status;
    /** 失败原因（可读） */
    private String failReason;
    /** 入账时间 */
    private LocalDateTime postedTime;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getCertFingerprint() { return certFingerprint; }
    public void setCertFingerprint(String certFingerprint) { this.certFingerprint = certFingerprint; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Integer getPointCount() { return pointCount; }
    public void setPointCount(Integer pointCount) { this.pointCount = pointCount; }
    public Integer getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(Integer acceptedCount) { this.acceptedCount = acceptedCount; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer duplicateCount) { this.duplicateCount = duplicateCount; }
    public Integer getSuppressedCount() { return suppressedCount; }
    public void setSuppressedCount(Integer suppressedCount) { this.suppressedCount = suppressedCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getPostedTime() { return postedTime; }
    public void setPostedTime(LocalDateTime postedTime) { this.postedTime = postedTime; }
}
