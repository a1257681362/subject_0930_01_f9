package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSeal;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.DrugMapper;
import com.evops.coldchain.mapper.ExceptionEvidenceMapper;
import com.evops.coldchain.mapper.RouteMapper;
import com.evops.coldchain.mapper.RouteVersionMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账聚合查询：一个批次一张完整台账——
 * 批次（含档案快照）、发货单、车辆、温度留痕、异常+凭证、全链路操作日志，
 * 并对照药品档案当前版本，直观呈现“按原版本核对、档案可先行变更”。
 */
@Service
public class LedgerService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private DrugMapper drugMapper;
    @Resource
    private RouteMapper routeMapper;
    @Resource
    private RouteVersionMapper routeVersionMapper;
    @Resource
    private ExceptionEvidenceMapper evidenceMapper;
    @Resource
    private com.evops.coldchain.mapper.ProbeCertMapper probeCertMapper;
    @Resource
    private TransportBatchService batchService;
    @Resource
    private RouteService routeService;
    @Resource
    private HandoverService handoverService;
    @Resource
    private PermissionService permissionService;
    @Resource
    private ExceptionLifecycleService exceptionLifecycleService;
    @Resource
    private OpLogService opLogService;
    @Resource
    private com.evops.coldchain.mapper.BatchAlertZoneSnapMapper alertSnapMapper;
    @Resource
    private com.evops.coldchain.mapper.TempAlertMapper tempAlertMapper;
    @Resource
    private com.evops.coldchain.mapper.InspectionTaskMapper inspectionTaskMapper;
    @Resource
    private com.evops.coldchain.mapper.InspectionSampleMapper inspectionSampleMapper;
    @Resource
    private com.evops.coldchain.mapper.SampleTransferMapper sampleTransferMapper;
    @Resource
    private com.evops.coldchain.mapper.InspectionApprovalMapper inspectionApprovalMapper;
    @Resource
    private com.evops.coldchain.service.LockedPriceService lockedPriceService;

    public Map<String, Object> batchLedger(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        Drug drug = drugMapper.selectById(batch.getDrugId());
        Route route = batch.getRouteId() == null ? null : routeMapper.selectById(batch.getRouteId());
        RouteVersion routeVersion = batch.getRouteVersionId() == null
                ? null : routeVersionMapper.selectById(batch.getRouteVersionId());
        // 批次持有的路线温区分段快照（发运时固化，验收可逐段追到 t_cc_route_segment_snap）
        List<RouteSegmentSnap> routeSegmentSnaps = batch.getRouteVersionId() == null
                ? new ArrayList<>() : routeService.listSnapshotsByBatch(batchId);

        List<TemperatureLog> temps = batchService.listTemperatures(batchId);
        List<ExceptionEvent> exceptions = batchService.listExceptions(batchId);

        List<Map<String, Object>> exceptionList = new ArrayList<>();
        for (ExceptionEvent event : exceptions) {
            List<ExceptionEvidence> evidences = evidenceMapper.selectList(new QueryWrapper<ExceptionEvidence>()
                    .eq("exception_id", event.getId())
                    .orderByAsc("id"));
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("exception", event);
            em.put("evidences", evidences);
            // V6：分级处置时钟 + 升级通知发送记录（含重试/失败原因）+ 复核记录
            em.put("grade", event.getGrade());
            em.put("gradeName", event.getGradeName());
            em.put("foundTime", event.getFoundTime());
            em.put("confirmTime", event.getConfirmTime());
            em.put("responseDueTime", event.getResponseDueTime());
            em.put("resolveDueTime", event.getResolveDueTime());
            em.put("escalated", event.getEscalated());
            em.put("escalateTime", event.getEscalateTime());
            em.put("reviewStatus", event.getReviewStatus());
            em.put("notifyLogs", exceptionLifecycleService.listNotifications(event.getId()));
            em.put("reviewLogs", exceptionLifecycleService.listReviews(event.getId()));
            exceptionList.add(em);
        }

        List<OpLog> opLogs = opLogService.listByBatch(batchId);

        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("batch", batch);
        ledger.put("order", order);
        ledger.put("vehicle", vehicle);
        ledger.put("drug", drug);
        // 路线温区分段：版本引用 + 发运时固化的逐段快照（温度判定/责任追溯以此为准）
        if (batch.getRouteVersionId() != null) {
            Map<String, Object> routeInfo = new LinkedHashMap<>();
            routeInfo.put("route", route);
            routeInfo.put("version", routeVersion);
            routeInfo.put("season", batch.getRouteSeason());
            routeInfo.put("frozenVersionNo", batch.getRouteVersionNo());
            routeInfo.put("segmentSnapshots", routeSegmentSnaps);
            ledger.put("route", routeInfo);
        }
        // 版本对照：核对依据以批次快照为准，drug.currentVersion 仅展示档案已演进到的版本
        Map<String, Object> versionCheck = new LinkedHashMap<>();
        versionCheck.put("batchFrozenVersion", batch.getDrugVersionNo());
        versionCheck.put("drugCurrentVersion", drug == null ? null : drug.getCurrentVersion());
        versionCheck.put("checkByFrozenVersion", true);
        ledger.put("versionCheck", versionCheck);
        // 车载探头校准凭证快照（发运时固化；途中替换探头/作废凭证均不改变本批次）
        if (batch.getProbeId() != null) {
            Map<String, Object> probeInfo = new LinkedHashMap<>();
            probeInfo.put("probeId", batch.getProbeId());
            probeInfo.put("bindId", batch.getProbeBindId());
            probeInfo.put("certId", batch.getProbeCertId());
            probeInfo.put("serialNo", batch.getProbeSerialNoSnap());
            probeInfo.put("rangeMin", batch.getProbeRangeMinSnap());
            probeInfo.put("rangeMax", batch.getProbeRangeMaxSnap());
            probeInfo.put("calibrateOrg", batch.getProbeCalibrateOrgSnap());
            probeInfo.put("certNo", batch.getProbeCertNoSnap());
            probeInfo.put("validUntil", batch.getProbeValidUntilSnap());
            probeInfo.put("sha256", batch.getProbeSha256Snap());
            probeInfo.put("certAtShip", batch.getProbeCertId() == null
                    ? null : probeCertMapper.selectById(batch.getProbeCertId()));
            ledger.put("probe", probeInfo);
        }
        ledger.put("temperatures", temps);
        ledger.put("exceptions", exceptionList);

        // V5 温控预警：批次创建时固化的类别模板温区快照 + 本批次全部预警（只追加）
        ledger.put("alertZone", batch.getAlertZoneCode());
        ledger.put("alertZoneSnapshots", batch.getAlertZoneCode() == null
                ? new ArrayList<>()
                : alertSnapMapper.selectList(new QueryWrapper<com.evops.coldchain.entity.BatchAlertZoneSnap>()
                        .eq("batch_id", batchId).orderByAsc("zone_code")));
        ledger.put("tempAlerts", tempAlertMapper.selectList(new QueryWrapper<com.evops.coldchain.entity.TempAlert>()
                .eq("batch_id", batchId).orderByAsc("id")));

        // 分仓交接（V4）：到仓箱数/责任主体/交接进度 + 每张交接单及其封签核验明细。
        // 原运输批次借此可追溯全部分仓节点，责任主体随交接提交转移。
        List<HandoverOrder> handovers = handoverService.listByBatch(batchId);
        List<Map<String, Object>> handoverList = new ArrayList<>();
        for (HandoverOrder ho : handovers) {
            List<HandoverSeal> seals = handoverService.listSeals(ho.getId());
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("order", ho);
            hm.put("seals", seals);
            handoverList.add(hm);
        }
        Map<String, Object> handoverInfo = new LinkedHashMap<>();
        handoverInfo.put("arrivedBoxQty", batch.getArrivedBoxQty());
        handoverInfo.put("responsibleOrg", batch.getResponsibleOrg());
        handoverInfo.put("responsibleParty", batch.getResponsibleParty());
        handoverInfo.put("progress", handoverService.progress(batchId));
        handoverInfo.put("handovers", handoverList);
        handoverInfo.put("coldChainPermissionPermitted",
                permissionService.isPermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER));
        ledger.put("handovers", handoverInfo);
        // V6：未关闭异常计数（签收/结算卡口口径）与结算记账
        ledger.put("unclosedExceptionCount", exceptionLifecycleService.unclosedCount(batchId));
        Map<String, Object> settleInfo = new LinkedHashMap<>();
        settleInfo.put("settleTime", batch.getSettleTime());
        settleInfo.put("settleBy", batch.getSettleBy());
        settleInfo.put("settleRemark", batch.getSettleRemark());
        settleInfo.put("settled", batch.getSettleTime() != null);
        ledger.put("settlement", settleInfo);

        // V7 运输抽检：监管区域 + 抽检结论回写（独立列，原始运输记录未改动）
        Map<String, Object> inspectionInfo = new LinkedHashMap<>();
        inspectionInfo.put("superviseRegion", batch.getSuperviseRegion());
        inspectionInfo.put("inspectionResult", batch.getInspectionResult());
        inspectionInfo.put("inspectionConclusion", batch.getInspectionConclusion());
        inspectionInfo.put("inspectionBy", batch.getInspectionBy());
        inspectionInfo.put("inspectionTime", batch.getInspectionTime());
        // 抽检任务 -> 样本（唯一封存码/采样人/位置/数量/封存照片）-> 双人转移链，全程可追溯
        List<com.evops.coldchain.entity.InspectionTask> inspectTasks = inspectionTaskMapper.selectList(
                new QueryWrapper<com.evops.coldchain.entity.InspectionTask>()
                        .eq("batch_id", batchId).orderByAsc("id"));
        List<Map<String, Object>> inspectTaskList = new ArrayList<>();
        for (com.evops.coldchain.entity.InspectionTask t : inspectTasks) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("task", t);
            List<com.evops.coldchain.entity.InspectionSample> samples = inspectionSampleMapper.selectList(
                    new QueryWrapper<com.evops.coldchain.entity.InspectionSample>()
                            .eq("task_id", t.getId()).orderByAsc("sample_seq"));
            List<Map<String, Object>> sampleList = new ArrayList<>();
            for (com.evops.coldchain.entity.InspectionSample s : samples) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("sample", s);
                sm.put("transfers", sampleTransferMapper.selectList(
                        new QueryWrapper<com.evops.coldchain.entity.SampleTransfer>()
                                .eq("sample_id", s.getId()).orderByAsc("transfer_seq")));
                sampleList.add(sm);
            }
            tm.put("samples", sampleList);
            inspectTaskList.add(tm);
        }
        inspectionInfo.put("tasks", inspectTaskList);
        // 冷链运输审批链（申请 -> 同意/驳回；PENDING 表示只完成一半停留待处理）
        inspectionInfo.put("approvals", inspectionApprovalMapper.selectList(
                new QueryWrapper<com.evops.coldchain.entity.InspectionApproval>()
                        .eq("batch_id", batchId).orderByAsc("id")));
        ledger.put("inspection", inspectionInfo);

        // V8 承运商报价比选与锁价：批次引用的锁价单及其原报价反查链（原单/处理人/异常原因）
        if (batch.getLockedPriceId() != null) {
            ledger.put("lockedPrice", lockedPriceService.trace(batch.getLockedPriceId()));
        }

        ledger.put("opLogs", opLogs);
        return ledger;
    }
}
