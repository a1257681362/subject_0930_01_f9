package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.BatchRejectRequest;
import com.evops.coldchain.dto.BatchSettleRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.RevokeRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.entity.VehicleProbe;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.ExceptionEvidenceMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运输批次服务（冷链台账核心）。
 *
 * 版本与凭证规则：
 * - 派车时把发货单锁定的档案版本复制为批次快照；此后核对/签收看快照，不看档案现值。
 * - 温度记录、异常事件、凭证全部追加（INSERT only），不覆盖既有凭证。
 * - 签收 = 按快照核对实物 + 无未处理异常，通过后状态 SIGNED 闭环。
 * - 撤回只置 REVOKED 状态，原始记录全部保留。
 */
@Service
public class TransportBatchService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private TemperatureLogMapper temperatureMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private ExceptionEvidenceMapper evidenceMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OrderService orderService;
    @Resource
    private VehicleService vehicleService;
    @Resource
    private DrugService drugService;
    @Resource
    private RouteService routeService;
    @Resource
    private ProbeService probeService;
    @Resource
    private HandoverService handoverService;
    @Resource
    private PermissionService permissionService;
    @Resource
    private DrugCategoryService categoryService;
    @Resource
    private AlertEvaluationService alertEvaluationService;
    @Resource
    private ExceptionLifecycleService exceptionLifecycleService;
    @Resource
    private ApprovalService approvalService;
    @Resource
    private LockedPriceService lockedPriceService;
    @Resource
    private OpLogService opLogService;

    /** 派车：生成稳定批次号，固化档案快照，关联发货单与车辆 */
    @Transactional
    public TransportBatch dispatch(DispatchRequest req) {
        ShipmentOrder order = orderService.requireOrder(req.getOrderId());
        if (!CcConstants.ORDER_CREATED.equals(order.getStatus())) {
            throw new BusinessException("发货单当前状态不允许派车: " + order.getStatus());
        }
        Vehicle vehicle = vehicleService.requireActiveVehicle(req.getVehicleId());

        // 取发货单锁定的原版本——档案之后再变更也与本批次无关
        DrugVersion version = drugService.requireVersion(order.getDrugId(), order.getDrugVersionNo());

        // 路线温区分段：派车只登记走哪条路线，季节版本的选定与逐段快照推迟到“发运”。
        // 因此派车后、发运前发布的新版本仍会作用于该未发运批次；一旦发运即与路线版本脱钩。
        if (req.getRouteId() != null) {
            routeService.requireActiveRoute(req.getRouteId());
        }

        // V8 冷链承运商报价比选：派车可引用锁价单。一旦携带，锁价单必须有效
        // （未作废、无改价审批在途），批次持其ID作为“锁价单反查承运商报价”的锚点。
        // 与 V7 运输审批同口径：未走报价比选的既有批次不受此卡口约束。
        com.evops.coldchain.entity.LockedPrice lockedPrice = req.getLockedPriceId() == null
                ? null : lockedPriceService.requireLockedForDispatch(req.getLockedPriceId());

        TransportBatch batch = new TransportBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setOrderId(order.getId());
        batch.setVehicleId(vehicle.getId());
        batch.setDrugId(order.getDrugId());
        // 档案快照
        batch.setDrugVersionNo(version.getVersionNo());
        batch.setApprovalNoSnap(version.getApprovalNo());
        batch.setTempMinSnap(version.getTempMin());
        batch.setTempMaxSnap(version.getTempMax());
        batch.setPackageSpecSnap(version.getPackageSpec());
        // 路线引用（季节版本与分段在发运时才选定并快照，故此处 version* 留空）
        batch.setRouteId(req.getRouteId());
        // V8 锁价单引用（可空）：携带则批次与中选报价快照绑定，支持锁价单反查
        batch.setLockedPriceId(lockedPrice == null ? null : lockedPrice.getId());
        // V7 监管区域：派车可指定，作为抽检查询区域隔离依据；不传也可首次抽检时补登
        batch.setSuperviseRegion(req.getSuperviseRegion() == null ? null
                : (req.getSuperviseRegion().trim().isEmpty() ? null : req.getSuperviseRegion().trim()));
        // 运输信息
        batch.setReceiverOrg(order.getReceiverOrg());
        batch.setShipQty(order.getShipQty());
        batch.setDispatchTime(LocalDateTime.now());
        batch.setHasTempExcursion(false);
        batch.setStatus(CcConstants.BATCH_DISPATCHED);
        batchMapper.insert(batch);

        // V5 温控预警模板：批次落库拿到ID后，按药品类别解析生效模板（上级继承/本类别覆盖）
        // 逐温区固化快照并命中本批次预警温区；此后类别/模板变更与本批次脱钩，旧批次永远用创建时快照。
        String alertZoneCode = categoryService.snapshotForBatch(batch, version);
        if (alertZoneCode != null) {
            batch.setAlertZoneCode(alertZoneCode);
            batchMapper.updateById(batch);
        }

        // 发货单同步为已派车
        order.setStatus(CcConstants.ORDER_DISPATCHED);
        orderMapper.updateById(order);

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_DISPATCH,
                String.format("批次 %s 派车：发货单=%s，车辆=%s，按档案 V%d 快照（批准文号=%s，温区=%s~%s℃，包装=%s）%s%s%s",
                        batch.getBatchNo(), order.getOrderNo(), vehicle.getPlateNo(),
                        version.getVersionNo(), version.getApprovalNo(),
                        version.getTempMin(), version.getTempMax(), version.getPackageSpec(),
                        req.getRouteId() == null ? ""
                                : "；已指定运输路线，温区分段版本将在发运时按当时季节选定并逐段快照",
                        alertZoneCode == null ? ""
                                : String.format("；温控预警模板已按药品类别创建时快照，本批次预警温区=%s", alertZoneCode),
                        lockedPrice == null ? ""
                                : String.format("；引用锁价单 %s（承运商=%s，含税总价=%s，可经锁价单反查原报价）",
                                        lockedPrice.getLockedPriceNo(), lockedPrice.getCarrierNameSnap(),
                                        lockedPrice.getFinalTotalAmount())));
        return batch;
    }

    /** 发运：已派车 -> 在途；发运瞬间选定路线季节生效版本并逐段固化快照 */
    @Transactional
    public TransportBatch ship(Long batchId) {
        TransportBatch batch = requireActiveBatch(batchId);
        if (!CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException("仅已派车批次允许发运，当前状态: " + batch.getStatus());
        }

        // V7 冷链运输审批卡口：发起过审批的批次，只完成一半（PENDING）停留待处理、
        // 已驳回（REJECTED）均不得发运；审批同意（APPROVED）才放行。未发起审批不受此卡口约束。
        approvalService.requireApprovedForShip(batchId);

        // 发运前卡口：车辆必须绑定仍在有效期内的持证探头（接口与页面共用这一道校验）
        VehicleProbe probeBinding = probeService.requireValidBindingForShip(batch.getVehicleId());
        // 当前责任主体初始为承运方（驾驶员/车辆）；后续分仓交接提交后随单转移给接收方
        Vehicle carrier = vehicleService.requireActiveVehicle(batch.getVehicleId());

        int snapCount = 0;
        RouteVersion routeVersion = null;
        if (batch.getRouteId() != null) {
            if (batch.getRouteVersionId() == null) {
                // 未发运批次：发运瞬间取当时季节的生效版本（派车后发布的新版本在此刻生效）
                routeVersion = routeService.selectEffectiveAt(batch.getRouteId(), LocalDateTime.now());
                batch.setRouteVersionId(routeVersion.getId());
                batch.setRouteSeason(routeVersion.getSeason());
                batch.setRouteVersionNo(routeVersion.getVersionNo());
                snapCount = routeService.snapshotForBatch(batch.getId(), routeVersion.getId()).size();
            }
        }

        // 车载探头校准凭证逐字段快照：发运后探头替换/证书作废均不影响在途批次与温度归属
        batch.setProbeId(probeBinding.getProbeId());
        batch.setProbeBindId(probeBinding.getId());
        batch.setProbeCertId(probeBinding.getCertId());
        batch.setProbeSerialNoSnap(probeBinding.getSerialNoSnap());
        batch.setProbeRangeMinSnap(probeBinding.getRangeMinSnap());
        batch.setProbeRangeMaxSnap(probeBinding.getRangeMaxSnap());
        batch.setProbeCalibrateOrgSnap(probeBinding.getCalibrateOrgSnap());
        batch.setProbeCertNoSnap(probeBinding.getCertNoSnap());
        batch.setProbeValidUntilSnap(probeBinding.getValidUntilSnap());
        batch.setProbeSha256Snap(probeBinding.getSha256Snap());

        // 责任主体起点：承运商（机构）+ 驾驶员（具体责任人）；分仓交接提交后随单转移
        batch.setResponsibleOrg(carrier.getCarrierName());
        batch.setResponsibleParty(carrier.getDriverName() == null ? carrier.getPlateNo() : carrier.getDriverName());

        batch.setStatus(CcConstants.BATCH_SHIPPED);
        batch.setShipTime(LocalDateTime.now());
        batchMapper.updateById(batch);

        ShipmentOrder order = orderService.requireOrder(batch.getOrderId());
        order.setStatus(CcConstants.ORDER_SHIPPED);
        orderMapper.updateById(order);

        opLogService.log(CcConstants.ENT_BATCH, batchId, batchId, CcConstants.ACT_SHIP,
                "批次 " + batch.getBatchNo() + " 发运在途"
                        + String.format("；车载探头序列号=%s，量程=%s~%s℃，校准机构=%s，证书编号=%s，有效期至=%s，摘要=%s（发运时固化，途中替换不回写）",
                                probeBinding.getSerialNoSnap(), probeBinding.getRangeMinSnap(),
                                probeBinding.getRangeMaxSnap(), probeBinding.getCalibrateOrgSnap(),
                                probeBinding.getCertNoSnap(), probeBinding.getValidUntilSnap(),
                                probeBinding.getSha256Snap())
                        + (routeVersion == null ? "" : String.format(
                                "；路线季节版本=%s V%d，逐段固化温区分段 %d 段（发运后版本变更不影响本批次）",
                                routeVersion.getSeason(), routeVersion.getVersionNo(), snapCount)));
        return batch;
    }

    /**
     * 温度上报：按测温时间追加留痕。
     * - 无路线批次：以批次档案快照温区判定越限（V1 口径）。
     * - 有路线温区分段快照的批次：按上报里程命中分段，以该段允许温区判定；
     *   同一段内“连续越限”持续时间不超过该段缓冲时长则只留痕、不开异常；
     *   一旦超过缓冲时长，自动追加一条温度异常事件（定位到该段责任人）。
     * 不改动任何历史温度行。
     */
    @Transactional
    public TemperatureLog reportTemperature(TempReportRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        // 派车后、闭环前（含异常挂起）允许持续上报；已签收/已撤回不可上报
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能再上报温度");
        }
        if (!CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())
                && !CcConstants.BATCH_SHIPPED.equals(batch.getStatus())
                && !CcConstants.BATCH_EXCEPTION.equals(batch.getStatus())) {
            throw new BusinessException("批次当前状态不允许上报温度: " + batch.getStatus());
        }

        List<RouteSegmentSnap> snaps = batch.getRouteVersionId() == null
                ? java.util.Collections.emptyList()
                : routeService.listSnapshotsByBatch(batch.getId());

        RouteSegmentSnap hit = null;
        BigDecimal min;
        BigDecimal max;
        Integer bufferMinutes = null;
        if (!snaps.isEmpty()) {
            if (req.getMileageKm() == null) {
                throw new BusinessException("该批次已配置路线温区分段，温度上报必须携带当前里程 mileageKm");
            }
            hit = matchSegment(snaps, req.getMileageKm());
            if (hit == null) {
                throw new BusinessException(String.format(
                        "上报里程 %skm 未落在路线任一分段内（0~%skm），无法判定温区",
                        req.getMileageKm(), snaps.get(snaps.size() - 1).getToKm()));
            }
            min = hit.getTempMin();
            max = hit.getTempMax();
            bufferMinutes = hit.getBufferMinutes();
        } else {
            min = batch.getTempMinSnap();
            max = batch.getTempMaxSnap();
        }

        boolean inRange = req.getTemperature().compareTo(min) >= 0
                && req.getTemperature().compareTo(max) <= 0;

        TemperatureLog log = new TemperatureLog();
        log.setBatchId(batch.getId());
        log.setRecordTime(req.getRecordTime());
        log.setTemperature(req.getTemperature());
        log.setInRange(inRange);
        log.setRecordedBy(req.getRecordedBy());
        log.setRemark(req.getRemark());
        log.setMileageKm(req.getMileageKm());
        log.setSegmentSeq(hit == null ? null : hit.getSegmentSeq());
        // 测点归属发运时固化的探头；该字段只随新行追加，替换探头后历史温度行永不回写
        log.setProbeId(batch.getProbeId());
        temperatureMapper.insert(log);

        String scope = hit == null
                ? String.format("快照温区 %s~%s℃", min, max)
                : String.format("里程 %skm 第%d段（%s~%skm，责任人=%s）允许温区 %s~%s℃，缓冲 %d 分钟",
                        req.getMileageKm(), hit.getSegmentSeq(), hit.getFromKm(), hit.getToKm(),
                        hit.getOwnerName(), min, max, bufferMinutes);
        opLogService.log(CcConstants.ENT_TEMPERATURE, log.getId(), batch.getId(), CcConstants.ACT_TEMP_REPORT,
                String.format("批次 %s 温度留痕：%s = %s℃（%s，%s；采集探头=%s）",
                        batch.getBatchNo(), req.getRecordTime(), req.getTemperature(), scope,
                        inRange ? "正常" : "越限", batch.getProbeSerialNoSnap()));

        // V5：启用了类别温控预警模板（派车时固化到温区快照）的批次，连续超限/抖动合并/预警与
        // 联动温度异常统一由预警引擎按“采样点 + 创建时快照”处理；模板后续变更不影响本批次。
        if (alertEvaluationService.getBatchZone(batch.getId(), batch.getAlertZoneCode()) != null) {
            // 刷新托管状态（markException 等场景拿到的是托管前实例）
            alertEvaluationService.onTemperatureReported(batch, log);
            return log;
        }

        if (!inRange) {
            // 缓冲判定：同段连续越限（不含正常点打断）是否已超过缓冲时长
            boolean bufferExceeded = bufferMinutes == null
                    || isContinuousExcursionBeyondBuffer(batch.getId(), log, hit, bufferMinutes);

            if (bufferExceeded) {
                String desc = hit == null
                        ? String.format("系统自动标记：%s 测温 %s℃ 超出快照温区 %s~%s℃",
                                req.getRecordTime(), req.getTemperature(), min, max)
                        : String.format("系统自动标记：%s 里程 %skm 第%d段（责任人=%s）测温 %s℃ 超出允许温区 %s~%s℃，连续越限已超过缓冲 %d 分钟",
                                req.getRecordTime(), req.getMileageKm(), hit.getSegmentSeq(),
                                hit.getOwnerName(), req.getTemperature(), min, max, bufferMinutes);
                // V6：温度超限客观成立（越过缓冲临界）即确认启钟，时钟起点=本次越限采样的临界时刻
                registerTempException(batch, desc, req.getRecordTime(),
                        hit == null ? null : hit.getSegmentSeq(), null);
            }
        }
        return log;
    }

    /** 按里程命中分段 [fromKm, toKm) */
    private RouteSegmentSnap matchSegment(List<RouteSegmentSnap> snaps, BigDecimal mileage) {
        if (mileage.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        for (RouteSegmentSnap s : snaps) {
            boolean geFrom = mileage.compareTo(s.getFromKm()) >= 0;
            boolean ltTo = mileage.compareTo(s.getToKm()) < 0;
            // 末段终点（=总里程）纳入最后一段，避免到达终点时无法判定
            boolean atEnd = mileage.compareTo(s.getToKm()) == 0 && s.getSegmentSeq().equals(snaps.size());
            if (geFrom && (ltTo || atEnd)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 判断本次越限是否使”同段连续越限”持续时间超过缓冲时长。
     * 沿时间倒序回看本批温度：遇到正常点或越过段边界即打断连续越限；
     * 连续起点为空（越限前无记录）时，保守按”本次即起点”计算。
     * V9：过滤 alert_exempt=TRUE 的补传保护点（已审核窗口内的补传不参与判定）。
     */
    public boolean isContinuousExcursionBeyondBuffer(Long batchId, TemperatureLog current,
                                                      RouteSegmentSnap hit, int bufferMinutes) {
        List<TemperatureLog> history = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId)
                .eq("alert_exempt", false)
                .orderByAsc("record_time")
                .orderByAsc("id"));

        LocalDateTime streakStart = current.getRecordTime();
        for (int i = history.size() - 1; i >= 0; i--) {
            TemperatureLog t = history.get(i);
            if (t.getId().equals(current.getId())) {
                streakStart = t.getRecordTime();
                continue;
            }
            boolean sameSegment = hit == null
                    || (t.getSegmentSeq() != null && t.getSegmentSeq().equals(hit.getSegmentSeq()));
            if (!sameSegment || Boolean.TRUE.equals(t.getInRange())) {
                break; // 正常点或切换分段，连续越限被打断
            }
            streakStart = t.getRecordTime();
        }
        long minutes = Duration.between(streakStart, current.getRecordTime()).toMinutes();
        return minutes > bufferMinutes;
    }

    /**
     * 人工异常补录（追加）。
     * V6：分级四类（温度超限/封签破损/延误/收货拒签）补录为“待确认(OPEN)”，处置时钟不启动，
     * 需显式确认才定级启钟；occurredTime=首次发现时间，只写一次不被后续覆盖。
     * 旧类型（DAMAGE/OTHER）仍走原“标记即 OPEN、resolveException 闭环”的旧路径。
     */
    @Transactional
    public ExceptionEvent markException(ExceptionMarkRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        String type = normalizeType(req.getExceptionType());
        LocalDateTime occurred = req.getOccurredTime() == null ? LocalDateTime.now() : req.getOccurredTime();
        if (exceptionLifecycleService.isGradedType(type)) {
            return exceptionLifecycleService.openManual(batch, type, req.getDescription(), occurred);
        }
        return markExceptionInternal(batch, type, req.getDescription(), occurred, null);
    }

    /**
     * 温度超限异常统一入口（V1~V2 缓冲判定 / V5 预警引擎共用）：
     * 客观成立即按 TEMP_EXCURSION 分级确认，处置时钟自临界时刻 criticalTime 起算，
     * foundTime 同值落库且不被任何补录覆盖。
     */
    public ExceptionEvent registerTempException(TransportBatch batch, String description,
                                                 LocalDateTime criticalTime, Integer segmentSeq, Long alertId) {
        return exceptionLifecycleService.registerAutoConfirmed(
                batch, CcConstants.EXC_TEMP, description, criticalTime, segmentSeq, alertId);
    }

    private ExceptionEvent markExceptionInternal(TransportBatch batch, String type, String description,
                                                 LocalDateTime occurred, Integer segmentSeq) {
        ExceptionEvent event = new ExceptionEvent();
        event.setBatchId(batch.getId());
        event.setExceptionType(type);
        event.setDescription(description);
        event.setOccurredTime(occurred);
        event.setStatus(CcConstants.EXC_OPEN);
        event.setSegmentSeq(segmentSeq);
        exceptionMapper.insert(event);

        // 批次进入异常挂起（已签收/已撤回的不允许走到这里，前置已拦截）
        if (!CcConstants.BATCH_EXCEPTION.equals(batch.getStatus())) {
            batch.setStatus(CcConstants.BATCH_EXCEPTION);
        }
        if (CcConstants.EXC_TEMP.equals(type)) {
            batch.setHasTempExcursion(true);
        }
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), batch.getId(), CcConstants.ACT_EXCEPTION_MARK,
                String.format("批次 %s 追加异常[%s]：%s", batch.getBatchNo(), type, description));
        return event;
    }

    /** 追加凭证：永远新增一行，不覆盖/更新/删除已有凭证 */
    @Transactional
    public ExceptionEvidence appendEvidence(EvidenceAppendRequest req) {
        ExceptionEvent event = requireException(req.getExceptionId());
        requireActiveBatch(event.getBatchId());

        ExceptionEvidence evidence = new ExceptionEvidence();
        evidence.setExceptionId(event.getId());
        evidence.setFileName(req.getFileName());
        evidence.setFileUrl(req.getFileUrl());
        evidence.setNote(req.getNote());
        evidenceMapper.insert(evidence);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(),
                CcConstants.ACT_EVIDENCE_APPEND,
                String.format("异常#%d 追加凭证：%s %s（备注：%s）",
                        event.getId(),
                        req.getFileName() == null ? "" : req.getFileName(),
                        req.getFileUrl() == null ? "" : req.getFileUrl(),
                        req.getNote() == null ? "" : req.getNote()));
        return evidence;
    }

    /**
     * 异常直接闭环（旧路径，保留兼容）：仅适用于不纳入 V6 分级的旧类型（DAMAGE/OTHER）。
     * 分级四类（温度超限/封签破损/延误/收货拒签）必须走“确认 → 引用证据的处置结论 →
     * 指定岗位复核”的新闭环，不能用一句话直接关闭。
     */
    @Transactional
    public ExceptionEvent resolveException(Long exceptionId, String resolveNote) {
        ExceptionEvent event = requireException(exceptionId);
        requireActiveBatch(event.getBatchId());
        if (exceptionLifecycleService.isGradedType(event.getExceptionType())
                || event.getGrade() != null) {
            throw new BusinessException(String.format(
                    "异常#%d 为分级异常[%s/%s]，请通过“提交处置结论（引用证据）→ %s 岗位复核”关闭，不能直接填写说明闭环",
                    exceptionId, event.getExceptionType(),
                    event.getGrade() == null ? "待确认" : event.getGrade(),
                    event.getReviewerRoleSnap() == null ? "质量复核" : event.getReviewerRoleSnap()));
        }
        if (CcConstants.EXC_RESOLVED.equals(event.getStatus())) {
            throw new BusinessException("异常已处理，不能重复处理");
        }
        if (!CcConstants.EXC_OPEN.equals(event.getStatus())) {
            throw new BusinessException("异常当前状态不允许直接闭环: " + event.getStatus());
        }
        event.setStatus(CcConstants.EXC_RESOLVED);
        event.setResolveNote(resolveNote);
        event.setResolveBy(UserContext.currentId());
        event.setResolveTime(LocalDateTime.now());
        exceptionMapper.updateById(event);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(),
                CcConstants.ACT_EXCEPTION_RESOLVE,
                "异常#" + event.getId() + " 处理闭环（旧类型直闭环）：" + resolveNote);
        return event;
    }

    /**
     * 收货拒签（V6）：拒签即 REJECTION（重大 L1）异常客观成立，登记即确认启钟，
     * 发货单/批次不进入签收闭环（保持挂起），异常未关闭前批次不得结算。
     */
    @Transactional
    public ExceptionEvent reject(BatchRejectRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能再登记收货拒签");
        }
        if (CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException("批次尚未发运在途，不能登记收货拒签");
        }
        LocalDateTime now = LocalDateTime.now();
        String desc = String.format("收货方 %s 拒签：%s", req.getRejectBy(), req.getReason());
        ExceptionEvent event = exceptionLifecycleService.registerAutoConfirmed(
                batch, CcConstants.EXC_REJECTION, desc, now, null, null);

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_BATCH_REJECT,
                String.format("批次 %s 收货拒签：拒签人=%s，原因=%s；已按收货拒签分级确认并启动处置时钟，未关闭前不得签收/结算",
                        batch.getBatchNo(), req.getRejectBy(), req.getReason()));
        return event;
    }

    /**
     * 批次结算（V6）：必须已签收闭环，且不存在未关闭异常。
     * 未关闭（待确认/处置中/待复核）异常一律拦截，结算记账只追加一次。
     */
    @Transactional
    public TransportBatch settle(BatchSettleRequest req) {
        TransportBatch batch = requireBatch(req.getBatchId());
        if (!CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("仅已签收闭环批次允许结算，当前状态: " + batch.getStatus()
                    + "（收货拒签/异常挂起批次须先关闭异常并完成签收）");
        }
        if (batch.getSettleTime() != null) {
            throw new BusinessException("批次已于 " + batch.getSettleTime() + " 结算，不能重复结算");
        }
        exceptionLifecycleService.requireAllClosed(batch.getId(), "进入结算");

        batch.setSettleTime(LocalDateTime.now());
        batch.setSettleBy(UserContext.currentId());
        batch.setSettleRemark(req.getSettleRemark());
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_BATCH_SETTLE,
                String.format("批次 %s 完成结算（全部异常已复核关闭）：%s；结算人=%s",
                        batch.getBatchNo(),
                        req.getSettleRemark() == null ? "" : req.getSettleRemark(),
                        UserContext.currentName()));
        return batch;
    }

    /**
     * 签收闭环：必须按派车时固化的档案快照逐项核对，
     * 且不存在未处理（OPEN）异常。核对口径不随档案后续变更而改变。
     */
    @Transactional
    public TransportBatch sign(SignRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收，不能重复签收");
        }

        // 冷链药品运输权限被收紧时，交接/继续派送停止推进（已确认的封签核验事实保留）
        permissionService.requirePermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        // 已进入分仓交接流程的批次：全部箱子封签核验完成、箱数平衡后方可继续派送/签收；
        // 未走中转仓拆分的直发批次（未登记到仓箱数）不经过此卡口。
        handoverService.requireReadyForDispatchIfStarted(batch.getId());

        // 1) 实物与快照核对（按原版本）
        if (!batch.getApprovalNoSnap().equals(req.getReceivedApprovalNo())) {
            throw new BusinessException(String.format(
                    "签收核对不符：实物批准文号[%s]与批次快照（原档案 V%d）[%s]不一致",
                    req.getReceivedApprovalNo(), batch.getDrugVersionNo(), batch.getApprovalNoSnap()));
        }
        if (!batch.getPackageSpecSnap().equals(req.getReceivedPackageSpec())) {
            throw new BusinessException(String.format(
                    "签收核对不符：实物包装规格[%s]与批次快照（原档案 V%d）[%s]不一致",
                    req.getReceivedPackageSpec(), batch.getDrugVersionNo(), batch.getPackageSpecSnap()));
        }
        if (!batch.getShipQty().equals(req.getConfirmQty())) {
            throw new BusinessException(String.format(
                    "签收核对不符：实收数量[%d]与发运数量[%d]不一致", req.getConfirmQty(), batch.getShipQty()));
        }

        // 2) 未关闭异常拦截（V6：待确认/处置中/待复核均视为未关闭，分级异常须复核关闭后才能签收）
        long openCount = exceptionLifecycleService.unclosedCount(batch.getId());
        if (openCount > 0) {
            throw new BusinessException("存在 " + openCount
                    + " 条未关闭异常（待确认/处置中/待复核），暂不能签收闭环；请完成处置结论、引用证据并经指定岗位复核关闭");
        }

        // 核对通过，闭环
        batch.setStatus(CcConstants.BATCH_SIGNED);
        batch.setSignBy(req.getSignBy());
        batch.setSignRemark(req.getSignRemark());
        batch.setSignTime(LocalDateTime.now());
        batchMapper.updateById(batch);

        ShipmentOrder order = orderService.requireOrder(batch.getOrderId());
        order.setStatus(CcConstants.ORDER_SIGNED);
        orderMapper.updateById(order);

        // V5：批次闭环，关闭活动连续超限计时段（温度/已产生预警事实保留）
        alertEvaluationService.closeStreaks(batch.getId());

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_SIGN,
                String.format("批次 %s 由 %s 签收闭环：按原档案 V%d 快照核对一致（批准文号=%s，包装=%s，数量=%d）%s；闭环时责任主体=%s/%s%s",
                        batch.getBatchNo(), req.getSignBy(), batch.getDrugVersionNo(),
                        batch.getApprovalNoSnap(), batch.getPackageSpecSnap(), batch.getShipQty(),
                        Boolean.TRUE.equals(batch.getHasTempExcursion()) ? "；途中曾越限（异常均已处理）" : "",
                        batch.getResponsibleOrg() == null ? "原承运方" : batch.getResponsibleOrg(),
                        batch.getResponsibleParty() == null ? "" : batch.getResponsibleParty(),
                        batch.getArrivedBoxQty() == null ? "" : "；分仓交接全部封签核验通过、箱数平衡"));
        return batch;
    }

    /**
     * 撤回：仅置 REVOKED 作废标记并留痕。
     * 不删除批次、温度、异常、凭证、日志任何一行。已签收闭环的批次不可撤回。
     */
    @Transactional
    public TransportBatch revoke(RevokeRequest req) {
        TransportBatch batch = requireBatch(req.getBatchId());
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能撤回");
        }
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回，不能重复撤回");
        }
        batch.setStatus(CcConstants.BATCH_REVOKED);
        batchMapper.updateById(batch);

        ShipmentOrder order = orderService.requireOrder(batch.getOrderId());
        if (!CcConstants.ORDER_SIGNED.equals(order.getStatus())) {
            order.setStatus(CcConstants.ORDER_REVOKED);
            orderMapper.updateById(order);
        }

        // V5：批次作废，关闭活动连续超限计时段（温度/预警事实原样保留）
        alertEvaluationService.closeStreaks(batch.getId());

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_REVOKE,
                "批次 " + batch.getBatchNo() + " 撤回作废（原始记录全部保留）。原因：" + req.getReason());
        return batch;
    }

    // ---------------- 查询 ----------------

    public TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    private TransportBatch requireActiveBatch(Long batchId) {
        TransportBatch batch = requireBatch(batchId);
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能操作: " + batch.getBatchNo());
        }
        return batch;
    }

    public ExceptionEvent requireException(Long exceptionId) {
        ExceptionEvent event = exceptionMapper.selectById(exceptionId);
        if (event == null) {
            throw new BusinessException("异常事件不存在: " + exceptionId);
        }
        return event;
    }

    public List<TemperatureLog> listTemperatures(Long batchId) {
        return temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId)
                .orderByAsc("record_time")
                .orderByAsc("id"));
    }

    public List<ExceptionEvent> listExceptions(Long batchId) {
        return exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }

    public List<ExceptionEvidence> listEvidence(Long exceptionId) {
        return evidenceMapper.selectList(new QueryWrapper<ExceptionEvidence>()
                .eq("exception_id", exceptionId)
                .orderByAsc("id"));
    }

    public List<TransportBatch> listByOrder(Long orderId) {
        return batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .eq("order_id", orderId)
                .orderByDesc("id"));
    }

    private String normalizeType(String type) {
        if (type == null) {
            return CcConstants.EXC_OTHER;
        }
        switch (type.trim().toUpperCase()) {
            case CcConstants.EXC_TEMP:
            case CcConstants.EXC_DAMAGE:
            case CcConstants.EXC_DELAY:
            case CcConstants.EXC_SEAL_BROKEN:
            case CcConstants.EXC_REJECTION:
                return type.trim().toUpperCase();
            default:
                return CcConstants.EXC_OTHER;
        }
    }

    /** 稳定批次号：年-序列（序列由 H2 生成，单调唯一，绝不复用） */
    private String generateBatchNo() {
        return String.format("PC%tY%06d", LocalDateTime.now(), sequenceMapper.nextBatchNo());
    }
}
