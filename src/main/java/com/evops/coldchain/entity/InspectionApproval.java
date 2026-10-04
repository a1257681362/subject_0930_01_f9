package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 冷链药品运输审批（V7），两步走：
 * 申请 -> PENDING（待审批，只完成一半，系统停留待处理）-> APPROVED 同意 / REJECTED 驳回。
 * PENDING/REJECTED 的批次不得发运；审批进行期间抽检任务与样本封存照常可办、可追溯。
 */
@TableName("t_cc_inspection_approval")
public class InspectionApproval extends BaseEntity {
    private String approvalNo;
    private Long batchId;
    private String superviseRegion;
    /** 审批类型，默认 TRANSPORT（冷链药品运输放行） */
    private String approvalType;
    private String reason;
    /** PENDING / APPROVED / REJECTED */
    private String status;
    private Long applyBy;
    private String applyName;
    private LocalDateTime applyTime;
    private Long approveBy;
    private String approveName;
    private LocalDateTime approveTime;
    private String reviewNote;

    public String getApprovalNo() { return approvalNo; }
    public void setApprovalNo(String approvalNo) { this.approvalNo = approvalNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
    public String getApprovalType() { return approvalType; }
    public void setApprovalType(String approvalType) { this.approvalType = approvalType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getApplyBy() { return applyBy; }
    public void setApplyBy(Long applyBy) { this.applyBy = applyBy; }
    public String getApplyName() { return applyName; }
    public void setApplyName(String applyName) { this.applyName = applyName; }
    public LocalDateTime getApplyTime() { return applyTime; }
    public void setApplyTime(LocalDateTime applyTime) { this.applyTime = applyTime; }
    public Long getApproveBy() { return approveBy; }
    public void setApproveBy(Long approveBy) { this.approveBy = approveBy; }
    public String getApproveName() { return approveName; }
    public void setApproveName(String approveName) { this.approveName = approveName; }
    public LocalDateTime getApproveTime() { return approveTime; }
    public void setApproveTime(LocalDateTime approveTime) { this.approveTime = approveTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
