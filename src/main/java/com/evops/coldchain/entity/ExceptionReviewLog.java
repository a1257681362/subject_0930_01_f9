package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 异常关闭前复核记录（V6，只追加）。
 * 处置结论提交后进入待复核；复核通过 APPROVED 才允许关闭（异常 RESOLVED），
 * 驳回 REJECTED 退回已确认重新处置。每次复核追加一行，历史复核意见不覆盖。
 */
@TableName("t_cc_exception_review_log")
public class ExceptionReviewLog extends CreateOnlyEntity {
    private Long exceptionId;
    /** APPROVED 通过关闭 / REJECTED 驳回重处 */
    private String result;
    private String reviewNote;
    /** 复核时实际采用的复核岗位（快照，便于审计是否由指定岗位复核） */
    private String reviewerRole;

    public Long getExceptionId() { return exceptionId; }
    public void setExceptionId(Long exceptionId) { this.exceptionId = exceptionId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public String getReviewerRole() { return reviewerRole; }
    public void setReviewerRole(String reviewerRole) { this.reviewerRole = reviewerRole; }
}
