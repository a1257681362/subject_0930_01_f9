package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DeviceManualIngestRequest;
import com.evops.coldchain.dto.DeviceTempIngestRequest;
import com.evops.coldchain.dto.DeviceTempPoint;
import com.evops.coldchain.entity.Device;
import com.evops.coldchain.entity.DeviceIngestBatch;
import com.evops.coldchain.entity.DeviceReplayAudit;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.TempBatchQuarantine;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.DeviceIngestBatchMapper;
import com.evops.coldchain.mapper.DeviceMapper;
import com.evops.coldchain.mapper.DeviceReplayAuditMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TempBatchQuarantineMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 车载设备温度数据入库核心（V9）。
 *
 * 一条可用结果：设备断网补传、人工补录、失败重试三条路径最终都汇入
 * {@link #ingestCanonical}；测点按“设备序列号 + 采样时间”数据库唯一约束去重，
 * 任何路径对同一测点只产生一条 {@link TemperatureLog}，预警/异常按业务键确定性去重，
 * 不会因补传/补录/重试重复登记或改变既有审核结论。
 *
 * 验签失败：整批原样进入隔离队列（不做部分入账），返回可读原因码+说明；
 * 运维仅能对 QUARANTINED 行重放，RELEASED 拒绝重放，每次尝试写审计行。
 */
@Service
public class DeviceTempIngestService {

    /** 已入账批次状态之外的批次测点只留痕不评估 */
    private static final List<String> EVALUABLE_STATUS = Arrays.asList(
            CcConstants.BATCH_DISPATCHED, CcConstants.BATCH_SHIPPED, CcConstants.BATCH_EXCEPTION);

    @Resource
    private DeviceMapper deviceMapper;
    @Resource
    private TempBatchQuarantineMapper quarantineMapper;
    @Resource
    private DeviceIngestBatchMapper ingestMapper;
    @Resource
    private DeviceReplayAuditMapper replayAuditMapper;
    @Resource
    private TemperatureLogMapper temperatureMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private RouteService routeService;
    @Resource
    private AlertEvaluationService alertEvaluationService;
    @Resource
    private ExceptionLifecycleService lifecycle;
    @Resource
    private OpLogService opLogService;

    /** 设备签名报文接入入口（设备侧免登录）：验签失败隔离，成功幂等入账 */
    @Transactional
    public IngestResult receiveSigned(DeviceTempIngestRequest req) {
        return receiveSigned(req, true);
    }

    /**
     * @param releasePriorQuarantine 是否在首次成功入账后自动释放同 clientBatchNo 的历史隔离单。
     *                              设备自行重发/人工补录=true；运维显式重放=false（由重放方法统一记释放与审计）。
     */
    private IngestResult receiveSigned(DeviceTempIngestRequest req, boolean releasePriorQuarantine) {
        String canonical = DeviceSignatureService.canonicalPayload(
                req.getClientBatchNo(), req.getDeviceSerial(), req.getPoints());
        String payloadSha = DeviceSignatureService.payloadSha256(canonical);

        // 1) 证书解析（失败即隔离，原因码可读）
        DeviceSignatureService.ParsedCertificate cert;
        try {
            cert = DeviceSignatureService.parse(req.getCertPem());
        } catch (IllegalArgumentException e) {
            return quarantine(req, payloadSha, DeviceRejectReason.CERT_PARSE_ERROR,
                    DeviceRejectReason.describe(DeviceRejectReason.CERT_PARSE_ERROR) + "：" + e.getMessage());
        }

        // 2) 证书必须已备案（信任锚=平台登记的设备证书）
        Device device = findByFingerprint(cert.getFingerprintSha256());
        if (device == null) {
            return quarantine(req, payloadSha, DeviceRejectReason.CERT_NOT_REGISTERED,
                    DeviceRejectReason.describe(DeviceRejectReason.CERT_NOT_REGISTERED)
                            + "（证书指纹=" + cert.getFingerprintSha256() + "）");
        }

        // 3) 报文声称的设备序列必须与备案设备一致（防止用 A 设备证书传 B 序列数据）
        if (!device.getDeviceSerial().equals(req.getDeviceSerial().trim())) {
            return quarantine(req, payloadSha, DeviceRejectReason.DEVICE_SERIAL_MISMATCH,
                    DeviceRejectReason.describe(DeviceRejectReason.DEVICE_SERIAL_MISMATCH)
                            + "：报文设备序列=" + req.getDeviceSerial() + "，证书备案设备=" + device.getDeviceSerial());
        }

        // 4) 设备状态与证书有效期
        if (CcConstants.DEVICE_REVOKED.equals(device.getStatus())) {
            return quarantine(req, payloadSha, DeviceRejectReason.DEVICE_REVOKED,
                    DeviceRejectReason.describe(DeviceRejectReason.DEVICE_REVOKED)
                            + "（设备=" + device.getDeviceSerial() + "，作废时间=" + device.getRevokeTime() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(cert.getNotBefore()) || now.isAfter(cert.getNotAfter())) {
            return quarantine(req, payloadSha, DeviceRejectReason.CERT_EXPIRED,
                    DeviceRejectReason.describe(DeviceRejectReason.CERT_EXPIRED)
                            + "（有效期 " + cert.getNotBefore() + " ~ " + cert.getNotAfter() + "）");
        }

        // 5) 验签（公钥来自随报证书；指纹已与备案一致，故等价于备案公钥）
        if (!DeviceSignatureService.verify(cert.getPublicKey(), canonical, req.getSignature())) {
            return quarantine(req, payloadSha, DeviceRejectReason.SIGNATURE_INVALID,
                    DeviceRejectReason.describe(DeviceRejectReason.SIGNATURE_INVALID)
                            + "（设备=" + device.getDeviceSerial() + "，批次=" + req.getClientBatchNo() + "）");
        }

        // 6) 验签通过 → 幂等入账
        try {
            IngestResult result = ingestCanonical(req.getClientBatchNo(), device, req.getPoints(),
                    canonical, payloadSha, cert.getFingerprintSha256(), CcConstants.TEMP_SOURCE_DEVICE,
                    releasePriorQuarantine);
            result.setQuarantined(false);
            return result;
        } catch (BatchPayloadChangedException e) {
            // 同 clientBatchNo 换载荷：不沿用旧批次的合法结果，按隔离留痕并返回可读错误
            return quarantine(req, payloadSha, DeviceRejectReason.BATCH_PAYLOAD_CHANGED, e.getMessage());
        }
    }

    /** 人工补录入口（需 coldchain:device:manual 权限）：设备须在册，与设备上报同一套去重/对账 */
    @Transactional
    public IngestResult receiveManual(DeviceManualIngestRequest req) {
        Device device = deviceMapper.selectOne(new QueryWrapper<Device>()
                .eq("device_serial", req.getDeviceSerial().trim()).last("LIMIT 1"));
        if (device == null) {
            throw new BusinessException("[" + DeviceRejectReason.DEVICE_NOT_FOUND + "] "
                    + DeviceRejectReason.describe(DeviceRejectReason.DEVICE_NOT_FOUND)
                    + "：" + req.getDeviceSerial());
        }
        String batchNo = req.getClientBatchNo();
        boolean generated = false;
        if (batchNo == null || batchNo.trim().isEmpty()) {
            batchNo = "MANUAL-" + System.nanoTime();
            generated = true;
        }
        String canonical = DeviceSignatureService.canonicalPayload(
                batchNo, req.getDeviceSerial().trim(), req.getPoints());
        String payloadSha = DeviceSignatureService.payloadSha256(canonical);
        try {
            // 人工补录无设备签名，不能据此释放“签名失败”的隔离单（须设备修好后重发或运维重放）
            IngestResult result = ingestCanonical(batchNo, device, req.getPoints(),
                    canonical, payloadSha, null, CcConstants.TEMP_SOURCE_MANUAL, false);
            result.setQuarantined(false);
            result.setClientBatchNoGenerated(generated);
            return result;
        } catch (BatchPayloadChangedException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    /**
     * 统一入账（补传/补录/重放共用）：
     * 1) client_batch_no 幂等（重发回首次结果，换摘要拒绝）；
     * 2) 逐点按设备序列+采样时间去重落温度留痕（唯一约束兜底并发）；
     * 3) 新测点归属到设备安装车辆在采样时刻处于在途窗口的批次；
     * 4) 对受影响的活动批次做一次确定性对账（V5 重放 / V1/V2 按段临界时间去重）；
     *    已签收/已撤回批次的补传只留痕，不重开任何结论。
     */
    private IngestResult ingestCanonical(String clientBatchNo, Device device, List<DeviceTempPoint> points,
                                         String canonical, String payloadSha, String certFingerprint,
                                         String sourceType, boolean releasePriorQuarantine) {
        // —— 幂等快路径：同批次号已成功入账，必须同载荷，直接回第一次结果 ——
        DeviceIngestBatch existing = ingestMapper.selectOne(new QueryWrapper<DeviceIngestBatch>()
                .eq("client_batch_no", clientBatchNo).last("LIMIT 1"));
        if (existing != null) {
            if (!existing.getPayloadSha256().equals(payloadSha)) {
                throw new BatchPayloadChangedException(String.format(
                        "设备侧批次号 %s 已提交过，但本次报文摘要(%s)与首次(%s)不一致，%s",
                        clientBatchNo, payloadSha, existing.getPayloadSha256(),
                        DeviceRejectReason.describe(DeviceRejectReason.BATCH_PAYLOAD_CHANGED)));
            }
            return IngestResult.replay(existing);
        }

        String deviceSerial = device.getDeviceSerial();
        int inserted = 0;
        int duplicate = 0;
        int unmatched = 0;
        Set<Long> affectedBatchIds = new LinkedHashSet<>();

        // 按采样时间排序后逐点处理，保证同批乱序（断网补传常见）时归属与判定确定
        List<DeviceTempPoint> ordered = new ArrayList<>(points);
        ordered.sort((a, b) -> {
            int c = a.getSampleTime().compareTo(b.getSampleTime());
            return c != 0 ? c : a.getTemperature().compareTo(b.getTemperature());
        });

        for (DeviceTempPoint p : ordered) {
            // 去重预检：设备序列+采样时间已存在即视为同一点位的补传/重试，只保留第一条结果
            if (pointExists(deviceSerial, p.getSampleTime())) {
                duplicate++;
                continue;
            }

            TransportBatch batch = resolveBatchAt(device, p.getSampleTime());
            if (batch == null) {
                // 采样时刻车辆无在途窗口覆盖的批次：测点不可归属（报文原文仍在批次结果中留存）
                unmatched++;
                continue;
            }

            TemperatureLog log = buildTemperatureLog(batch, device, p, clientBatchNo, sourceType);
            try {
                temperatureMapper.insert(log);
            } catch (DuplicateKeyException dup) {
                // 并发/重试兜底：设备序列+采样时间唯一约束
                duplicate++;
                continue;
            }
            inserted++;
            affectedBatchIds.add(batch.getId());

            opLogService.log(CcConstants.ENT_TEMPERATURE, log.getId(), batch.getId(), CcConstants.ACT_TEMP_REPORT,
                    String.format("批次 %s 设备温度%s入账：设备=%s，采样时间=%s，温度=%s℃（%s，%s；来源=%s，报文批次=%s）",
                            batch.getBatchNo(), sourceType, deviceSerial, p.getSampleTime(), p.getTemperature(),
                            log.getSegmentSeq() == null ? "快照温区" : ("第" + log.getSegmentSeq() + "段"),
                            Boolean.TRUE.equals(log.getInRange()) ? "正常" : "越限",
                            sourceType, clientBatchNo));
        }

        // —— 确定性对账：补传历史测点后，仅对受影响的“活动批次”重算；已审核结论不改变 ——
        for (Long batchId : affectedBatchIds) {
            TransportBatch batch = batchMapper.selectById(batchId);
            if (batch != null && EVALUABLE_STATUS.contains(batch.getStatus())) {
                reconcileBatch(batch);
            }
            // SIGNED/REVOKED：补传测点只追加留痕，不重新评估、不重开预警/异常/审核结论
        }

        DeviceIngestBatch record = new DeviceIngestBatch();
        record.setClientBatchNo(clientBatchNo);
        record.setDeviceSerial(deviceSerial);
        record.setPayload(canonical);
        record.setPayloadSha256(payloadSha);
        record.setCertFingerprintSha256(certFingerprint);
        record.setPointCount(points.size());
        record.setInsertedCount(inserted);
        record.setDuplicateCount(duplicate);
        record.setUnmatchedCount(unmatched);
        record.setAffectedBatchIds(joinIds(affectedBatchIds));
        record.setSourceType(sourceType);
        record.setReceivedTime(LocalDateTime.now());
        try {
            ingestMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下同 clientBatchNo 已落账：回首次结果（摘要一致才可能走到这里）
            DeviceIngestBatch winner = ingestMapper.selectOne(new QueryWrapper<DeviceIngestBatch>()
                    .eq("client_batch_no", clientBatchNo).last("LIMIT 1"));
            if (winner != null && winner.getPayloadSha256().equals(payloadSha)) {
                return IngestResult.replay(winner);
            }
            throw new BusinessException("设备报文批次处理冲突，请按原 clientBatchNo 重试");
        }

        opLogService.log(CcConstants.ENT_DEVICE_INGEST, record.getId(), null,
                CcConstants.TEMP_SOURCE_MANUAL.equals(sourceType)
                        ? CcConstants.ACT_DEVICE_MANUAL : CcConstants.ACT_DEVICE_INGEST,
                String.format("设备温度%s批次 %s 处理完成：设备=%s，测点=%d，新入账=%d，去重=%d，不可归属=%d；"
                                + "完成确定性对账的批次=[%s]（已签收/已撤回批次补传只留痕，审核结论不变）",
                        sourceType, clientBatchNo, deviceSerial, points.size(),
                        inserted, duplicate, unmatched, record.getAffectedBatchIds()));

        // 首次成功入账：若该批次此前验签失败进过隔离队列（设备修好后自行重发成功），
        // 自动释放对应隔离单并写 SUCCESS 审计，避免隔离队列残留“实际已由设备重发入账”的假阳性。
        // 人工补录（无签名）与运维显式重放不触发此联动；幂等回放（replay）也不进入这里；
        // 换载荷报文在入口即被拦截，不会走到本分支。
        if (releasePriorQuarantine) {
            autoReleasePriorQuarantine(clientBatchNo, record);
        }

        return IngestResult.created(record);
    }

    /**
     * 首次验签通过入账后，联动释放同 clientBatchNo 的历史未入账隔离单：
     * 置 RELEASED 并指向入账结果ID，追加一行 SUCCESS 重放审计与释放留痕。
     */
    private void autoReleasePriorQuarantine(String clientBatchNo, DeviceIngestBatch record) {
        TempBatchQuarantine prior = quarantineMapper.selectOne(new QueryWrapper<TempBatchQuarantine>()
                .eq("client_batch_no", clientBatchNo).last("LIMIT 1"));
        if (prior == null || !CcConstants.QUARANTINED.equals(prior.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        prior.setStatus(CcConstants.QUARANTINE_RELEASED);
        prior.setReleasedTime(now);
        prior.setReleasedBy(UserContext.currentId());
        prior.setReleasedIngestId(record.getId());
        prior.setReplayCount((prior.getReplayCount() == null ? 0 : prior.getReplayCount()) + 1);
        quarantineMapper.updateById(prior);

        IngestResult justIn = IngestResult.created(record);
        DeviceReplayAudit audit = buildAudit(prior, CcConstants.REPLAY_SUCCESS, null,
                "设备/人工重发验签通过并入账，历史隔离单自动释放", justIn, UserContext.currentName(), now);
        replayAuditMapper.insert(audit);

        opLogService.log(CcConstants.ENT_QUARANTINE, prior.getId(), null, CcConstants.ACT_QUARANTINE_RELEASE,
                String.format("隔离单 %s 随批次 %s 验签通过入账自动释放：入账结果ID=%d，新入账=%d，去重=%d，不可归属=%d；操作人=%s",
                        prior.getQueueNo(), clientBatchNo, record.getId(),
                        record.getInsertedCount(), record.getDuplicateCount(), record.getUnmatchedCount(),
                        UserContext.currentName()));
    }

    /**
     * 批次级确定性对账（补传后重算，绝不改变已审核结论）：
     * - V5：批次有类别预警模板快照 → {@link AlertEvaluationService#rebuildBatch}
     *   （streak 过程状态可重建，预警按 批次×温区×起点 去重 + 唯一约束兜底，异常登记随预警去重）；
     * - 否则按 V1/V2 口径：单温区或路线分段，沿全部温度点按采样时间重放缓冲判定，
     *   仅在此前没有任何同段（或同批单温区）温度异常时才可能登记一条，临界时间=本次点时间；
     *   已存在异常（OPEN/CONFIRMED/REVIEWING/RESOLVED，含已 QA 复核关闭）一律不重复登记、不改状态。
     */
    private void reconcileBatch(TransportBatch batch) {
        if (alertEvaluationService.getBatchZone(batch.getId(), batch.getAlertZoneCode()) != null) {
            alertEvaluationService.rebuildBatch(batch.getId());
            return;
        }
        reconcileLegacy(batch);
    }

    private void reconcileLegacy(TransportBatch batch) {
        List<TemperatureLog> points = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batch.getId())
                .orderByAsc("record_time").orderByAsc("id"));

        List<RouteSegmentSnap> snaps = batch.getRouteVersionId() == null
                ? Collections.emptyList()
                : routeService.listSnapshotsByBatch(batch.getId());

        // 已经存在温度异常的分段不再补登记（既有结论——含已复核关闭——不被补传改变）
        Set<Integer> segmentsWithException = new LinkedHashSet<>();
        boolean batchLevelExceptionExists = false;
        for (ExceptionEvent e : exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batch.getId()).eq("exception_type", CcConstants.EXC_TEMP))) {
            if (e.getSegmentSeq() == null) {
                batchLevelExceptionExists = true;
            } else {
                segmentsWithException.add(e.getSegmentSeq());
            }
        }

        // 逐点重放缓冲判定：记录每个分段（单温区用 null 键）当前连续越限起点
        Map<Integer, LocalDateTime> streakStart = new LinkedHashMap<>();
        for (TemperatureLog p : points) {
            if (Boolean.TRUE.equals(p.getInRange())) {
                streakStart.remove(keyOf(p.getSegmentSeq()));
                continue;
            }
            Integer key = keyOf(p.getSegmentSeq());
            if (!streakStart.containsKey(key)) {
                streakStart.put(key, p.getRecordTime());
            }
            Integer buffer = bufferMinutesOf(snaps, p.getSegmentSeq());
            long minutes = Duration.between(streakStart.get(key), p.getRecordTime()).toMinutes();
            boolean beyond = buffer == null || minutes > buffer;
            boolean alreadyHasException = p.getSegmentSeq() == null
                    ? batchLevelExceptionExists
                    : segmentsWithException.contains(p.getSegmentSeq());
            if (beyond && !alreadyHasException) {
                registerLegacyException(batch, snaps, p);
                if (p.getSegmentSeq() == null) {
                    batchLevelExceptionExists = true;
                } else {
                    segmentsWithException.add(p.getSegmentSeq());
                }
                streakStart.remove(key); // 该段已登记，后续补传不再开第二条
            }
        }
    }

    private void registerLegacyException(TransportBatch batch, List<RouteSegmentSnap> snaps, TemperatureLog critical) {
        BigDecimal min;
        BigDecimal max;
        RouteSegmentSnap hit = null;
        if (!snaps.isEmpty() && critical.getSegmentSeq() != null) {
            for (RouteSegmentSnap s : snaps) {
                if (s.getSegmentSeq().equals(critical.getSegmentSeq())) {
                    hit = s;
                    break;
                }
            }
        }
        String desc;
        if (hit == null) {
            min = batch.getTempMinSnap();
            max = batch.getTempMaxSnap();
            desc = String.format("设备补传数据对账自动标记：%s 测温 %s℃ 超出快照温区 %s~%s℃（断网补传按采样时间确定性重放）",
                    critical.getRecordTime(), critical.getTemperature(), min, max);
        } else {
            min = hit.getTempMin();
            max = hit.getTempMax();
            desc = String.format("设备补传数据对账自动标记：%s 里程 %skm 第%d段（责任人=%s）测温 %s℃ 超出允许温区 %s~%s℃，"
                            + "连续越限已超过缓冲 %d 分钟（断网补传按采样时间确定性重放）",
                    critical.getRecordTime(), critical.getMileageKm(), hit.getSegmentSeq(),
                    hit.getOwnerName(), critical.getTemperature(), min, max, hit.getBufferMinutes());
        }
        // 客观成立即确认启钟（V6）；foundTime=临界采样时刻。补传不覆盖任何已存在异常。
        lifecycle.registerAutoConfirmed(batch, CcConstants.EXC_TEMP, desc,
                critical.getRecordTime(), critical.getSegmentSeq(), null);
    }

    private Integer bufferMinutesOf(List<RouteSegmentSnap> snaps, Integer segmentSeq) {
        if (segmentSeq == null) {
            return null;
        }
        for (RouteSegmentSnap s : snaps) {
            if (s.getSegmentSeq().equals(segmentSeq)) {
                return s.getBufferMinutes();
            }
        }
        return null;
    }

    private Integer keyOf(Integer segmentSeq) {
        return segmentSeq == null ? -1 : segmentSeq;
    }

    private boolean pointExists(String deviceSerial, LocalDateTime sampleTime) {
        Long n = temperatureMapper.selectCount(new QueryWrapper<TemperatureLog>()
                .eq("device_serial", deviceSerial)
                .eq("record_time", sampleTime));
        return n != null && n > 0;
    }

    /**
     * 归属判定：找设备安装车辆在 sampleTime 落在 [派车时间, 签收时间/当前) 的批次；
     * 取采样时刻之前最近派车、且未在该时刻前签收的一条。无则 null（不可归属）。
     */
    private TransportBatch resolveBatchAt(Device device, LocalDateTime sampleTime) {
        if (device.getVehicleId() == null) {
            return null;
        }
        List<TransportBatch> candidates = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .eq("vehicle_id", device.getVehicleId())
                .le("dispatch_time", sampleTime)
                .orderByDesc("dispatch_time"));
        for (TransportBatch b : candidates) {
            if (b.getSignTime() != null && b.getSignTime().isBefore(sampleTime)) {
                continue; // 该批次签收在采样之前
            }
            if (CcConstants.BATCH_REVOKED.equals(b.getStatus())) {
                continue;
            }
            return b;
        }
        return null;
    }

    private TemperatureLog buildTemperatureLog(TransportBatch batch, Device device, DeviceTempPoint p,
                                               String clientBatchNo, String sourceType) {
        List<RouteSegmentSnap> snaps = batch.getRouteVersionId() == null
                ? Collections.emptyList()
                : routeService.listSnapshotsByBatch(batch.getId());
        RouteSegmentSnap hit = null;
        BigDecimal min;
        BigDecimal max;
        if (!snaps.isEmpty()) {
            hit = matchSegment(snaps, p.getMileageKm());
            min = hit == null ? batch.getTempMinSnap() : hit.getTempMin();
            max = hit == null ? batch.getTempMaxSnap() : hit.getTempMax();
        } else {
            min = batch.getTempMinSnap();
            max = batch.getTempMaxSnap();
        }
        boolean inRange = p.getTemperature().compareTo(min) >= 0 && p.getTemperature().compareTo(max) <= 0;

        TemperatureLog log = new TemperatureLog();
        log.setBatchId(batch.getId());
        log.setRecordTime(p.getSampleTime());
        log.setTemperature(p.getTemperature());
        log.setInRange(inRange);
        log.setRecordedBy("DEVICE:" + device.getDeviceSerial());
        log.setRemark((CcConstants.TEMP_SOURCE_MANUAL.equals(sourceType) ? "人工补录" : "设备上报")
                + "，报文批次 " + clientBatchNo);
        log.setMileageKm(p.getMileageKm());
        log.setSegmentSeq(hit == null ? null : hit.getSegmentSeq());
        log.setProbeId(batch.getProbeId());
        log.setSourceType(sourceType);
        log.setDeviceSerial(device.getDeviceSerial());
        log.setClientBatchNo(clientBatchNo);
        return log;
    }

    private RouteSegmentSnap matchSegment(List<RouteSegmentSnap> snaps, BigDecimal mileage) {
        if (mileage == null || mileage.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        for (RouteSegmentSnap s : snaps) {
            boolean geFrom = mileage.compareTo(s.getFromKm()) >= 0;
            boolean ltTo = mileage.compareTo(s.getToKm()) < 0;
            boolean atEnd = mileage.compareTo(s.getToKm()) == 0
                    && s.getSegmentSeq().equals(snaps.size());
            if (geFrom && (ltTo || atEnd)) {
                return s;
            }
        }
        return null;
    }

    // ---------------- 隔离队列 ----------------

    private IngestResult quarantine(DeviceTempIngestRequest req, String payloadSha,
                                    String reasonCode, String reasonDetail) {
        // 同 clientBatchNo 已隔离：幂等回原隔离单（不重复隔离、不占用新单号）
        TempBatchQuarantine exist = quarantineMapper.selectOne(new QueryWrapper<TempBatchQuarantine>()
                .eq("client_batch_no", req.getClientBatchNo()).last("LIMIT 1"));
        if (exist != null) {
            return IngestResult.quarantined(exist);
        }

        String canonical = DeviceSignatureService.canonicalPayload(
                req.getClientBatchNo(), req.getDeviceSerial(), req.getPoints());
        String fingerprint = null;
        try {
            fingerprint = DeviceSignatureService.parse(req.getCertPem()).getFingerprintSha256();
        } catch (Exception ignored) {
            // 证书不可解析时指纹留空，原因码已记录
        }

        TempBatchQuarantine q = new TempBatchQuarantine();
        q.setQueueNo(generateQuarantineNo());
        q.setClientBatchNo(req.getClientBatchNo());
        q.setDeviceSerial(req.getDeviceSerial());
        q.setCertFingerprintSha256(fingerprint);
        q.setPayload(canonical);
        q.setPayloadSha256(payloadSha);
        q.setSignature(req.getSignature());
        q.setCertPem(req.getCertPem());
        q.setPointCount(req.getPoints().size());
        q.setStatus(CcConstants.QUARANTINED);
        q.setReasonCode(reasonCode);
        q.setReasonDetail(truncate(reasonDetail, 1000));
        q.setReceivedTime(LocalDateTime.now());
        q.setReplayCount(0);
        try {
            quarantineMapper.insert(q);
        } catch (DuplicateKeyException e) {
            TempBatchQuarantine winner = quarantineMapper.selectOne(new QueryWrapper<TempBatchQuarantine>()
                    .eq("client_batch_no", req.getClientBatchNo()).last("LIMIT 1"));
            if (winner != null) {
                return IngestResult.quarantined(winner);
            }
            throw new BusinessException("隔离登记冲突，请重试");
        }

        opLogService.log(CcConstants.ENT_QUARANTINE, q.getId(), null, CcConstants.ACT_QUARANTINE_ISOLATE,
                String.format("设备温度报文验签失败整批隔离：隔离单=%s，设备=%s，报文批次=%s，测点数=%d，原因码=%s，说明=%s",
                        q.getQueueNo(), req.getDeviceSerial(), req.getClientBatchNo(),
                        req.getPoints().size(), reasonCode, reasonDetail));
        return IngestResult.quarantined(q);
    }

    public TempBatchQuarantine requireQuarantine(Long id) {
        TempBatchQuarantine q = quarantineMapper.selectById(id);
        if (q == null) {
            throw new BusinessException("隔离记录不存在: " + id);
        }
        return q;
    }

    public List<TempBatchQuarantine> listQuarantine(String status) {
        QueryWrapper<TempBatchQuarantine> wrapper = new QueryWrapper<TempBatchQuarantine>().orderByDesc("id");
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim());
        }
        return quarantineMapper.selectList(wrapper);
    }

    public List<DeviceReplayAudit> listReplayAudits(Long quarantineId) {
        return replayAuditMapper.selectList(new QueryWrapper<DeviceReplayAudit>()
                .eq("quarantine_id", quarantineId).orderByAsc("replay_seq"));
    }

    /**
     * 运维重放：只能重放“未入账”（QUARANTINED）数据。
     * 每一次尝试（成功/失败/拒绝）都追加一行审计；成功后释放隔离单并禁止再次重放。
     */
    @Transactional
    public ReplayOutcome replay(Long quarantineId) {
        TempBatchQuarantine q = requireQuarantine(quarantineId);
        String operator = UserContext.currentName();
        LocalDateTime now = LocalDateTime.now();

        if (CcConstants.QUARANTINE_RELEASED.equals(q.getStatus())) {
            DeviceReplayAudit audit = buildAudit(q, CcConstants.REPLAY_REJECTED,
                    DeviceRejectReason.REPLAY_ALREADY_RELEASED,
                    DeviceRejectReason.describe(DeviceRejectReason.REPLAY_ALREADY_RELEASED), null, operator, now);
            replayAuditMapper.insert(audit);
            opLogService.log(CcConstants.ENT_DEVICE_REPLAY, q.getId(), null, CcConstants.ACT_QUARANTINE_REPLAY,
                    String.format("隔离单 %s 重放被拒绝：已释放（入账批次结果ID=%d），只能重放未入账数据；操作人=%s",
                            q.getQueueNo(), q.getReleasedIngestId(), operator));
            return ReplayOutcome.rejected(audit, q);
        }

        // 重新执行完整校验链：从隔离单原样还原报文（payload 与签名逐字节复用，不接受修改）。
        // releasePriorQuarantine=false：隔离单的释放/审计由本重放方法统一处理，避免重复记账。
        DeviceTempIngestRequest req = rebuildRequest(q);
        IngestResult result;
        try {
            result = receiveSigned(req, false);
        } catch (BusinessException e) {
            return failReplay(q, "REPLAY_BUSINESS_ERROR", e.getMessage(), operator, now);
        } catch (Exception e) {
            return failReplay(q, "REPLAY_SYSTEM_ERROR", "重放系统异常: " + e.getMessage(), operator, now);
        }

        if (result.isQuarantined()) {
            // 仍未通过（设备未登记/证书不符/签名仍失败等）：保持隔离，审计 FAILED
            TempBatchQuarantine latest = requireQuarantine(quarantineId);
            latest.setReplayCount((latest.getReplayCount() == null ? 0 : latest.getReplayCount()) + 1);
            quarantineMapper.updateById(latest);
            DeviceReplayAudit audit = buildAudit(latest, CcConstants.REPLAY_FAILED,
                    result.getReasonCode(), result.getReasonDetail(), null, operator, now);
            replayAuditMapper.insert(audit);
            opLogService.log(CcConstants.ENT_DEVICE_REPLAY, latest.getId(), null, CcConstants.ACT_QUARANTINE_REPLAY,
                    String.format("隔离单 %s 第 %d 次重放仍失败：原因码=%s，说明=%s；操作人=%s",
                            latest.getQueueNo(), audit.getReplaySeq(), result.getReasonCode(),
                            result.getReasonDetail(), operator));
            return ReplayOutcome.failed(audit, latest);
        }

        // 重放成功：释放隔离单（只进不退），审计 SUCCESS
        q.setStatus(CcConstants.QUARANTINE_RELEASED);
        q.setReleasedTime(now);
        q.setReleasedBy(UserContext.currentId());
        q.setReleasedIngestId(result.getIngestId());
        q.setReplayCount((q.getReplayCount() == null ? 0 : q.getReplayCount()) + 1);
        quarantineMapper.updateById(q);

        DeviceReplayAudit audit = buildAudit(q, CcConstants.REPLAY_SUCCESS, null,
                "重放验签通过并入账，隔离批次已释放", result, operator, now);
        replayAuditMapper.insert(audit);

        opLogService.log(CcConstants.ENT_QUARANTINE, q.getId(), null, CcConstants.ACT_QUARANTINE_RELEASE,
                String.format("隔离单 %s 重放成功释放：入账批次结果ID=%d，新入账=%d，去重=%d，不可归属=%d；操作人=%s",
                        q.getQueueNo(), result.getIngestId(), result.getInsertedCount(),
                        result.getDuplicateCount(), result.getUnmatchedCount(), operator));
        return ReplayOutcome.success(audit, q, result);
    }

    private ReplayOutcome failReplay(TempBatchQuarantine q, String code, String detail,
                                     String operator, LocalDateTime now) {
        q.setReplayCount((q.getReplayCount() == null ? 0 : q.getReplayCount()) + 1);
        quarantineMapper.updateById(q);
        DeviceReplayAudit audit = buildAudit(q, CcConstants.REPLAY_FAILED, code,
                truncate(detail, 1000), null, operator, now);
        replayAuditMapper.insert(audit);
        opLogService.log(CcConstants.ENT_DEVICE_REPLAY, q.getId(), null, CcConstants.ACT_QUARANTINE_REPLAY,
                String.format("隔离单 %s 第 %d 次重放失败：原因码=%s，说明=%s；操作人=%s",
                        q.getQueueNo(), audit.getReplaySeq(), code, detail, operator));
        return ReplayOutcome.failed(audit, q);
    }

    /** 从隔离单还原原始报文：测点由 payload 规范化串解析，签名/证书/批次号/设备序列原样复用 */
    private DeviceTempIngestRequest rebuildRequest(TempBatchQuarantine q) {
        DeviceTempIngestRequest req = new DeviceTempIngestRequest();
        req.setClientBatchNo(q.getClientBatchNo());
        req.setDeviceSerial(q.getDeviceSerial());
        req.setSignature(q.getSignature());
        req.setCertPem(q.getCertPem());
        // canonical: clientBatchNo|deviceSerial|point;point...
        String payload = q.getPayload();
        int firstBar = payload.indexOf('|');
        int secondBar = payload.indexOf('|', firstBar + 1);
        String pointsPart = payload.substring(secondBar + 1);
        List<DeviceTempPoint> points = new ArrayList<>();
        if (!pointsPart.isEmpty()) {
            for (String item : pointsPart.split(";", -1)) {
                String[] f = item.split(",", -1);
                DeviceTempPoint p = new DeviceTempPoint();
                p.setSampleTime(LocalDateTime.parse(f[0]));
                p.setTemperature(new java.math.BigDecimal(f[1]));
                if (f.length > 2 && !f[2].isEmpty()) {
                    p.setMileageKm(new java.math.BigDecimal(f[2]));
                }
                points.add(p);
            }
        }
        req.setPoints(points);
        return req;
    }

    private DeviceReplayAudit buildAudit(TempBatchQuarantine q, String result, String reasonCode,
                                         String reasonDetail, IngestResult ingest,
                                         String operator, LocalDateTime now) {
        DeviceReplayAudit audit = new DeviceReplayAudit();
        audit.setQuarantineId(q.getId());
        audit.setQueueNo(q.getQueueNo());
        Long seq = replayAuditMapper.selectCount(new QueryWrapper<DeviceReplayAudit>()
                .eq("quarantine_id", q.getId()));
        audit.setReplaySeq((seq == null ? 0 : seq.intValue()) + 1);
        audit.setResult(result);
        audit.setReasonCode(reasonCode);
        audit.setReasonDetail(truncate(reasonDetail, 1000));
        if (ingest != null) {
            audit.setInsertedCount(ingest.getInsertedCount());
            audit.setDuplicateCount(ingest.getDuplicateCount());
            audit.setUnmatchedCount(ingest.getUnmatchedCount());
            audit.setAffectedBatchIds(ingest.getAffectedBatchIds());
        }
        audit.setOperator(operator);
        audit.setReplayTime(now);
        return audit;
    }

    // ---------------- 私有工具 ----------------

    private Device findByFingerprint(String fingerprint) {
        List<Device> rows = deviceMapper.selectList(new QueryWrapper<Device>()
                .eq("cert_fingerprint_sha256", fingerprint).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String generateQuarantineNo() {
        return String.format("QJ%09d", sequenceMapper.nextQuarantineNo());
    }

    private String joinIds(Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 幂等换载荷专用内部异常（区分于普通业务拒绝，便于入口转隔离） */
    private static class BatchPayloadChangedException extends RuntimeException {
        BatchPayloadChangedException(String message) {
            super(message);
        }
    }

    /** 入账结果（首次处理/幂等回放/隔离 三态） */
    public static class IngestResult {
        private boolean quarantined;
        private boolean replay;
        private Long ingestId;
        private String clientBatchNo;
        private String deviceSerial;
        private Integer pointCount;
        private Integer insertedCount;
        private Integer duplicateCount;
        private Integer unmatchedCount;
        private String affectedBatchIds;
        private String sourceType;
        private Long quarantineId;
        private String quarantineNo;
        private String reasonCode;
        private String reasonDetail;
        private boolean clientBatchNoGenerated;

        static IngestResult created(DeviceIngestBatch r) {
            IngestResult x = new IngestResult();
            x.quarantined = false;
            x.replay = false;
            x.ingestId = r.getId();
            copy(r, x);
            return x;
        }

        static IngestResult replay(DeviceIngestBatch r) {
            IngestResult x = new IngestResult();
            x.quarantined = false;
            x.replay = true;
            x.ingestId = r.getId();
            copy(r, x);
            return x;
        }

        static IngestResult quarantined(TempBatchQuarantine q) {
            IngestResult x = new IngestResult();
            x.quarantined = true;
            x.clientBatchNo = q.getClientBatchNo();
            x.deviceSerial = q.getDeviceSerial();
            x.pointCount = q.getPointCount();
            x.quarantineId = q.getId();
            x.quarantineNo = q.getQueueNo();
            x.reasonCode = q.getReasonCode();
            x.reasonDetail = q.getReasonDetail();
            return x;
        }

        private static void copy(DeviceIngestBatch r, IngestResult x) {
            x.clientBatchNo = r.getClientBatchNo();
            x.deviceSerial = r.getDeviceSerial();
            x.pointCount = r.getPointCount();
            x.insertedCount = r.getInsertedCount();
            x.duplicateCount = r.getDuplicateCount();
            x.unmatchedCount = r.getUnmatchedCount();
            x.affectedBatchIds = r.getAffectedBatchIds();
            x.sourceType = r.getSourceType();
        }

        public boolean isQuarantined() { return quarantined; }
        public boolean isReplay() { return replay; }
        public Long getIngestId() { return ingestId; }
        public String getClientBatchNo() { return clientBatchNo; }
        public String getDeviceSerial() { return deviceSerial; }
        public Integer getPointCount() { return pointCount; }
        public Integer getInsertedCount() { return insertedCount == null ? 0 : insertedCount; }
        public Integer getDuplicateCount() { return duplicateCount == null ? 0 : duplicateCount; }
        public Integer getUnmatchedCount() { return unmatchedCount == null ? 0 : unmatchedCount; }
        public String getAffectedBatchIds() { return affectedBatchIds; }
        public String getSourceType() { return sourceType; }
        public Long getQuarantineId() { return quarantineId; }
        public String getQuarantineNo() { return quarantineNo; }
        public String getReasonCode() { return reasonCode; }
        public String getReasonDetail() { return reasonDetail; }
        public boolean isClientBatchNoGenerated() { return clientBatchNoGenerated; }
        public void setQuarantined(boolean quarantined) { this.quarantined = quarantined; }
        public void setClientBatchNoGenerated(boolean g) { this.clientBatchNoGenerated = g; }
    }

    /** 重放结果（审计行 + 隔离单最新状态 + 成功时的入账结果） */
    public static class ReplayOutcome {
        private final DeviceReplayAudit audit;
        private final TempBatchQuarantine quarantine;
        private final IngestResult ingest;

        private ReplayOutcome(DeviceReplayAudit audit, TempBatchQuarantine quarantine, IngestResult ingest) {
            this.audit = audit;
            this.quarantine = quarantine;
            this.ingest = ingest;
        }

        static ReplayOutcome success(DeviceReplayAudit a, TempBatchQuarantine q, IngestResult r) {
            return new ReplayOutcome(a, q, r);
        }

        static ReplayOutcome failed(DeviceReplayAudit a, TempBatchQuarantine q) {
            return new ReplayOutcome(a, q, null);
        }

        static ReplayOutcome rejected(DeviceReplayAudit a, TempBatchQuarantine q) {
            return new ReplayOutcome(a, q, null);
        }

        public DeviceReplayAudit getAudit() { return audit; }
        public TempBatchQuarantine getQuarantine() { return quarantine; }
        public IngestResult getIngest() { return ingest; }
    }
}
