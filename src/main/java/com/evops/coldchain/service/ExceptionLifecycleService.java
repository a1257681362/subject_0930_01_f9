package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.ExceptionGradeRule;
import com.evops.coldchain.entity.ExceptionNotifyLog;
import com.evops.coldchain.entity.ExceptionReviewLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.ExceptionEvidenceMapper;
import com.evops.coldchain.mapper.ExceptionGradeRuleMapper;
import com.evops.coldchain.mapper.ExceptionReviewLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运输异常处置生命周期（V6 核心）。
 *
 * 状态机：
 * <pre>
 *   人工补录：            OPEN（待确认，时钟未启动）
 *                            -- 确认(confirm) --> CONFIRMED（时钟启动）
 *   系统客观判定成立：     直接 CONFIRMED（温度达到临界 / 封签核验破损 / 收货拒签）
 *   CONFIRMED -- 提交引用证据的结论(conclude) --> REVIEWING
 *   REVIEWING -- 指定复核岗位通过(review APPROVED) --> RESOLVED（关闭）
 *              -- 驳回(review REJECTED) --> CONFIRMED（重新处置，复核意见留痕）
 * </pre>
 *
 * 口径铁律：
 * - foundTime（首次发现时间）只在异常首次落库时写一次，确认/补录/重放/重试均不得覆盖；
 * - 处置时钟 confirmTime：人工确认=确认动作时刻；系统判定=异常客观成立的临界时刻
 *  （温度=连续超限达到容忍时长的采样时刻，与 t_cc_temp_alert.first_found_time 同值）；
 * - 等级/时限/岗位在确认瞬间从分级规则快照到异常行，规则之后调整不影响在途时钟；
 * - 关闭必须由 reviewerRoleSnap 指定的角色复核通过。
 */
@Service
public class ExceptionLifecycleService {

    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private ExceptionGradeRuleMapper ruleMapper;
    @Resource
    private ExceptionEvidenceMapper evidenceMapper;
    @Resource
    private ExceptionReviewLogMapper reviewMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ExceptionNotifyService notifyService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 开单 ----------------

    /**
     * 人工补录异常：分级四类进入 OPEN（待确认，时钟未启动）；foundTime 记录首次发现时间，
     * 之后任何路径都不能覆盖它。非分级旧类型（DAMAGE/OTHER）由调用方走旧闭环。
     */
    @Transactional
    public ExceptionEvent openManual(TransportBatch batch, String type, String description,
                                     LocalDateTime foundTime) {
        return insertEvent(batch, type, description, foundTime, null, null, CcConstants.EXC_OPEN,
                "人工补录", UserContext.currentName());
    }

    /**
     * 系统客观判定成立的异常：成立即确认，处置时钟自 foundTime（临界时刻）起算。
     * 温度超限：foundTime=连续超限达到容忍时长的采样时刻（与预警 firstFoundTime 同一口径）；
     * 封签破损：foundTime=核验时刻；收货拒签：foundTime=拒签时刻。
     * 分级规则缺失/停用时退化为 OPEN（不阻断主业务），等待人工确认。
     */
    @Transactional
    public ExceptionEvent registerAutoConfirmed(TransportBatch batch, String type, String description,
                                                LocalDateTime criticalTime, Integer segmentSeq, Long alertId) {
        ExceptionEvent event = insertEvent(batch, type, description, criticalTime, segmentSeq, alertId,
                CcConstants.EXC_OPEN, "系统判定", "system");
        ExceptionGradeRule rule = findEnabledRule(type);
        if (rule == null) {
            opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), batch.getId(), CcConstants.ACT_EXCEPTION_MARK,
                    String.format("批次 %s 系统判定异常[%s]已登记，但分级规则缺失或停用，暂为待确认状态：%s",
                            batch.getBatchNo(), type, description));
            return event;
        }
        return confirmInternal(event, rule, criticalTime, "system");
    }

    private ExceptionEvent insertEvent(TransportBatch batch, String type, String description,
                                       LocalDateTime foundTime, Integer segmentSeq, Long alertId,
                                       String status, String sourceLabel, String operator) {
        ExceptionEvent event = new ExceptionEvent();
        event.setBatchId(batch.getId());
        event.setExceptionType(type);
        event.setDescription(description);
        // foundTime 只在此处写一次
        event.setFoundTime(foundTime);
        event.setFoundSource("系统判定".equals(sourceLabel) ? "AUTO" : "MANUAL");
        event.setOccurredTime(foundTime);
        event.setStatus(status);
        event.setSegmentSeq(segmentSeq);
        event.setAlertId(alertId);
        event.setEscalated(false);
        event.setReviewStatus(CcConstants.REVIEW_NA);
        exceptionMapper.insert(event);

        // 批次挂起（已签收/已撤回不允许走到这里，调用方前置拦截）
        if (!CcConstants.BATCH_EXCEPTION.equals(batch.getStatus())
                && !CcConstants.BATCH_SIGNED.equals(batch.getStatus())
                && !CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            batch.setStatus(CcConstants.BATCH_EXCEPTION);
        }
        if (CcConstants.EXC_TEMP.equals(type)) {
            batch.setHasTempExcursion(true);
        }
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), batch.getId(), CcConstants.ACT_EXCEPTION_MARK,
                String.format("批次 %s 登记异常[%s]（%s，首次发现时间=%s，状态=%s）：%s",
                        batch.getBatchNo(), type, sourceLabel, foundTime, status, description));
        return event;
    }

    // ---------------- 确认（启动处置时钟） ----------------

    /** 人工确认补录异常：按当前分级规则定级、快照时限岗位，处置时钟自确认时刻起算并通知处置岗位 */
    @Transactional
    public ExceptionEvent confirm(Long exceptionId) {
        ExceptionEvent event = requireEvent(exceptionId);
        if (!CcConstants.EXC_OPEN.equals(event.getStatus())) {
            throw new BusinessException(String.format(
                    "异常#%d 当前状态=%s，仅待确认(OPEN)异常可以确认启动处置时钟", exceptionId, event.getStatus()));
        }
        if (event.getConfirmTime() != null || event.getGrade() != null) {
            throw new BusinessException("异常#" + exceptionId + " 已确认过，处置时钟不能重复启动/回拨");
        }
        ExceptionGradeRule rule = requireEnabledRule(event.getExceptionType());
        return confirmInternal(event, rule, LocalDateTime.now(), UserContext.currentName());
    }

    private ExceptionEvent confirmInternal(ExceptionEvent event, ExceptionGradeRule rule,
                                           LocalDateTime clockStart, String operator) {
        if (event.getFoundTime() == null) {
            throw new BusinessException("异常#" + event.getId() + " 缺少首次发现时间，不能启动处置时钟");
        }
        // 分级快照：确认后规则再调整与本异常无关
        event.setGrade(rule.getGrade());
        event.setGradeName(rule.getGradeName());
        event.setResponseMinutesSnap(rule.getResponseMinutes());
        event.setDeadlineMinutesSnap(rule.getDeadlineMinutes());
        event.setOwnerRoleSnap(rule.getOwnerRole());
        event.setEscalateRoleSnap(rule.getEscalateRole());
        event.setReviewerRoleSnap(rule.getReviewerRole());
        event.setConfirmBy(UserContext.currentId());
        event.setConfirmTime(clockStart);
        event.setResponseDueTime(clockStart.plusMinutes(rule.getResponseMinutes()));
        event.setResolveDueTime(clockStart.plusMinutes(rule.getDeadlineMinutes()));
        event.setStatus(CcConstants.EXC_CONFIRMED);
        event.setReviewStatus(CcConstants.REVIEW_NA);
        event.setEscalated(false);
        exceptionMapper.updateById(event);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(), CcConstants.ACT_EXC_CONFIRM,
                String.format("异常#%d 已确认并启动处置时钟：等级=%s(%s)，首次发现=%s，时钟起点=%s，"
                                + "响应截止=%s（%d分钟），处置截止=%s（%d分钟），处置岗=%s，升级岗=%s，复核岗=%s；确认人=%s",
                        event.getId(), rule.getGrade(), rule.getGradeName(), event.getFoundTime(), clockStart,
                        event.getResponseDueTime(), rule.getResponseMinutes(),
                        event.getResolveDueTime(), rule.getDeadlineMinutes(),
                        rule.getOwnerRole(), rule.getEscalateRole(), rule.getReviewerRole(), operator));

        // 通知处置岗位（发送结果由通知服务逐次落库，失败也不阻断确认）
        event.setNotifyEndpoint(rule.getNotifyEndpoint());
        notifyService.notifyResponse(event);
        return event;
    }

    // ---------------- 处置结论（必须引用证据） ----------------

    /** 提交处置结论：必须引用至少一条本异常名下的凭证证据，提交后进入待复核 */
    @Transactional
    public ExceptionEvent conclude(Long exceptionId, String conclusion, List<Long> evidenceIds) {
        ExceptionEvent event = requireEvent(exceptionId);
        if (CcConstants.EXC_REVIEWING.equals(event.getStatus())) {
            throw new BusinessException("异常#" + exceptionId + " 已在待复核，请等待复核结果（驳回后可重新提交结论）");
        }
        if (!CcConstants.EXC_CONFIRMED.equals(event.getStatus())) {
            throw new BusinessException(String.format(
                    "异常#%d 当前状态=%s，仅已确认(CONFIRMED)异常可以提交处置结论", exceptionId, event.getStatus()));
        }
        if (conclusion == null || conclusion.trim().isEmpty()) {
            throw new BusinessException("处置结论不能为空：关闭异常必须给出结论说明");
        }
        List<Long> ids = normalizeIds(evidenceIds);
        if (ids.isEmpty()) {
            throw new BusinessException("处置结论必须引用证据：请先追加凭证/照片/说明并在结论中引用至少一条");
        }
        List<ExceptionEvidence> rows = evidenceMapper.selectList(new QueryWrapper<ExceptionEvidence>()
                .eq("exception_id", exceptionId));
        List<Long> owned = new ArrayList<>();
        for (ExceptionEvidence e : rows) {
            owned.add(e.getId());
        }
        List<Long> invalid = new ArrayList<>();
        for (Long id : ids) {
            if (!owned.contains(id)) {
                invalid.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw new BusinessException("引用证据不存在或不属于异常#" + exceptionId + "：" + invalid
                    + "（处置结论只能引用本异常追加的凭证行）");
        }

        event.setConclusion(conclusion.trim());
        event.setEvidenceRefs(joinIds(ids));
        event.setStatus(CcConstants.EXC_REVIEWING);
        event.setReviewStatus(CcConstants.REVIEW_PENDING);
        exceptionMapper.updateById(event);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(), CcConstants.ACT_EXC_CONCLUDE,
                String.format("异常#%d 提交处置结论并进入复核：%s；引用证据ID=%s；待复核岗位=%s",
                        event.getId(), conclusion.trim(), event.getEvidenceRefs(), event.getReviewerRoleSnap()));
        return event;
    }

    // ---------------- 指定角色复核 ----------------

    /**
     * 关闭前复核：通过才 RESOLVED 关闭，驳回退回 CONFIRMED。
     * 必须由异常快照的复核岗位（reviewerRoleSnap）执行；无登录主体或角色不符一律拒绝。
     */
    @Transactional
    public ExceptionEvent review(Long exceptionId, boolean approved, String reviewNote) {
        ExceptionEvent event = requireEvent(exceptionId);
        if (!CcConstants.EXC_REVIEWING.equals(event.getStatus())
                || !CcConstants.REVIEW_PENDING.equals(event.getReviewStatus())) {
            throw new BusinessException(String.format(
                    "异常#%d 当前状态=%s/%s，仅待复核异常可以复核",
                    exceptionId, event.getStatus(), event.getReviewStatus()));
        }
        if (reviewNote == null || reviewNote.trim().isEmpty()) {
            throw new BusinessException("复核意见不能为空（通过/驳回都必须留下依据）");
        }
        String requiredRole = event.getReviewerRoleSnap();
        if (!currentSubjectHasRole(requiredRole)) {
            throw new BusinessException(String.format(
                    "异常#%d 的分级规则要求由 %s 岗位复核关闭，当前账号不具备该角色，禁止复核",
                    exceptionId, requiredRole));
        }

        ExceptionReviewLog reviewLog = new ExceptionReviewLog();
        reviewLog.setExceptionId(exceptionId);
        reviewLog.setReviewerRole(requiredRole);
        reviewLog.setReviewNote(reviewNote.trim());

        LocalDateTime now = LocalDateTime.now();
        if (approved) {
            reviewLog.setResult(CcConstants.REVIEW_APPROVED);
            reviewMapper.insert(reviewLog);

            event.setReviewStatus(CcConstants.REVIEW_APPROVED);
            event.setReviewBy(UserContext.currentId());
            event.setReviewTime(now);
            event.setReviewNote(reviewNote.trim());
            event.setStatus(CcConstants.EXC_RESOLVED);
            // 兼容旧闭环字段（列表/导出仍可读 resolve*）
            event.setResolveNote("结论：" + event.getConclusion() + "；复核：" + reviewNote.trim());
            event.setResolveBy(UserContext.currentId());
            event.setResolveTime(now);
            exceptionMapper.updateById(event);

            opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(), CcConstants.ACT_EXC_REVIEW,
                    String.format("异常#%d 经 %s 岗位复核通过关闭：%s（处置结论引用证据=%s）",
                            event.getId(), requiredRole, reviewNote.trim(), event.getEvidenceRefs()));
        } else {
            reviewLog.setResult(CcConstants.REVIEW_REJECTED);
            reviewMapper.insert(reviewLog);

            event.setReviewStatus(CcConstants.REVIEW_REJECTED);
            event.setReviewBy(UserContext.currentId());
            event.setReviewTime(now);
            event.setReviewNote(reviewNote.trim());
            // 退回重新处置：结论与证据引用保留可查，状态回到 CONFIRMED，时钟不重启（截止时间不变）
            event.setStatus(CcConstants.EXC_CONFIRMED);
            exceptionMapper.updateById(event);

            opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(), CcConstants.ACT_EXC_REVIEW,
                    String.format("异常#%d 经 %s 岗位复核驳回，退回重新处置（处置时钟不重启，截止仍为 %s）：%s",
                            event.getId(), requiredRole, event.getResolveDueTime(), reviewNote.trim()));
        }
        return event;
    }

    // ---------------- 时钟扫描：逾期升级 + 失败通知重试 ----------------

    /**
     * 定时扫描（也可手动触发）：
     * 1) CONFIRMED 且处置截止已逾期且未升级：置升级事实并通知升级岗位（一次）；
     * 2) 未关闭异常最近一次通知仍失败：自动重试并记录重试原因。
     */
    @Transactional
    public Map<String, Object> scanClock(LocalDateTime now) {
        List<ExceptionEvent> due = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("status", CcConstants.EXC_CONFIRMED)
                .eq("escalated", false)
                .le("resolve_due_time", now));
        int escalated = 0;
        for (ExceptionEvent event : due) {
            // 已撤回批次不再督办升级（事实保留，但处置时钟停止推进）
            TransportBatch eventBatch = batchMapper.selectById(event.getBatchId());
            if (eventBatch == null || CcConstants.BATCH_REVOKED.equals(eventBatch.getStatus())) {
                continue;
            }
            event.setEscalated(true);
            event.setEscalateTime(now);
            exceptionMapper.updateById(event);
            ExceptionGradeRule rule = findEnabledRule(event.getExceptionType());
            if (rule != null) {
                event.setNotifyEndpoint(rule.getNotifyEndpoint());
            }
            notifyService.notifyEscalate(event);
            escalated++;
        }

        List<ExceptionEvent> openEvents = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .in("status", Arrays.asList(
                        CcConstants.EXC_OPEN, CcConstants.EXC_CONFIRMED, CcConstants.EXC_REVIEWING)));
        // 同样排除已撤回批次，不再为其补发通知
        openEvents.removeIf(e -> {
            TransportBatch b = batchMapper.selectById(e.getBatchId());
            return b == null || CcConstants.BATCH_REVOKED.equals(b.getStatus());
        });
        int retried = notifyService.retryFailedNotifications(openEvents);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scannedAt", now);
        summary.put("escalated", escalated);
        summary.put("notificationRetried", retried);
        if (escalated > 0 || retried > 0) {
            opLogService.log(CcConstants.ENT_EXCEPTION, null, null, CcConstants.ACT_EXC_ESCALATE,
                    String.format("异常处置时钟扫描：逾期升级 %d 条，失败通知自动重试 %d 条；扫描时刻=%s",
                            escalated, retried, now));
        }
        return summary;
    }

    // ---------------- 卡口与查询 ----------------

    /** 未关闭异常数（OPEN 待确认 / CONFIRMED 处置中 / REVIEWING 待复核 均拦截签收与结算） */
    public long unclosedCount(Long batchId) {
        Long n = exceptionMapper.selectCount(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batchId)
                .in("status", Arrays.asList(
                        CcConstants.EXC_OPEN, CcConstants.EXC_CONFIRMED, CcConstants.EXC_REVIEWING)));
        return n == null ? 0 : n;
    }

    public void requireAllClosed(Long batchId, String action) {
        long n = unclosedCount(batchId);
        if (n > 0) {
            throw new BusinessException(String.format(
                    "批次存在 %d 条未关闭异常（待确认/处置中/待复核），不能%s；请完成处置结论、引用证据并经指定岗位复核关闭",
                    n, action));
        }
    }

    public ExceptionEvent requireEvent(Long exceptionId) {
        ExceptionEvent event = exceptionMapper.selectById(exceptionId);
        if (event == null) {
            throw new BusinessException("异常事件不存在: " + exceptionId);
        }
        return event;
    }

    /** 升级/响应通知手动重试（委托通知服务，重试原因与发送结果由其逐次落库） */
    @Transactional
    public ExceptionNotifyLog retryNotify(Long exceptionId, String retryReason) {
        requireEvent(exceptionId);
        return notifyService.retry(exceptionId, retryReason);
    }

    public List<ExceptionReviewLog> listReviews(Long exceptionId) {
        return reviewMapper.selectList(new QueryWrapper<ExceptionReviewLog>()
                .eq("exception_id", exceptionId).orderByAsc("id"));
    }

    public List<ExceptionNotifyLog> listNotifications(Long exceptionId) {
        return notifyService.listLogs(exceptionId);
    }

    /** 判断类型是否纳入 V6 分级（有启用规则） */
    public boolean isGradedType(String type) {
        return ExceptionGradeService.GRADED_TYPES.contains(type);
    }

    // ---------------- 私有工具 ----------------

    private ExceptionGradeRule findEnabledRule(String type) {
        ExceptionGradeRule rule = ruleMapper.selectOne(new QueryWrapper<ExceptionGradeRule>()
                .eq("exception_type", type).last("LIMIT 1"));
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            return null;
        }
        return rule;
    }

    private ExceptionGradeRule requireEnabledRule(String type) {
        ExceptionGradeRule rule = findEnabledRule(type);
        if (rule == null) {
            throw new BusinessException("异常类型[" + type + "]未配置启用的分级规则，不能确认/定级");
        }
        return rule;
    }

    private List<Long> normalizeIds(List<Long> raw) {
        List<Long> ids = new ArrayList<>();
        if (raw != null) {
            for (Long id : raw) {
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * 当前登录主体是否拥有指定角色。无 Shiro 主体（定时线程）或未认证均视为不具备
     * （复核只能由真实登录的指定岗位执行；系统线程不做复核动作）。
     */
    private boolean currentSubjectHasRole(String role) {
        if (role == null) {
            return false;
        }
        try {
            Subject subject = SecurityUtils.getSubject();
            return subject.getPrincipal() != null && subject.hasRole(role);
        } catch (Exception e) {
            return false;
        }
    }
}
