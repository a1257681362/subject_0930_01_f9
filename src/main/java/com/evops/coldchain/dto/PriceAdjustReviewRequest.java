package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 人工改价审批（同意/驳回都必须留意见） */
public class PriceAdjustReviewRequest {
    @NotBlank(message = "审批意见不能为空")
    private String reviewNote;

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
