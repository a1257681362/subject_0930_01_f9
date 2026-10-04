package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 隔离队列（V9）：签名/证书验证失败的设备数据批次。
 * 记录原因代码和可读详情；运维修复问题后可重放（仅限 PENDING 状态）。
 */
@TableName("t_cc_device_quarantine")
public class DeviceQuarantine extends BaseEntity {
    /** 关联数据批次登记 */
    private Long ingestBatchId;
    /** 设备序列号 */
    private String deviceSerial;
    /** 关联运输批次 */
    private Long batchId;
    /** 原因代码：DEVICE_UNKNOWN / CERT_EXPIRED / CERT_REVOKED / CERT_MISMATCH / SIGN_MISMATCH / PAYLOAD_MALFORMED / BATCH_INVALID */
    private String reasonCode;
    /** 可读错误详情 */
    private String reasonDetail;
    /** 原始数据快照（JSON，供重放） */
    private String payloadSnapshot;
    /** PENDING 待处理 / REPLAYED 已重放 / DISCARDED 已废弃 */
    private String status;
    /** 已重放次数 */
    private Integer replayCount;

    public Long getIngestBatchId() { return ingestBatchId; }
    public void setIngestBatchId(Long ingestBatchId) { this.ingestBatchId = ingestBatchId; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public String getPayloadSnapshot() { return payloadSnapshot; }
    public void setPayloadSnapshot(String payloadSnapshot) { this.payloadSnapshot = payloadSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getReplayCount() { return replayCount; }
    public void setReplayCount(Integer replayCount) { this.replayCount = replayCount; }
}
