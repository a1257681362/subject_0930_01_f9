package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 异常事件：追加式，一批可多次。
 * 原描述 description 一旦写入永不修改；处理闭环只填 resolve* 字段。
 */
@TableName("t_cc_exception")
public class ExceptionEvent extends BaseEntity {
    private Long batchId;
    /** TEMP_EXCURSION / DAMAGE / DELAY / OTHER */
    private String exceptionType;
    private String description;
    private LocalDateTime occurredTime;
    /** OPEN / RESOLVED */
    private String status;
    private String resolveNote;
    private Long resolveBy;
    private LocalDateTime resolveTime;
    /** 越限命中的路线分段序号（有路线分段时），便于追溯该段责任人 */
    private Integer segmentSeq;
    /** V5：触发该温度异常的温控预警ID（预警闭环时一并闭环对应异常） */
    private Long alertId;

    // —— V6：异常分级与处置时钟 ——
    /** 首次发现时间：仅在异常首次落库时写入一次，任何补录/确认/重放路径都不得覆盖 */
    private LocalDateTime foundTime;
    /** 异常成立依据：AUTO 系统客观判定即确认 / MANUAL 人工补录需显式确认 */
    private String foundSource;
    /** 等级快照 L1/L2/L3（确认时从分级规则复制） */
    private String grade;
    private String gradeName;
    private Integer responseMinutesSnap;
    private Integer deadlineMinutesSnap;
    private String ownerRoleSnap;
    private String escalateRoleSnap;
    private String reviewerRoleSnap;
    /** 确认人/确认时刻（处置时钟起点） */
    private Long confirmBy;
    private LocalDateTime confirmTime;
    /** 响应截止 = 确认时刻 + 响应时限 */
    private LocalDateTime responseDueTime;
    /** 处置截止 = 确认时刻 + 处置时限，逾期升级 */
    private LocalDateTime resolveDueTime;
    /** 是否已升级（升级事实只进不退） */
    private Boolean escalated;
    private LocalDateTime escalateTime;
    /** 处置结论（提交结论必须引用证据） */
    private String conclusion;
    /** 证据引用：t_cc_exception_evidence 行ID 逗号分隔，至少一个 */
    private String evidenceRefs;
    /** 复核状态 NA/PENDING/APPROVED/REJECTED */
    private String reviewStatus;
    private Long reviewBy;
    private LocalDateTime reviewTime;
    private String reviewNote;

    /** V6 瞬态（不落库）：分级规则上的通知投递地址，供通知服务发送时取用 */
    @TableField(exist = false)
    private String notifyEndpoint;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveNote() { return resolveNote; }
    public void setResolveNote(String resolveNote) { this.resolveNote = resolveNote; }
    public Long getResolveBy() { return resolveBy; }
    public void setResolveBy(Long resolveBy) { this.resolveBy = resolveBy; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
    public Integer getSegmentSeq() { return segmentSeq; }
    public void setSegmentSeq(Integer segmentSeq) { this.segmentSeq = segmentSeq; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }
    public LocalDateTime getFoundTime() { return foundTime; }
    public void setFoundTime(LocalDateTime foundTime) { this.foundTime = foundTime; }
    public String getFoundSource() { return foundSource; }
    public void setFoundSource(String foundSource) { this.foundSource = foundSource; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getGradeName() { return gradeName; }
    public void setGradeName(String gradeName) { this.gradeName = gradeName; }
    public Integer getResponseMinutesSnap() { return responseMinutesSnap; }
    public void setResponseMinutesSnap(Integer responseMinutesSnap) { this.responseMinutesSnap = responseMinutesSnap; }
    public Integer getDeadlineMinutesSnap() { return deadlineMinutesSnap; }
    public void setDeadlineMinutesSnap(Integer deadlineMinutesSnap) { this.deadlineMinutesSnap = deadlineMinutesSnap; }
    public String getOwnerRoleSnap() { return ownerRoleSnap; }
    public void setOwnerRoleSnap(String ownerRoleSnap) { this.ownerRoleSnap = ownerRoleSnap; }
    public String getEscalateRoleSnap() { return escalateRoleSnap; }
    public void setEscalateRoleSnap(String escalateRoleSnap) { this.escalateRoleSnap = escalateRoleSnap; }
    public String getReviewerRoleSnap() { return reviewerRoleSnap; }
    public void setReviewerRoleSnap(String reviewerRoleSnap) { this.reviewerRoleSnap = reviewerRoleSnap; }
    public Long getConfirmBy() { return confirmBy; }
    public void setConfirmBy(Long confirmBy) { this.confirmBy = confirmBy; }
    public LocalDateTime getConfirmTime() { return confirmTime; }
    public void setConfirmTime(LocalDateTime confirmTime) { this.confirmTime = confirmTime; }
    public LocalDateTime getResponseDueTime() { return responseDueTime; }
    public void setResponseDueTime(LocalDateTime responseDueTime) { this.responseDueTime = responseDueTime; }
    public LocalDateTime getResolveDueTime() { return resolveDueTime; }
    public void setResolveDueTime(LocalDateTime resolveDueTime) { this.resolveDueTime = resolveDueTime; }
    public Boolean getEscalated() { return escalated; }
    public void setEscalated(Boolean escalated) { this.escalated = escalated; }
    public LocalDateTime getEscalateTime() { return escalateTime; }
    public void setEscalateTime(LocalDateTime escalateTime) { this.escalateTime = escalateTime; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public String getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(String evidenceRefs) { this.evidenceRefs = evidenceRefs; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public String getNotifyEndpoint() { return notifyEndpoint; }
    public void setNotifyEndpoint(String notifyEndpoint) { this.notifyEndpoint = notifyEndpoint; }
}
