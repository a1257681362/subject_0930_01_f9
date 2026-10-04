package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 交接封签核验明细（每个封签一行）。
 * 封签号在同一运输批次范围内唯一；核验状态只允许 PENDING -> CONFIRMED 单向流转：
 * 已确认的核验事实（核验说明/核验人/核验时间）只进不退，不提供改写或删除路径。
 * 应用层仅在“核验确认”这一状态流转时 update 本行，历史核验事实永不回写。
 */
@TableName("t_cc_handover_seal")
public class HandoverSeal extends BaseEntity {
    private Long handoverId;
    private Long batchId;
    private Integer sealSeq;
    private String sealNo;
    /** PENDING / CONFIRMED */
    private String verifyStatus;
    private String verifyNote;
    private String verifiedBy;
    private LocalDateTime verifyTime;

    public Long getHandoverId() { return handoverId; }
    public void setHandoverId(Long handoverId) { this.handoverId = handoverId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getSealSeq() { return sealSeq; }
    public void setSealSeq(Integer sealSeq) { this.sealSeq = sealSeq; }
    public String getSealNo() { return sealNo; }
    public void setSealNo(String sealNo) { this.sealNo = sealNo; }
    public String getVerifyStatus() { return verifyStatus; }
    public void setVerifyStatus(String verifyStatus) { this.verifyStatus = verifyStatus; }
    public String getVerifyNote() { return verifyNote; }
    public void setVerifyNote(String verifyNote) { this.verifyNote = verifyNote; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public LocalDateTime getVerifyTime() { return verifyTime; }
    public void setVerifyTime(LocalDateTime verifyTime) { this.verifyTime = verifyTime; }
}
