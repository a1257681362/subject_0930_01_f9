package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 验签失败隔离队列（V9）。
 * 整批温度报文原样留存：报文/随报证书/签名/失败原因码与可读说明。
 * QUARANTINED 未入账（运维可重放）/ RELEASED 重放成功入账（不可再重放）。
 * 全过程重放尝试另在 {@link DeviceReplayAudit} 逐次只追加。
 */
@TableName("t_cc_temp_batch_quarantine")
public class TempBatchQuarantine extends BaseEntity {
    private String queueNo;
    private String clientBatchNo;
    private String deviceSerial;
    private String certFingerprintSha256;
    private String payload;
    private String payloadSha256;
    private String signature;
    private String certPem;
    private Integer pointCount;
    private String status;
    private String reasonCode;
    private String reasonDetail;
    private LocalDateTime receivedTime;
    private LocalDateTime releasedTime;
    private Long releasedBy;
    private Long releasedIngestId;
    private Integer replayCount;

    public String getQueueNo() { return queueNo; }
    public void setQueueNo(String queueNo) { this.queueNo = queueNo; }
    public String getClientBatchNo() { return clientBatchNo; }
    public void setClientBatchNo(String clientBatchNo) { this.clientBatchNo = clientBatchNo; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getCertFingerprintSha256() { return certFingerprintSha256; }
    public void setCertFingerprintSha256(String certFingerprintSha256) { this.certFingerprintSha256 = certFingerprintSha256; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String payloadSha256) { this.payloadSha256 = payloadSha256; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getCertPem() { return certPem; }
    public void setCertPem(String certPem) { this.certPem = certPem; }
    public Integer getPointCount() { return pointCount; }
    public void setPointCount(Integer pointCount) { this.pointCount = pointCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public LocalDateTime getReceivedTime() { return receivedTime; }
    public void setReceivedTime(LocalDateTime receivedTime) { this.receivedTime = receivedTime; }
    public LocalDateTime getReleasedTime() { return releasedTime; }
    public void setReleasedTime(LocalDateTime releasedTime) { this.releasedTime = releasedTime; }
    public Long getReleasedBy() { return releasedBy; }
    public void setReleasedBy(Long releasedBy) { this.releasedBy = releasedBy; }
    public Long getReleasedIngestId() { return releasedIngestId; }
    public void setReleasedIngestId(Long releasedIngestId) { this.releasedIngestId = releasedIngestId; }
    public Integer getReplayCount() { return replayCount; }
    public void setReplayCount(Integer replayCount) { this.replayCount = replayCount; }
}
