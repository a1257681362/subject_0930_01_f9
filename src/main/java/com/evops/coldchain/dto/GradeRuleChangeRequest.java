package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;

/**
 * 异常分级规则调整请求（V6）。
 * 按异常类型定位唯一规则行；调整只影响“此后确认”的异常，已确认异常持快照不变。
 */
public class GradeRuleChangeRequest {
    /** TEMP_EXCURSION 温度超限 / SEAL_BROKEN 封签破损 / DELAY 延误 / REJECTION 收货拒签 */
    @NotBlank(message = "异常类型不能为空")
    private String exceptionType;
    /** L1 重大 / L2 较大 / L3 一般 */
    @NotBlank(message = "等级不能为空")
    private String grade;
    @NotBlank(message = "等级名称不能为空")
    private String gradeName;
    /** 响应时限（分钟），自确认时刻起算 */
    @NotNull(message = "响应时限不能为空")
    @PositiveOrZero(message = "响应时限不能为负")
    private Integer responseMinutes;
    /** 处置时限（分钟），自确认时刻起算，逾期升级 */
    @NotNull(message = "处置时限不能为空")
    @PositiveOrZero(message = "处置时限不能为负")
    private Integer deadlineMinutes;
    @NotBlank(message = "处置岗位不能为空")
    private String ownerRole;
    @NotBlank(message = "升级岗位不能为空")
    private String escalateRole;
    @NotBlank(message = "复核岗位不能为空")
    private String reviewerRole;
    /** 通知投递地址（空=站内信必达；非空=HTTP webhook，可失败重试） */
    private String notifyEndpoint;
    private Boolean enabled;
    private String remark;

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
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
