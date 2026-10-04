package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 隔离数据重放审计（V9）：每一次重放尝试独立一行，只追加。
 * result=SUCCESS 重放成功并释放 / FAILED 仍失败（原因码+说明）/ REJECTED 已入账等拒绝。
 */
@TableName("t_cc_device_replay_audit")
public class DeviceReplayAudit extends CreateOnlyEntity {
    private Long quarantineId;
    private String queueNo;
    private Integer replaySeq;
    private String result;
    private String reasonCode;
    private String reasonDetail;
    private Integer insertedCount;
    private Integer duplicateCount;
    private Integer unmatchedCount;
    private String affectedBatchIds;
    private String operator;
    private LocalDateTime replayTime;

    public Long getQuarantineId() { return quarantineId; }
    public void setQuarantineId(Long quarantineId) { this.quarantineId = quarantineId; }
    public String getQueueNo() { return queueNo; }
    public void setQueueNo(String queueNo) { this.queueNo = queueNo; }
    public Integer getReplaySeq() { return replaySeq; }
    public void setReplaySeq(Integer replaySeq) { this.replaySeq = replaySeq; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public Integer getInsertedCount() { return insertedCount; }
    public void setInsertedCount(Integer insertedCount) { this.insertedCount = insertedCount; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer duplicateCount) { this.duplicateCount = duplicateCount; }
    public Integer getUnmatchedCount() { return unmatchedCount; }
    public void setUnmatchedCount(Integer unmatchedCount) { this.unmatchedCount = unmatchedCount; }
    public String getAffectedBatchIds() { return affectedBatchIds; }
    public void setAffectedBatchIds(String affectedBatchIds) { this.affectedBatchIds = affectedBatchIds; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getReplayTime() { return replayTime; }
    public void setReplayTime(LocalDateTime replayTime) { this.replayTime = replayTime; }
}
