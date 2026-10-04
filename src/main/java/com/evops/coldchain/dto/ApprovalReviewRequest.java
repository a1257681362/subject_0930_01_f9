package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 冷链运输审批审查意见（第二步）：同意或驳回都必须留意见。
 */
public class ApprovalReviewRequest {
    /** 审查意见（同意/驳回均必填） */
    @NotBlank(message = "审批意见不能为空")
    private String reviewNote;

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
