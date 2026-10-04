package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.AlertStreak;
import com.evops.coldchain.entity.BatchAlertZoneSnap;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.AlertStreakMapper;
import com.evops.coldchain.mapper.BatchAlertZoneSnapMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 温控预警评估引擎（V5）。
 *
 * 判定口径（一律用批次创建时固化的 {@link BatchAlertZoneSnap}，不回看类别模板现值）：
 * - 按采样点（批次在途探头全程唯一）逐点判定是否超出本批次预警温区。
 * - 连续超限计时状态落在 t_cc_alert_streak（批次×温区唯一一行，过程状态可复用/重建）。
 * - 采样点短暂回正常：若正常间隔不超过模板“抖动合并窗”，视为抖动，连续超限合并、起点不变；
 *   超过合并窗才真正断开（jitter=0 即“一个正常点就断开”的严格口径，与历史行为一致）。
 * - 连续超限持续“超过”容忍时长，产生一条 {@link TempAlert}（一次连续段只产一条），
 *   并联动一条 TEMP_EXCURSION 异常事件把批次挂起。预警只 INSERT，绝不因模板变更消失。
 *
 * 幂等：
 * - 温度上报走 {@link #onTemperatureReported} 增量实时评估。
 * - 定时任务走 {@link #rebuildBatch} 批次级确定性重放（仅重建 streak 过程状态，不删任何
 *   温度/预警事实）；预警按（批次×温区×起点）去重 + 数据库唯一约束兜底，
 *   因此任务重启重扫不会多写一条预警/异常/流水，也不会改变既有预警。
 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    @Resource
    private BatchAlertZoneSnapMapper snapMapper;
    @Resource
    private TemperatureLogMapper temperatureMapper;
    @Resource
    private AlertStreakMapper streakMapper;
    @Resource
    private TempAlertMapper alertMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ExceptionLifecycleService lifecycleService;

    @Resource
    private OpLogService opLogService;

    /** 批次是否启用了 V5 类别预警模板（派车时固化到温区快照） */
    public BatchAlertZoneSnap getBatchZone(Long batchId, String zoneCode) {
        if (zoneCode == null) {
            return null;
        }
        return snapMapper.selectOne(new QueryWrapper<BatchAlertZoneSnap>()
                .eq("batch_id", batchId)
                .eq("zone_code", zoneCode)
                .last("LIMIT 1"));
    }

    /** 温度上报后实时增量评估该采样点（与上报同一事务）；V9 补传保护点跳过 */
    @Transactional
    public void onTemperatureReported(TransportBatch batch, TemperatureLog point) {
        if (Boolean.TRUE.equals(point.getAlertExempt())) {
            return; // V9：补传保护点不参与预警评估
        }
        BatchAlertZoneSnap zone = getBatchZone(batch.getId(), batch.getAlertZoneCode());
        if (zone == null) {
            return; // 未挂类别/解析不到温区：不做 V5 预警，V1/V2 既有判定照常
        }
        AlertStreak streak = findStreak(batch.getId(), zone.getZoneCode());
        applyPoint(batch, zone, point, streak);
    }

    /**
     * 批次级确定性重放（定时任务兜底/重启续算）。
     * 仅重建 streak 过程状态行；预警存在即跳过插入，保证不多写。返回本批次新增预警数。
     */
    @Transactional
    public int rebuildBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getAlertZoneCode() == null) {
            return 0;
        }
        BatchAlertZoneSnap zone = getBatchZone(batchId, batch.getAlertZoneCode());
        if (zone == null) {
            return 0;
        }
        List<TemperatureLog> points = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId)
                .eq("alert_exempt", false)
                .orderByAsc("record_time").orderByAsc("id"));

        // 重放前后预警行数差=本次真正新增数（已存在预警的起点在重放中只关联、不插入）
        long beforeCount = alertMapper.selectCount(new QueryWrapper<TempAlert>().eq("batch_id", batchId));

        // 过程状态允许清空重建（streak 非审计事实，无 op_log）；温度/预警事实一律不删
        streakMapper.delete(new QueryWrapper<AlertStreak>()
                .eq("batch_id", batchId).eq("zone_code", zone.getZoneCode()));

        AlertStreak streak = null;
        for (TemperatureLog p : points) {
            streak = applyPoint(batch, zone, p, streak);
        }

        long afterCount = alertMapper.selectCount(new QueryWrapper<TempAlert>().eq("batch_id", batchId));
        return (int) Math.max(0, afterCount - beforeCount);
    }

    /** 取批次当前连续超限计时状态（批次×温区唯一） */
    private AlertStreak findStreak(Long batchId, String zoneCode) {
        List<AlertStreak> rows = streakMapper.selectList(new QueryWrapper<AlertStreak>()
                .eq("batch_id", batchId).eq("zone_code", zoneCode).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 把单个采样点作用到连续超限状态机，返回（可能新建/复用/更新后的）streak；正常点且无活动段时返回 null。
     */
    private AlertStreak applyPoint(TransportBatch batch, BatchAlertZoneSnap zone,
                                   TemperatureLog point, AlertStreak streak) {
        boolean exceed = isExceed(zone, point.getTemperature());
        int jitter = zone.getJitterGapMinutes() == null ? 0 : zone.getJitterGapMinutes();
        LocalDateTime t = point.getRecordTime();

        if (exceed) {
            if (streak == null) {
                streak = newStreak(batch, zone, point);
            } else if (CcConstants.STREAK_BROKEN.equals(streak.getStatus())) {
                // 上一段已彻底断开：复用状态行开启新连续段
                resetStreak(streak, point);
            } else if (streak.getLastSampleTime() != null
                    && streak.getLastSampleTime().isAfter(streak.getLastExceedTime())) {
                // 处于回正常间隙：用“最后越限->本次越限”的间隔判断抖动合并还是断开重开
                long gap = minutesBetween(streak.getLastExceedTime(), t);
                if (gap > jitter) {
                    resetStreak(streak, point);
                }
                // gap<=jitter：短暂抖动，合并，连续起点保持不变
            }
            streak.setProbeId(point.getProbeId());
            streak.setLastExceedTime(t);
            streak.setLastSampleTime(t);
            streak.setExceedCount((streak.getExceedCount() == null ? 0 : streak.getExceedCount()) + 1);
            streak.setStatus(CcConstants.STREAK_ACTIVE);
            persist(streak);

            // 达到容忍时长且本连续段尚未产生过预警 -> 产生一条
            if (streak.getAlertId() == null) {
                long duration = minutesBetween(streak.getStreakStartTime(), t);
                if (duration > zone.getTolerateMinutes()) {
                    raiseAlert(batch, zone, streak, point, duration);
                }
            }
            return streak;
        }

        // 正常采样点：活动段不立即断开，按“回正常已持续多久”决定合并还是断开
        if (streak != null && CcConstants.STREAK_ACTIVE.equals(streak.getStatus())) {
            streak.setLastSampleTime(t);
            long normalGap = minutesBetween(streak.getLastExceedTime(), t);
            if (normalGap > jitter) {
                streak.setStatus(CcConstants.STREAK_BROKEN);
            }
            streakMapper.updateById(streak);
            return streak;
        }
        return streak;
    }

    private AlertStreak newStreak(TransportBatch batch, BatchAlertZoneSnap zone, TemperatureLog first) {
        AlertStreak s = new AlertStreak();
        s.setBatchId(batch.getId());
        s.setProbeId(first.getProbeId());
        s.setZoneCode(zone.getZoneCode());
        s.setStreakStartTime(first.getRecordTime());
        s.setLastExceedTime(first.getRecordTime());
        s.setLastSampleTime(first.getRecordTime());
        s.setExceedCount(0);
        s.setStatus(CcConstants.STREAK_ACTIVE);
        return s;
    }

    /** 复用状态行开启新连续段（清空上一段的预警关联与起点） */
    private void resetStreak(AlertStreak s, TemperatureLog first) {
        s.setStreakStartTime(first.getRecordTime());
        s.setLastExceedTime(first.getRecordTime());
        s.setLastSampleTime(first.getRecordTime());
        s.setExceedCount(0);
        s.setAlertId(null);
        s.setStatus(CcConstants.STREAK_ACTIVE);
        s.setProbeId(first.getProbeId());
    }

    private void persist(AlertStreak streak) {
        if (streak.getId() == null) {
            streakMapper.insert(streak);
        } else {
            streakMapper.updateById(streak);
        }
    }

    private boolean isExceed(BatchAlertZoneSnap zone, BigDecimal temperature) {
        return temperature.compareTo(zone.getTempMin()) < 0
                || temperature.compareTo(zone.getTempMax()) > 0;
    }

    private long minutesBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toMinutes();
    }

    /** 产生预警（只追加）+ 联动温度异常把批次挂起；重复触发由存在检查与唯一约束双重去重 */
    private void raiseAlert(TransportBatch batch, BatchAlertZoneSnap zone, AlertStreak streak,
                            TemperatureLog trigger, long durationMinutes) {
        LocalDateTime start = streak.getStreakStartTime();
        if (existsAlert(batch.getId(), zone.getZoneCode(), start)) {
            TempAlert exist = findAlert(batch.getId(), zone.getZoneCode(), start);
            if (exist != null) {
                streak.setAlertId(exist.getId());
                streakMapper.updateById(streak);
            }
            return;
        }

        BigDecimal peak = peakTemperature(batch.getId(), start, trigger.getRecordTime(), zone);

        TempAlert alert = new TempAlert();
        alert.setBatchId(batch.getId());
        alert.setProbeId(trigger.getProbeId());
        alert.setZoneCode(zone.getZoneCode());
        alert.setZoneName(zone.getZoneName());
        // 触发时把模板阈值复制进预警：模板之后如何修改，本预警原值不变、也不消失
        alert.setTempMinSnap(zone.getTempMin());
        alert.setTempMaxSnap(zone.getTempMax());
        alert.setTolerateMinutes(zone.getTolerateMinutes());
        alert.setJitterGapMinutes(zone.getJitterGapMinutes());
        alert.setStartTime(start);
        alert.setTriggerTime(trigger.getRecordTime());
        // V6：首次发现时间（临界时刻）与触发时刻同值；联动温度异常的 foundTime/confirmTime
        // 也取同一时刻，温度分级与处置时钟严格同一口径归属。
        alert.setFirstFoundTime(trigger.getRecordTime());
        alert.setLastExceedTime(trigger.getRecordTime());
        alert.setDurationMinutes(durationMinutes);
        alert.setPeakTemperature(peak);
        alert.setSourceCategoryNo(zone.getSourceCategoryNo());
        alert.setStatus(CcConstants.ALERT_OPEN);
        alert.setDescription(String.format(
                "系统按采样点连续超限自动预警：批次 %s 探头#%s 在%s~%s 连续超限 %d 分钟（容忍 %d 分钟，抖动合并窗 %d 分钟），"
                        + "温区[%s/%s]阈值 %s~%s℃，峰值 %s℃，阈值来源类别=%s%s",
                batch.getBatchNo(),
                trigger.getProbeId() == null ? "-" : String.valueOf(trigger.getProbeId()),
                start, trigger.getRecordTime(), durationMinutes,
                zone.getTolerateMinutes(), zone.getJitterGapMinutes(),
                zone.getZoneCode(), zone.getZoneName(), zone.getTempMin(), zone.getTempMax(),
                peak, zone.getSourceCategoryNo(),
                Boolean.TRUE.equals(zone.getInherited()) ? "（上级类别继承）" : "（本类别指定温区覆盖）"));
        try {
            alertMapper.insert(alert);
        } catch (DuplicateKeyException dup) {
            // 并发/重启兜底：同一起点已有预警，回查关联，不再多写
            TempAlert exist = findAlert(batch.getId(), zone.getZoneCode(), start);
            if (exist != null) {
                streak.setAlertId(exist.getId());
                streakMapper.updateById(streak);
            }
            return;
        }
        streak.setAlertId(alert.getId());
        streakMapper.updateById(streak);

        // 联动温度异常事件（与温度上报自动异常同一套挂起/签收卡口）：
        // V6 起温度异常按分级机制“客观成立即确认”，处置时钟自临界时刻（=firstFoundTime）起算，
        // 随预警首次产生一次性登记（预警存在检查 + 唯一约束保证不重复登记）。
        lifecycleService.registerAutoConfirmed(batch, CcConstants.EXC_TEMP, alert.getDescription(),
                alert.getFirstFoundTime(), null, alert.getId());

        opLogService.log(CcConstants.ENT_TEMP_ALERT, alert.getId(), batch.getId(), CcConstants.ACT_ALERT_RAISE,
                alert.getDescription());
    }

    private boolean existsAlert(Long batchId, String zoneCode, LocalDateTime start) {
        return start != null && findAlert(batchId, zoneCode, start) != null;
    }

    private TempAlert findAlert(Long batchId, String zoneCode, LocalDateTime start) {
        if (start == null) {
            return null;
        }
        List<TempAlert> rows = alertMapper.selectList(new QueryWrapper<TempAlert>()
                .eq("batch_id", batchId)
                .eq("zone_code", zoneCode)
                .eq("start_time", start)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 连续段 [start, end] 内偏离阈值最远的温度（峰值）；V9 补传保护点不参与 */
    private BigDecimal peakTemperature(Long batchId, LocalDateTime start, LocalDateTime end,
                                       BatchAlertZoneSnap zone) {
        List<TemperatureLog> seg = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId)
                .eq("alert_exempt", false)
                .ge("record_time", start).le("record_time", end)
                .orderByAsc("record_time").orderByAsc("id"));
        BigDecimal peak = null;
        BigDecimal maxDeviation = null;
        for (TemperatureLog p : seg) {
            BigDecimal dev;
            if (p.getTemperature().compareTo(zone.getTempMax()) > 0) {
                dev = p.getTemperature().subtract(zone.getTempMax());
            } else if (p.getTemperature().compareTo(zone.getTempMin()) < 0) {
                dev = zone.getTempMin().subtract(p.getTemperature());
            } else {
                continue;
            }
            if (maxDeviation == null || dev.compareTo(maxDeviation) > 0) {
                maxDeviation = dev;
                peak = p.getTemperature();
            }
        }
        return peak;
    }

    /** 批次闭环/撤回时关闭活动连续段（此后不再有新采样点；事实与已产生预警保留） */
    @Transactional
    public void closeStreaks(Long batchId) {
        List<AlertStreak> actives = streakMapper.selectList(new QueryWrapper<AlertStreak>()
                .eq("batch_id", batchId).eq("status", CcConstants.STREAK_ACTIVE));
        for (AlertStreak s : actives) {
            s.setStatus(CcConstants.STREAK_BROKEN);
            streakMapper.updateById(s);
        }
    }

    /** 预警闭环：只追加处理字段，不改原描述、不删除、不因模板变更消失 */
    @Transactional
    public TempAlert resolveAlert(Long alertId, String resolveNote) {
        TempAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new com.evops.common.BusinessException("温控预警不存在: " + alertId);
        }
        if (CcConstants.ALERT_RESOLVED.equals(alert.getStatus())) {
            throw new com.evops.common.BusinessException("预警已处理，不能重复处理");
        }
        alert.setStatus(CcConstants.ALERT_RESOLVED);
        alert.setResolveNote(resolveNote);
        alert.setResolveBy(UserContext.currentId());
        alert.setResolveTime(LocalDateTime.now());
        alertMapper.updateById(alert);

        // V6：预警闭环只关闭预警本身，不再越级直接关闭联动的温度异常。
        // 温度异常是分级异常，必须走“处置结论（引用证据）→ 指定岗位复核”关闭；
        // 此处仅追加留痕提示，批次仍可能被未关闭异常卡在签收/结算卡口。
        List<ExceptionEvent> linked = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("alert_id", alertId));
        long linkedOpen = linked.stream().filter(e -> !CcConstants.EXC_RESOLVED.equals(e.getStatus())).count();

        opLogService.log(CcConstants.ENT_TEMP_ALERT, alert.getId(), alert.getBatchId(),
                CcConstants.ACT_ALERT_RESOLVE, "温控预警#" + alertId + " 处理闭环：" + resolveNote
                        + String.format("；联动温度异常 %d 条（未关闭 %d 条，须按分级机制引用证据并经质量复核关闭）",
                        linked.size(), linkedOpen));
        return alert;
    }
}
