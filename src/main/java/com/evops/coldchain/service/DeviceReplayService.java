package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DeviceIngestRequest;
import com.evops.coldchain.dto.DeviceReplayRequest;
import com.evops.coldchain.entity.DeviceIngestBatch;
import com.evops.coldchain.entity.DeviceQuarantine;
import com.evops.coldchain.entity.DeviceReplayLog;
import com.evops.coldchain.mapper.DeviceIngestBatchMapper;
import com.evops.coldchain.mapper.DeviceQuarantineMapper;
import com.evops.coldchain.mapper.DeviceReplayLogMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备数据重放服务（V9）。
 *
 * 规则：
 * - 只能重放未入账（PENDING）的隔离数据；已入账（REPLAYED）不能重复重放。
 * - 每次重放写审计记录（操作者、时间、结果、入账点数）。
 * - 重放成功后隔离状态置 REPLAYED，数据批次状态置 POSTED。
 */
@Service
public class DeviceReplayService {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    @Resource
    private DeviceQuarantineMapper quarantineMapper;
    @Resource
    private DeviceIngestBatchMapper ingestBatchMapper;
    @Resource
    private DeviceReplayLogMapper replayLogMapper;
    @Resource
    private DeviceIngestService ingestService;
    @Resource
    private OpLogService opLogService;

    /**
     * 重放隔离数据。
     * 仅限 PENDING 状态；重放全过程写审计记录。
     */
    @Transactional
    public Map<String, Object> replay(Long quarantineId, DeviceReplayRequest req) {
        DeviceQuarantine quarantine = ingestService.requireQuarantine(quarantineId);

        // 只能重放未入账数据
        if (!CcConstants.QUARANTINE_PENDING.equals(quarantine.getStatus())) {
            throw new BusinessException(String.format(
                    "隔离数据当前状态=%s，仅待处理（PENDING）状态可以重放；已入账数据不能重复重放",
                    quarantine.getStatus()));
        }

        int replaySeq = quarantine.getReplayCount() + 1;
        String operator = UserContext.currentName();

        // 解析原始数据
        DeviceIngestRequest originalReq;
        try {
            originalReq = M.readValue(quarantine.getPayloadSnapshot(), DeviceIngestRequest.class);
        } catch (Exception e) {
            writeReplayLog(quarantineId, replaySeq, operator, CcConstants.REPLAY_FAILED,
                    "原始数据快照解析失败: " + e.getMessage(), 0);
            incrementReplayCount(quarantine);
            throw new BusinessException("隔离数据快照解析失败，无法重放: " + e.getMessage());
        }

        // 重新执行接入流程（使用新的 requestId 避免幂等冲突）
        String newRequestId = originalReq.getRequestId() + "-replay-" + replaySeq;
        originalReq.setRequestId(newRequestId);

        Map<String, Object> ingestResult;
        try {
            ingestResult = ingestService.ingest(originalReq);
        } catch (Exception e) {
            writeReplayLog(quarantineId, replaySeq, operator, CcConstants.REPLAY_FAILED,
                    "重放执行失败: " + e.getMessage(), 0);
            incrementReplayCount(quarantine);
            throw new BusinessException("重放执行失败: " + e.getMessage());
        }

        String status = (String) ingestResult.get("status");
        int pointsPosted = ingestResult.get("acceptedCount") == null ? 0
                : ((Number) ingestResult.get("acceptedCount")).intValue();

        if (CcConstants.INGEST_POSTED.equals(status)) {
            // 重放成功：更新隔离状态
            quarantine.setStatus(CcConstants.QUARANTINE_REPLAYED);
            quarantine.setReplayCount(replaySeq);
            quarantineMapper.updateById(quarantine);

            writeReplayLog(quarantineId, replaySeq, operator, CcConstants.REPLAY_SUCCESS,
                    String.format("重放成功：入账 %d 点，原因=%s", pointsPosted, req.getReplayReason()),
                    pointsPosted);

            opLogService.log(CcConstants.ENT_DEVICE_REPLAY, quarantineId, quarantine.getBatchId(),
                    CcConstants.ACT_DEVICE_REPLAY,
                    String.format("隔离数据重放成功：隔离#%d，第%d次重放，入账%d点，操作者=%s，原因=%s",
                            quarantineId, replaySeq, pointsPosted, operator, req.getReplayReason()));
        } else {
            // 重放仍失败（如证书仍有问题）
            String failDetail = ingestResult.get("reasonDetail") == null ? "未知原因"
                    : String.valueOf(ingestResult.get("reasonDetail"));
            writeReplayLog(quarantineId, replaySeq, operator, CcConstants.REPLAY_FAILED,
                    "重放仍失败: " + failDetail, 0);
            incrementReplayCount(quarantine);

            opLogService.log(CcConstants.ENT_DEVICE_REPLAY, quarantineId, quarantine.getBatchId(),
                    CcConstants.ACT_DEVICE_REPLAY,
                    String.format("隔离数据重放失败：隔离#%d，第%d次重放，原因=%s，操作者=%s",
                            quarantineId, replaySeq, failDetail, operator));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quarantineId", quarantineId);
        result.put("replaySeq", replaySeq);
        result.put("status", quarantine.getStatus());
        result.put("ingestResult", ingestResult);
        return result;
    }

    /** 查询重放审计记录 */
    public List<DeviceReplayLog> listReplayLogs(Long quarantineId) {
        return replayLogMapper.selectList(new QueryWrapper<DeviceReplayLog>()
                .eq("quarantine_id", quarantineId)
                .orderByAsc("replay_seq"));
    }

    private void writeReplayLog(Long quarantineId, int replaySeq, String operator,
                                String result, String detail, int pointsPosted) {
        DeviceReplayLog log = new DeviceReplayLog();
        log.setQuarantineId(quarantineId);
        log.setReplaySeq(replaySeq);
        log.setReplayBy(operator);
        log.setReplayTime(LocalDateTime.now());
        log.setResult(result);
        log.setResultDetail(detail);
        log.setPointsPosted(pointsPosted);
        replayLogMapper.insert(log);
    }

    private void incrementReplayCount(DeviceQuarantine quarantine) {
        quarantine.setReplayCount(quarantine.getReplayCount() + 1);
        quarantineMapper.updateById(quarantine);
    }
}
