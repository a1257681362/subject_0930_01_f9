package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 已验签设备温度报文批次结果（V9）。
 * 按 client_batch_no 幂等：同一批重发直接回第一次结果（inserted/duplicate 计数不变），
 * 换载荷（payload_sha256 不同）拒绝。只追加，不提供修改/删除。
 */
@TableName("t_cc_device_ingest_batch")
public class DeviceIngestBatch extends CreateOnlyEntity {
    private String clientBatchNo;
    private String deviceSerial;
    private String payload;
    private String payloadSha256;
    private String certFingerprintSha256;
    private Integer pointCount;
    private Integer insertedCount;
    private Integer duplicateCount;
    private Integer unmatchedCount;
    private String affectedBatchIds;
    private String sourceType;
    private LocalDateTime receivedTime;

    public String getClientBatchNo() { return clientBatchNo; }
    public void setClientBatchNo(String clientBatchNo) { this.clientBatchNo = clientBatchNo; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String payloadSha256) { this.payloadSha256 = payloadSha256; }
    public String getCertFingerprintSha256() { return certFingerprintSha256; }
    public void setCertFingerprintSha256(String certFingerprintSha256) { this.certFingerprintSha256 = certFingerprintSha256; }
    public Integer getPointCount() { return pointCount; }
    public void setPointCount(Integer pointCount) { this.pointCount = pointCount; }
    public Integer getInsertedCount() { return insertedCount; }
    public void setInsertedCount(Integer insertedCount) { this.insertedCount = insertedCount; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer duplicateCount) { this.duplicateCount = duplicateCount; }
    public Integer getUnmatchedCount() { return unmatchedCount; }
    public void setUnmatchedCount(Integer unmatchedCount) { this.unmatchedCount = unmatchedCount; }
    public String getAffectedBatchIds() { return affectedBatchIds; }
    public void setAffectedBatchIds(String affectedBatchIds) { this.affectedBatchIds = affectedBatchIds; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public LocalDateTime getReceivedTime() { return receivedTime; }
    public void setReceivedTime(LocalDateTime receivedTime) { this.receivedTime = receivedTime; }
}
