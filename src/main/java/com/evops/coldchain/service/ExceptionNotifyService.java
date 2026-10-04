package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionGradeRule;
import com.evops.coldchain.entity.ExceptionNotifyLog;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.ExceptionGradeRuleMapper;
import com.evops.coldchain.mapper.ExceptionNotifyLogMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 异常升级/响应通知服务（V6）。
 *
 * 只追加，不覆盖：
 * - 确认异常时向处置岗位发首响通知（RESPONSE）；
 * - 处置时限逾期未闭环向升级岗位发升级通知（ESCALATE）；
 * - 发送失败留 failReason，可手动/定时重试，每次重试独立一行（RETRY/ESCALATE，attemptNo 递增），
 *   重试必须记录 retryReason；历史失败行原样保留。
 * - endpoint 为空走站内信（必达，SUCCESS）；非空走 {@link ExceptionNotifyTransport}（可能失败）。
 */
@Service
public class ExceptionNotifyService {

    @Resource
    private ExceptionNotifyLogMapper notifyMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private ExceptionGradeRuleMapper ruleMapper;
    @Resource
    private ExceptionNotifyTransport transport;
    @Resource
    private OpLogService opLogService;

    /** 确认时向处置岗位发首响通知，返回落库的通知行（无论成功失败） */
    @Transactional
    public ExceptionNotifyLog notifyResponse(ExceptionEvent event) {
        return send(event, event.getOwnerRoleSnap(), CcConstants.NOTIFY_RESPONSE, null);
    }

    /**
     * 逾期升级：向升级岗位发送升级通知。每个异常至多升级一次（升级事实只进不退）；
     * 若升级通知发送失败，后续靠手动/定时重试补发。
     */
    @Transactional
    public ExceptionNotifyLog notifyEscalate(ExceptionEvent event) {
        return send(event, event.getEscalateRoleSnap(), CcConstants.NOTIFY_ESCALATE,
                "处置时限已逾期（截止 " + event.getResolveDueTime() + "）仍未闭环，自动升级督办");
    }

    /**
     * 手动重试：向最近一次通知的同一目标岗位/阶段补发。
     * 必须给出重试原因；最近一次已成功时不允许无意义重试。
     */
    @Transactional
    public ExceptionNotifyLog retry(Long exceptionId, String retryReason) {
        if (retryReason == null || retryReason.trim().isEmpty()) {
            throw new BusinessException("重试通知必须填写重试原因");
        }
        List<ExceptionNotifyLog> rows = listLogs(exceptionId);
        if (rows.isEmpty()) {
            throw new BusinessException("异常#" + exceptionId + " 尚无任何通知记录，无法重试");
        }
        ExceptionNotifyLog last = rows.get(rows.size() - 1);
        if (CcConstants.NOTIFY_SUCCESS.equals(last.getSendStatus())) {
            throw new BusinessException("最近一次通知已成功送达，无需重试");
        }
        ExceptionEvent event = new ExceptionEvent();
        event.setId(exceptionId);
        ExceptionEvent full = exceptionMapper.selectById(exceptionId);
        if (full != null) {
            event.setGrade(full.getGrade());
            event.setFoundTime(full.getFoundTime());
            event.setConfirmTime(full.getConfirmTime());
            event.setResolveDueTime(full.getResolveDueTime());
            event.setOwnerRoleSnap(peekOwnerRole(exceptionId, rows));
            event.setEscalateRoleSnap(peekEscalateRole(exceptionId, rows));
            event.setNotifyEndpoint(resolveRuleEndpoint(full));
        } else {
            event.setOwnerRoleSnap(peekOwnerRole(exceptionId, rows));
            event.setEscalateRoleSnap(peekEscalateRole(exceptionId, rows));
        }
        String stage = CcConstants.NOTIFY_ESCALATE.equals(last.getNotifyStage())
                ? CcConstants.NOTIFY_ESCALATE : CcConstants.NOTIFY_RETRY;
        ExceptionNotifyLog logRow = send(event, last.getTargetRole(), stage, retryReason.trim());
        logRow.setRetryReason(retryReason.trim());
        notifyMapper.updateById(logRow);

        opLogService.log(CcConstants.ENT_EXCEPTION, exceptionId, null, CcConstants.ACT_EXC_NOTIFY_RETRY,
                String.format("异常#%d 通知手动重试：目标岗位=%s，重试阶段=%s，第%d次尝试，重试原因=%s，结果=%s%s",
                        exceptionId, last.getTargetRole(), stage, logRow.getAttemptNo(),
                        retryReason.trim(), logRow.getSendStatus(),
                        logRow.getFailReason() == null ? "" : "，失败原因=" + logRow.getFailReason()));
        return logRow;
    }

    /**
     * 定时任务兜底：对“有失败通知、且最近一次仍失败、且异常未关闭”的异常自动重试；
     * 已升级过的异常按升级口径补发升级岗，其余补发处置岗首响。
     * @return 本次自动重试条数
     */
    @Transactional
    public int retryFailedNotifications(List<ExceptionEvent> openEvents) {
        int retried = 0;
        for (ExceptionEvent event : openEvents) {
            List<ExceptionNotifyLog> rows = listLogs(event.getId());
            if (rows.isEmpty()) {
                continue;
            }
            ExceptionNotifyLog last = rows.get(rows.size() - 1);
            if (CcConstants.NOTIFY_SUCCESS.equals(last.getSendStatus())) {
                continue;
            }
            String reason = "定时自动重试：上一次（" + last.getSendTime() + "）发送失败——"
                    + (last.getFailReason() == null ? "未知原因" : last.getFailReason());
            String stage = CcConstants.NOTIFY_ESCALATE.equals(last.getNotifyStage())
                    || Boolean.TRUE.equals(event.getEscalated())
                    ? CcConstants.NOTIFY_ESCALATE : CcConstants.NOTIFY_RETRY;
            ExceptionNotifyLog logRow = send(event, last.getTargetRole(), stage, reason);
            logRow.setRetryReason(reason);
            notifyMapper.updateById(logRow);
            retried++;
        }
        return retried;
    }

    private ExceptionNotifyLog send(ExceptionEvent event, String targetRole, String stage, String retryReason) {
        if (targetRole == null || targetRole.trim().isEmpty()) {
            throw new BusinessException("异常#" + event.getId() + " 缺少目标岗位配置，无法发送通知");
        }
        int attempt = nextAttemptNo(event.getId(), stage);
        String endpoint = resolveEndpoint(event, targetRole);
        ExceptionNotifyLog row = new ExceptionNotifyLog();
        row.setExceptionId(event.getId());
        row.setTargetRole(targetRole);
        row.setNotifyStage(stage);
        row.setAttemptNo(attempt);
        row.setSendTime(LocalDateTime.now());
        row.setRetryReason(stage.equals(CcConstants.NOTIFY_RESPONSE) ? null : retryReason);

        String payload = buildPayload(event, targetRole, stage);
        if (endpoint == null) {
            // 站内信：演示环境视为必达（写库即送达），记录 SITE 渠道
            row.setChannel(CcConstants.NOTIFY_CHANNEL_SITE);
            row.setSendStatus(CcConstants.NOTIFY_SUCCESS);
        } else {
            row.setChannel(CcConstants.NOTIFY_CHANNEL_WEBHOOK);
            ExceptionNotifyTransport.SendResult result = transport.send(endpoint, payload);
            row.setSendStatus(result.isSuccess() ? CcConstants.NOTIFY_SUCCESS : CcConstants.NOTIFY_FAILED);
            if (!result.isSuccess()) {
                row.setFailReason(result.getFailReason());
            }
        }
        notifyMapper.insert(row);

        String action = CcConstants.NOTIFY_ESCALATE.equals(stage)
                ? CcConstants.ACT_EXC_ESCALATE : CcConstants.ACT_EXC_NOTIFY;
        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), null, action,
                String.format("异常#%d %s通知：目标岗位=%s，第%d次尝试，渠道=%s，结果=%s%s",
                        event.getId(), stageDesc(stage), targetRole, attempt, row.getChannel(),
                        row.getSendStatus(), row.getFailReason() == null ? "" : "，失败原因=" + row.getFailReason()));
        return row;
    }

    /** 取投递地址：优先用调用方（确认/升级路径）瞬态携带的规则 endpoint；
     * 定时重试路径事件从库中重载、瞬态字段为空，则按异常类型回查分级规则上的 endpoint，
     * 保证重试与首次发送走同一渠道。 */
    private String resolveEndpoint(ExceptionEvent event, String targetRole) {
        if (event.getNotifyEndpoint() != null) {
            return event.getNotifyEndpoint();
        }
        return resolveRuleEndpoint(event);
    }

    private String resolveRuleEndpoint(ExceptionEvent event) {
        if (event.getExceptionType() == null) {
            ExceptionEvent full = exceptionMapper.selectById(event.getId());
            if (full == null) {
                return null;
            }
            event.setExceptionType(full.getExceptionType());
        }
        ExceptionGradeRule rule = ruleMapper.selectOne(new QueryWrapper<ExceptionGradeRule>()
                .eq("exception_type", event.getExceptionType()).last("LIMIT 1"));
        return rule == null ? null : rule.getNotifyEndpoint();
    }

    private int nextAttemptNo(Long exceptionId, String stage) {
        Long n = notifyMapper.selectCount(new QueryWrapper<ExceptionNotifyLog>()
                .eq("exception_id", exceptionId)
                .eq("notify_stage", stage));
        return (n == null ? 0 : n.intValue()) + 1;
    }

    private String buildPayload(ExceptionEvent event, String targetRole, String stage) {
        return String.format(
                "{\"exceptionId\":%d,\"stage\":\"%s\",\"targetRole\":\"%s\",\"grade\":\"%s\","
                        + "\"foundTime\":\"%s\",\"confirmTime\":\"%s\",\"resolveDueTime\":\"%s\"}",
                event.getId(), stage, targetRole, event.getGrade(),
                event.getFoundTime(), event.getConfirmTime(), event.getResolveDueTime());
    }

    public List<ExceptionNotifyLog> listLogs(Long exceptionId) {
        return notifyMapper.selectList(new QueryWrapper<ExceptionNotifyLog>()
                .eq("exception_id", exceptionId).orderByAsc("id"));
    }

    /** 重试时仅需目标岗位；最近通知行的目标岗位已足够，这里只保证实体字段非空以构造 payload */
    private String peekOwnerRole(Long exceptionId, List<ExceptionNotifyLog> rows) {
        for (ExceptionNotifyLog r : rows) {
            if (CcConstants.NOTIFY_RESPONSE.equals(r.getNotifyStage())) {
                return r.getTargetRole();
            }
        }
        return rows.get(rows.size() - 1).getTargetRole();
    }

    private String peekEscalateRole(Long exceptionId, List<ExceptionNotifyLog> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (CcConstants.NOTIFY_ESCALATE.equals(rows.get(i).getNotifyStage())) {
                return rows.get(i).getTargetRole();
            }
        }
        return rows.get(rows.size() - 1).getTargetRole();
    }

    private String stageDesc(String stage) {
        switch (stage) {
            case CcConstants.NOTIFY_RESPONSE:
                return "首响";
            case CcConstants.NOTIFY_ESCALATE:
                return "逾期升级";
            default:
                return "重试";
        }
    }
}
