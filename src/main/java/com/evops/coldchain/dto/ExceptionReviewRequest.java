package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 异常关闭前复核请求（V6）。
 * approved=TRUE 复核通过关闭异常；FALSE 驳回退回处置（时钟不重启）。
 * 无论通过/驳回都必须填写复核意见，且必须由分级规则指定的复核岗位执行。
 */
public class ExceptionReviewRequest {
    @NotNull(message = "复核结论不能为空（true通过/false驳回）")
    private Boolean approved;
    @NotBlank(message = "复核意见不能为空（通过/驳回都必须留下依据）")
    private String reviewNote;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
