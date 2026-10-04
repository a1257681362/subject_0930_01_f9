package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 异常升级/响应通知发送记录（V6，只追加）。
 * 首次通知与每一次重试各写一行：sendStatus 记录发送成功/失败；
 * 失败必须记录 failReason；重试 is_retry 语义由 notifyStage=RETRY/ESCALATE + attemptNo 表达。
 */
@TableName("t_cc_exception_notify_log")
public class ExceptionNotifyLog extends CreateOnlyEntity {
    private Long exceptionId;
    /** 本行通知目标岗位（处置岗位 owner_role / 升级岗位 escalate_role） */
    private String targetRole;
    /** RESPONSE 确认时首响通知 / ESCALATE 逾期升级 / RETRY 手动重试 */
    private String notifyStage;
    /** 同一异常同一阶段的尝试次序，从 1 递增 */
    private Integer attemptNo;
    /** SITE 站内信 / WEBHOOK */
    private String channel;
    /** SUCCESS / FAILED */
    private String sendStatus;
    /** 失败原因（FAILED 时必填，重试成功后历史失败行仍保留可查） */
    private String failReason;
    /** 重试原因（stage=RETRY 时必填：为何发起本次重试，如上一次失败原因） */
    private String retryReason;
    /** 实际发送尝试时间（通知发送时刻，不是处置时钟起点） */
    private LocalDateTime sendTime;

    public Long getExceptionId() { return exceptionId; }
    public void setExceptionId(Long exceptionId) { this.exceptionId = exceptionId; }
    public String getTargetRole() { return targetRole; }
    public void setTargetRole(String targetRole) { this.targetRole = targetRole; }
    public String getNotifyStage() { return notifyStage; }
    public void setNotifyStage(String notifyStage) { this.notifyStage = notifyStage; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
    public LocalDateTime getSendTime() { return sendTime; }
    public void setSendTime(LocalDateTime sendTime) { this.sendTime = sendTime; }
}
