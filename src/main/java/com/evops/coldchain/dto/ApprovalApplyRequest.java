package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 冷链药品运输审批申请（两步流程第一步）：
 * 申请后审批停留 PENDING 待处理，经同意（APPROVED）后批次方可发运；
 * 被驳回（REJECTED）可重新申请。
 */
public class ApprovalApplyRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** 监管区域（不传取批次已固化区域） */
    private String superviseRegion;
    @NotBlank(message = "审批申请事由不能为空")
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
