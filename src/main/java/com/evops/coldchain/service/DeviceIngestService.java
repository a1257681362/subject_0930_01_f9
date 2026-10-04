package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DeviceIngestRequest;
import com.evops.coldchain.entity.AlertStreak;
import com.evops.coldchain.entity.BatchAlertZoneSnap;
import com.evops.coldchain.entity.DeviceIngestBatch;
import com.evops.coldchain.entity.DeviceQuarantine;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.TempDedup;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.DeviceIngestBatchMapper;
import com.evops.coldchain.mapper.DeviceQuarantineMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.TempDedupMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车载设备温度数据接入服务（V9）。
 *
 * 核心流程：
 * 1. 幂等检查：同 requestId 重复请求直接返回第一次结果；不同参数拒绝。
 * 2. 签名验证：按登记证书重算 HMAC-SHA256 并比对；失败进隔离队列。
 * 3. 批次校验：运输批次存在且状态允许上报。
 * 4. 按设备序列+采样时间去重：(device_serial, record_time) 唯一，重复点跳过。
 * 5. 补传保护：已审核窗口内的补传点标记 alert_exempt=TRUE，不参与预警评估。
 * 6. 入账：新点插入温度留痕，运行预警评估（非 exempt 点）。
 * 7. 登记结果：数据批次状态 POSTED，返回入账/重复/保护计数。
 */
@Service
public class DeviceIngestService {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    @Resource
    private DeviceIngestBatchMapper ingestBatchMapper;
    @Resource
    private DeviceQuarantineMapper quarantineMapper;
    @Resource
    private TempDedupMapper dedupMapper;
    @Resource
    private TemperatureLogMapper temperatureMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private DeviceCertService certService;
    @Resource
    private TransportBatchService batchService;
    @Resource
    private AlertEvaluationService alertEvaluationService;
    @Resource
    private RouteService routeService;
    @Resource
    private OpLogService opLogService;

    /**
     * 设备温度批量上报入口。
     * 幂等：同 requestId 重复请求返回第一次结果（replay=true）；不同参数拒绝。
     */
    @Transactional
    public Map<String, Object> ingest(DeviceIngestRequest req) {
        // 1) 幂等快路径：同 requestId 已处理过
        DeviceIngestBatch existing = ingestBatchMapper.selectOne(
                new QueryWrapper<DeviceIngestBatch>().eq("request_id", req.getRequestId()));
        if (existing != null) {
            return replayResult(existing, req);
        }

        // 2) 构建规范化载荷 + 摘要
        String canonicalPayload = DeviceCertService.buildCanonicalPayload(
                req.getDeviceSerial(), req.getBatchId(), req.getPoints());
        String payloadHash = DeviceCertService.sha256Hex(canonicalPayload);

        // 3) 签名验证（证书 + 签名）
        DeviceCertService.VerifyResult verifyFail = certService.verifySignature(
                req.getDeviceSerial(), req.getCertFingerprint(), req.getSignature(), canonicalPayload);
        if (verifyFail != null) {
            return quarantine(req, payloadHash, verifyFail.getReasonCode(), verifyFail.getReasonDetail());
        }

        // 4) 批次校验
        TransportBatch batch = batchMapper.selectById(req.getBatchId());
        if (batch == null) {
            return quarantine(req, payloadHash, CcConstants.REASON_BATCH_INVALID,
                    "运输批次不存在: " + req.getBatchId());
        }
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            return quarantine(req, payloadHash, CcConstants.REASON_BATCH_INVALID,
                    "批次已撤回作废，不能接收设备数据: " + batch.getBatchNo());
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            return quarantine(req, payloadHash, CcConstants.REASON_BATCH_INVALID,
                    "批次已签收闭环，不能接收设备数据: " + batch.getBatchNo());
        }

        // 5) 登记数据批次（占幂等键）
        DeviceIngestBatch ingestBatch = new DeviceIngestBatch();
        ingestBatch.setRequestId(req.getRequestId());
        ingestBatch.setDeviceSerial(req.getDeviceSerial());
        ingestBatch.setBatchId(req.getBatchId());
        ingestBatch.setCertFingerprint(req.getCertFingerprint());
        ingestBatch.setSignature(req.getSignature());
        ingestBatch.setPayloadHash(payloadHash);
        ingestBatch.setPointCount(req.getPoints().size());
        try {
            ingestBatchMapper.insert(ingestBatch);
        } catch (DuplicateKeyException dup) {
            // 并发兜底：同 requestId 已被其他线程处理
            DeviceIngestBatch winner = ingestBatchMapper.selectOne(
                    new QueryWrapper<DeviceIngestBatch>().eq("request_id", req.getRequestId()));
            if (winner != null) {
                return replayResult(winner, req);
            }
            throw new BusinessException("数据批次处理冲突，请按原 requestId 重试");
        }

        // 6) 补传保护：查找已审核关闭的温度异常，确定保护截止时间
        LocalDateTime reviewedCutoff = findReviewedCutoff(batch.getId());

        // 7) 逐点处理：去重 + 入账 + 预警评估
        int accepted = 0;
        int duplicated = 0;
        int suppressed = 0;
        List<Map<String, Object>> pointResults = new ArrayList<>();

        List<RouteSegmentSnap> snaps = batch.getRouteVersionId() == null
                ? java.util.Collections.emptyList()
                : routeService.listSnapshotsByBatch(batch.getId());

        for (DeviceIngestRequest.TempPoint point : req.getPoints()) {
            // 去重检查
            TempDedup existingDedup = dedupMapper.selectOne(new QueryWrapper<TempDedup>()
                    .eq("device_serial", req.getDeviceSerial())
                    .eq("record_time", point.getRecordTime()));
            if (existingDedup != null) {
                duplicated++;
                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("recordTime", point.getRecordTime());
                pr.put("status", "DUPLICATE");
                pr.put("logId", existingDedup.getLogId());
                pointResults.add(pr);
                continue;
            }

            // 补传保护：已审核窗口内的点标记 exempt
            boolean exempt = reviewedCutoff != null && !point.getRecordTime().isAfter(reviewedCutoff);
            if (exempt) {
                suppressed++;
            }

            // 确定温区（路线分段或批次快照）
            BigDecimal min;
            BigDecimal max;
            Integer bufferMinutes = null;
            RouteSegmentSnap hit = null;
            if (!snaps.isEmpty() && point.getMileageKm() != null) {
                hit = matchSegment(snaps, point.getMileageKm());
                if (hit != null) {
                    min = hit.getTempMin();
                    max = hit.getTempMax();
                    bufferMinutes = hit.getBufferMinutes();
                } else {
                    min = batch.getTempMinSnap();
                    max = batch.getTempMaxSnap();
                }
            } else {
                min = batch.getTempMinSnap();
                max = batch.getTempMaxSnap();
            }

            boolean inRange = point.getTemperature().compareTo(min) >= 0
                    && point.getTemperature().compareTo(max) <= 0;

            // 插入温度留痕
            TemperatureLog log = new TemperatureLog();
            log.setBatchId(batch.getId());
            log.setRecordTime(point.getRecordTime());
            log.setTemperature(point.getTemperature());
            log.setInRange(inRange);
            log.setRecordedBy("device:" + req.getDeviceSerial());
            log.setRemark(point.getRemark());
            log.setMileageKm(point.getMileageKm());
            log.setSegmentSeq(hit == null ? null : hit.getSegmentSeq());
            log.setProbeId(batch.getProbeId());
            log.setSource(CcConstants.TEMP_SOURCE_BACKFILL);
            log.setIngestBatchId(ingestBatch.getId());
            log.setAlertExempt(exempt);
            temperatureMapper.insert(log);

            // 登记去重
            TempDedup dedup = new TempDedup();
            dedup.setDeviceSerial(req.getDeviceSerial());
            dedup.setRecordTime(point.getRecordTime());
            dedup.setLogId(log.getId());
            dedup.setIngestBatchId(ingestBatch.getId());
            try {
                dedupMapper.insert(dedup);
            } catch (DuplicateKeyException dup) {
                // 并发兜底：同 (device_serial, record_time) 已被入账
                duplicated++;
                continue;
            }

            // 预警评估（非 exempt 点）
            if (!exempt) {
                evaluatePoint(batch, log, hit, min, max, bufferMinutes, inRange);
            }

            accepted++;
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("recordTime", point.getRecordTime());
            pr.put("status", exempt ? "SUPPRESSED" : "ACCEPTED");
            pr.put("logId", log.getId());
            pr.put("inRange", inRange);
            pointResults.add(pr);
        }

        // 8) 更新数据批次结果
        ingestBatch.setAcceptedCount(accepted);
        ingestBatch.setDuplicateCount(duplicated);
        ingestBatch.setSuppressedCount(suppressed);
        ingestBatch.setStatus(CcConstants.INGEST_POSTED);
        ingestBatch.setPostedTime(LocalDateTime.now());
        ingestBatchMapper.updateById(ingestBatch);

        opLogService.log(CcConstants.ENT_DEVICE_INGEST, ingestBatch.getId(), batch.getId(),
                CcConstants.ACT_DEVICE_INGEST,
                String.format("设备数据批次入账：requestId=%s，设备=%s，批次=%s，总点数=%d，入账=%d，重复=%d，补传保护=%d",
                        req.getRequestId(), req.getDeviceSerial(), batch.getBatchNo(),
                        req.getPoints().size(), accepted, duplicated, suppressed));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ingestBatchId", ingestBatch.getId());
        result.put("requestId", req.getRequestId());
        result.put("status", CcConstants.INGEST_POSTED);
        result.put("pointCount", req.getPoints().size());
        result.put("acceptedCount", accepted);
        result.put("duplicateCount", duplicated);
        result.put("suppressedCount", suppressed);
        result.put("replay", false);
        result.put("points", pointResults);
        return result;
    }

    /** 幂等回放：同 requestId 返回第一次结果；不同参数拒绝 */
    private Map<String, Object> replayResult(DeviceIngestBatch existing, DeviceIngestRequest req) {
        String canonicalPayload = DeviceCertService.buildCanonicalPayload(
                req.getDeviceSerial(), req.getBatchId(), req.getPoints());
        String payloadHash = DeviceCertService.sha256Hex(canonicalPayload);
        if (!existing.getPayloadHash().equals(payloadHash)) {
            throw new BusinessException("requestId=" + req.getRequestId()
                    + " 已用于另一批数据（内容摘要不匹配），不能复用");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ingestBatchId", existing.getId());
        result.put("requestId", existing.getRequestId());
        result.put("status", existing.getStatus());
        result.put("pointCount", existing.getPointCount());
        result.put("acceptedCount", existing.getAcceptedCount());
        result.put("duplicateCount", existing.getDuplicateCount());
        result.put("suppressedCount", existing.getSuppressedCount());
        result.put("replay", true);
        return result;
    }

    /** 验证失败 → 隔离队列 */
    private Map<String, Object> quarantine(DeviceIngestRequest req, String payloadHash,
                                           String reasonCode, String reasonDetail) {
        // 登记数据批次（隔离状态）
        DeviceIngestBatch ingestBatch = new DeviceIngestBatch();
        ingestBatch.setRequestId(req.getRequestId());
        ingestBatch.setDeviceSerial(req.getDeviceSerial());
        ingestBatch.setBatchId(req.getBatchId());
        ingestBatch.setCertFingerprint(req.getCertFingerprint());
        ingestBatch.setSignature(req.getSignature());
        ingestBatch.setPayloadHash(payloadHash);
        ingestBatch.setPointCount(req.getPoints().size());
        ingestBatch.setStatus(CcConstants.INGEST_QUARANTINED);
        ingestBatch.setFailReason(reasonDetail);
        try {
            ingestBatchMapper.insert(ingestBatch);
        } catch (DuplicateKeyException dup) {
            DeviceIngestBatch winner = ingestBatchMapper.selectOne(
                    new QueryWrapper<DeviceIngestBatch>().eq("request_id", req.getRequestId()));
            if (winner != null) {
                return replayResult(winner, req);
            }
            throw new BusinessException("数据批次处理冲突，请按原 requestId 重试");
        }

        // 进入隔离队列
        DeviceQuarantine quarantine = new DeviceQuarantine();
        quarantine.setIngestBatchId(ingestBatch.getId());
        quarantine.setDeviceSerial(req.getDeviceSerial());
        quarantine.setBatchId(req.getBatchId());
        quarantine.setReasonCode(reasonCode);
        quarantine.setReasonDetail(reasonDetail);
        try {
            quarantine.setPayloadSnapshot(M.writeValueAsString(req));
        } catch (Exception e) {
            quarantine.setPayloadSnapshot("{}");
        }
        quarantine.setStatus(CcConstants.QUARANTINE_PENDING);
        quarantine.setReplayCount(0);
        quarantineMapper.insert(quarantine);

        opLogService.log(CcConstants.ENT_DEVICE_QUARANTINE, quarantine.getId(), req.getBatchId(),
                CcConstants.ACT_DEVICE_QUARANTINE,
                String.format("设备数据批次隔离：requestId=%s，设备=%s，原因=%s（%s）",
                        req.getRequestId(), req.getDeviceSerial(), reasonCode, reasonDetail));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ingestBatchId", ingestBatch.getId());
        result.put("quarantineId", quarantine.getId());
        result.put("requestId", req.getRequestId());
        result.put("status", CcConstants.INGEST_QUARANTINED);
        result.put("reasonCode", reasonCode);
        result.put("reasonDetail", reasonDetail);
        result.put("replay", false);
        return result;
    }

    /** 查找已审核关闭的温度异常的最大处理时间（补传保护截止） */
    private LocalDateTime findReviewedCutoff(Long batchId) {
        List<ExceptionEvent> resolved = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batchId)
                .eq("exception_type", CcConstants.EXC_TEMP)
                .eq("status", CcConstants.EXC_RESOLVED)
                .eq("review_status", CcConstants.REVIEW_APPROVED)
                .isNotNull("resolve_time")
                .orderByDesc("resolve_time")
                .last("LIMIT 1"));
        return resolved.isEmpty() ? null : resolved.get(0).getResolveTime();
    }

    /** 预警评估（与 TransportBatchService.reportTemperature 同一口径） */
    private void evaluatePoint(TransportBatch batch, TemperatureLog log,
                               RouteSegmentSnap hit, BigDecimal min, BigDecimal max,
                               Integer bufferMinutes, boolean inRange) {
        // V5 预警模板批次：走预警引擎
        if (alertEvaluationService.getBatchZone(batch.getId(), batch.getAlertZoneCode()) != null) {
            alertEvaluationService.onTemperatureReported(batch, log);
            return;
        }
        // V1/V2 缓冲判定
        if (!inRange) {
            boolean bufferExceeded = bufferMinutes == null
                    || batchService.isContinuousExcursionBeyondBuffer(batch.getId(), log, hit, bufferMinutes);
            if (bufferExceeded) {
                String desc = String.format(
                        "系统自动标记（设备补传）：%s 测温 %s℃ 超出温区 %s~%s℃",
                        log.getRecordTime(), log.getTemperature(), min, max);
                batchService.registerTempException(batch, desc, log.getRecordTime(),
                        hit == null ? null : hit.getSegmentSeq(), null);
            }
        }
    }

    /** 按里程命中分段 [fromKm, toKm)（与 TransportBatchService 同逻辑） */
    private RouteSegmentSnap matchSegment(List<RouteSegmentSnap> snaps, BigDecimal mileage) {
        if (mileage.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        for (RouteSegmentSnap s : snaps) {
            boolean geFrom = mileage.compareTo(s.getFromKm()) >= 0;
            boolean ltTo = mileage.compareTo(s.getToKm()) < 0;
            boolean atEnd = mileage.compareTo(s.getToKm()) == 0 && s.getSegmentSeq().equals(snaps.size());
            if (geFrom && (ltTo || atEnd)) {
                return s;
            }
        }
        return null;
    }

    // ---------------- 查询 ----------------

    public List<DeviceIngestBatch> listIngestBatches(Long batchId) {
        QueryWrapper<DeviceIngestBatch> qw = new QueryWrapper<DeviceIngestBatch>().orderByDesc("id");
        if (batchId != null) {
            qw.eq("batch_id", batchId);
        }
        return ingestBatchMapper.selectList(qw);
    }

    public DeviceIngestBatch requireIngestBatch(Long id) {
        DeviceIngestBatch batch = ingestBatchMapper.selectById(id);
        if (batch == null) {
            throw new BusinessException("数据批次不存在: " + id);
        }
        return batch;
    }

    public List<DeviceQuarantine> listQuarantine(String status) {
        QueryWrapper<DeviceQuarantine> qw = new QueryWrapper<DeviceQuarantine>().orderByDesc("id");
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        return quarantineMapper.selectList(qw);
    }

    public DeviceQuarantine requireQuarantine(Long id) {
        DeviceQuarantine q = quarantineMapper.selectById(id);
        if (q == null) {
            throw new BusinessException("隔离队列条目不存在: " + id);
        }
        return q;
    }
}
