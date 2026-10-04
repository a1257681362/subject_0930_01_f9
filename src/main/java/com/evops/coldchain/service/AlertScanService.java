package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.AlertScanCursor;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.mapper.AlertScanCursorMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 温控预警定时扫描服务（V5）。
 *
 * - 用 t_cc_alert_scan_cursor 记录已纳入计算的最大温度留痕ID，定时任务每次只挑“有新采样点”
 *   的批次，调用 {@link AlertEvaluationService#rebuildBatch} 做批次级确定性重放。
 * - 评估本身幂等（预警按 批次×温区×起点 去重 + 唯一约束兜底），所以即使定时任务重启、
 *   游标重放，也不会多写一条预警/温度异常/产生流水；已产生预警不会因重扫或模板变更消失。
 * - 定时线程无 Shiro 登录主体时操作者记为 system（UserContext 回退），操作者不丢。
 */
@Service
public class AlertScanService {

    private static final int CURSOR_ID = 1;

    @Resource
    private AlertScanCursorMapper cursorMapper;
    @Resource
    private TemperatureLogMapper temperatureMapper;
    @Resource
    private AlertEvaluationService evaluationService;
    @Resource
    private OpLogService opLogService;

    /**
     * 执行一次扫描。返回扫描摘要（处理批次数、新增预警数、推进后的游标）。
     * 无新采样点时不写任何业务记录（只按需推进空游标）。
     */
    @Transactional
    public Map<String, Object> scan() {
        AlertScanCursor cursor = loadOrInitCursor();
        long fromId = cursor.getLastLogId() == null ? 0L : cursor.getLastLogId();

        // 最大温度留痕ID（取最后一行，避免聚合列映射歧义）
        List<TemperatureLog> last = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .orderByDesc("id").last("LIMIT 1"));
        long maxId = last.isEmpty() ? 0L : last.get(0).getId();

        int batchCount = 0;
        int newAlerts = 0;
        if (maxId > fromId) {
            // 仅挑这段时间出现过新采样点的批次重放（重放幂等，不多写）
            List<Long> batchIds = new ArrayList<>();
            for (Object o : temperatureMapper.selectObjs(new QueryWrapper<TemperatureLog>()
                    .select("DISTINCT batch_id").gt("id", fromId))) {
                batchIds.add(((Number) o).longValue());
            }
            for (Long batchId : batchIds) {
                newAlerts += evaluationService.rebuildBatch(batchId);
                batchCount++;
            }

            cursor.setLastLogId(maxId);
            cursor.setLastScanTime(LocalDateTime.now());
            cursor.setLastScanBy(UserContext.currentName());
            cursor.setUpdateBy(UserContext.currentId());
            cursor.setUpdateTime(LocalDateTime.now());
            cursorMapper.updateById(cursor);

            opLogService.log(CcConstants.ENT_TEMP_ALERT, null, null, CcConstants.ACT_ALERT_SCAN,
                    String.format("温控预警定时扫描：新增采样点批次 %d 个（温度留痕 #%d~#%d），新产生预警 %d 条；操作者=%s",
                            batchCount, fromId + 1, maxId, newAlerts, UserContext.currentName()));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scannedBatches", batchCount);
        summary.put("newAlerts", newAlerts);
        summary.put("fromLogId", fromId);
        summary.put("lastLogId", maxId);
        summary.put("operator", UserContext.currentName());
        return summary;
    }

    private AlertScanCursor loadOrInitCursor() {
        AlertScanCursor cursor = cursorMapper.selectById(CURSOR_ID);
        if (cursor == null) {
            cursor = new AlertScanCursor();
            cursor.setId(CURSOR_ID);
            cursor.setLastLogId(0L);
            cursor.setLastScanTime(LocalDateTime.now());
            cursor.setLastScanBy(UserContext.currentName());
            cursor.setUpdateBy(UserContext.currentId());
            cursor.setUpdateTime(LocalDateTime.now());
            cursorMapper.insert(cursor);
        }
        return cursor;
    }
}
