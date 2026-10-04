package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 异常分级规则（V6）：按异常类型唯一一行。
 * 等级/响应时限/处置时限/处置岗位/升级岗位/复核岗位可调整，
 * 但只对“调整后确认”的异常生效——异常确认瞬间把这些值快照到 t_cc_exception。
 */
@TableName("t_cc_exception_grade_rule")
public class ExceptionGradeRule extends BaseEntity {
    /** TEMP_EXCURSION / SEAL_BROKEN / DELAY / REJECTION */
    private String exceptionType;
    private String exceptionTypeName;
    /** L1 重大 / L2 较大 / L3 一般 */
    private String grade;
    private String gradeName;
    /** 响应时限（分钟）：自异常确认时刻（处置时钟起点）起算 */
    private Integer responseMinutes;
    /** 处置时限（分钟）：自确认时刻起算，逾期必须升级 */
    private Integer deadlineMinutes;
    /** 处置岗位（首次通知对象） */
    private String ownerRole;
    /** 升级岗位（逾期/重试通知对象） */
    private String escalateRole;
    /** 关闭前复核岗位 */
    private String reviewerRole;
    /** 通知投递地址（空=站内信必达；非空=HTTP webhook，可能失败需重试） */
    private String notifyEndpoint;
    private Boolean enabled;
    private String remark;

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getExceptionTypeName() { return exceptionTypeName; }
    public void setExceptionTypeName(String exceptionTypeName) { this.exceptionTypeName = exceptionTypeName; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getGradeName() { return gradeName; }
    public void setGradeName(String gradeName) { this.gradeName = gradeName; }
    public Integer getResponseMinutes() { return responseMinutes; }
    public void setResponseMinutes(Integer responseMinutes) { this.responseMinutes = responseMinutes; }
    public Integer getDeadlineMinutes() { return deadlineMinutes; }
    public void setDeadlineMinutes(Integer deadlineMinutes) { this.deadlineMinutes = deadlineMinutes; }
    public String getOwnerRole() { return ownerRole; }
    public void setOwnerRole(String ownerRole) { this.ownerRole = ownerRole; }
    public String getEscalateRole() { return escalateRole; }
    public void setEscalateRole(String escalateRole) { this.escalateRole = escalateRole; }
    public String getReviewerRole() { return reviewerRole; }
    public void setReviewerRole(String reviewerRole) { this.reviewerRole = reviewerRole; }
    public String getNotifyEndpoint() { return notifyEndpoint; }
    public void setNotifyEndpoint(String notifyEndpoint) { this.notifyEndpoint = notifyEndpoint; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
