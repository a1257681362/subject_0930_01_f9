package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 样本转移单（V7）：样本转移必须双人确认。
 * 移交人发起即落移交人确认（senderConfirmTime），单据停留 PENDING（只完成一半）；
 * 接收人必须是不同于移交人的第二个登录账号，确认后置 CONFIRMED，样本当前持有方随之转移。
 * 每次转移追加一行形成完整转移链，只追加不改写。
 */
@TableName("t_cc_sample_transfer")
public class SampleTransfer extends BaseEntity {
    private Long sampleId;
    private Long taskId;
    private Long batchId;
    private Integer transferSeq;
    private String handoverLocation;
    private String transferNote;
    private String fromCustodianName;
    private String fromCustodianOrg;
    private String senderName;
    private String senderDisplay;
    private LocalDateTime senderConfirmTime;
    private String toCustodianName;
    private String toCustodianOrg;
    private String receiverName;
    private String receiverDisplay;
    private LocalDateTime receiverConfirmTime;
    /** PENDING / CONFIRMED */
    private String status;

    public Long getSampleId() { return sampleId; }
    public void setSampleId(Long sampleId) { this.sampleId = sampleId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getTransferSeq() { return transferSeq; }
    public void setTransferSeq(Integer transferSeq) { this.transferSeq = transferSeq; }
    public String getHandoverLocation() { return handoverLocation; }
    public void setHandoverLocation(String handoverLocation) { this.handoverLocation = handoverLocation; }
    public String getTransferNote() { return transferNote; }
    public void setTransferNote(String transferNote) { this.transferNote = transferNote; }
    public String getFromCustodianName() { return fromCustodianName; }
    public void setFromCustodianName(String fromCustodianName) { this.fromCustodianName = fromCustodianName; }
    public String getFromCustodianOrg() { return fromCustodianOrg; }
    public void setFromCustodianOrg(String fromCustodianOrg) { this.fromCustodianOrg = fromCustodianOrg; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getSenderDisplay() { return senderDisplay; }
    public void setSenderDisplay(String senderDisplay) { this.senderDisplay = senderDisplay; }
    public LocalDateTime getSenderConfirmTime() { return senderConfirmTime; }
    public void setSenderConfirmTime(LocalDateTime senderConfirmTime) { this.senderConfirmTime = senderConfirmTime; }
    public String getToCustodianName() { return toCustodianName; }
    public void setToCustodianName(String toCustodianName) { this.toCustodianName = toCustodianName; }
    public String getToCustodianOrg() { return toCustodianOrg; }
    public void setToCustodianOrg(String toCustodianOrg) { this.toCustodianOrg = toCustodianOrg; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverDisplay() { return receiverDisplay; }
    public void setReceiverDisplay(String receiverDisplay) { this.receiverDisplay = receiverDisplay; }
    public LocalDateTime getReceiverConfirmTime() { return receiverConfirmTime; }
    public void setReceiverConfirmTime(LocalDateTime receiverConfirmTime) { this.receiverConfirmTime = receiverConfirmTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
