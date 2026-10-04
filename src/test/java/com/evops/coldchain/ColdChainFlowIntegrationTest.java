package com.evops.coldchain;

import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugChangeRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.RevokeRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OpLogService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链台账全链路集成测试（内存 H2）。
 * 重点验证业务不变量：
 * 1) 档案先变更，已派车批次仍按原版本核对；
 * 2) 异常/凭证只追加，不覆盖、不改原描述；
 * 3) 撤回只作废、不删行；
 * 4) 批次号稳定且不重复；
 * 5) 关键操作全部留下操作者与时间。
 *
 * 不加 @Transactional 回滚：需要验证“提交后旧行仍在/未被覆盖”的真实持久化行为。
 */
@SpringBootTest
@ActiveProfiles("test")
class ColdChainFlowIntegrationTest {

    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private TransportBatchService batchService;
    @Autowired private ProbeService probeService;
    @Autowired private OpLogService opLogService;
    @Autowired private com.evops.coldchain.service.ExceptionLifecycleService lifecycle;
    @Autowired private com.evops.coldchain.support.TestSubjects testSubjects;
    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private ExceptionEventMapper exceptionMapper;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 30, 8, 0);

    @Test
    void fullFlow_versionedSnapshot_appendOnly_signoffAndRevoke() {
        // ---------- 1. 建档（V1：2~8℃） ----------
        DrugCreateRequest createReq = new DrugCreateRequest();
        createReq.setDrugName("重组人胰岛素注射液");
        createReq.setApprovalNo("国药准字S20260001");
        createReq.setTempMin(new BigDecimal("2.00"));
        createReq.setTempMax(new BigDecimal("8.00"));
        createReq.setPackageSpec("300IU/笔芯×5支/盒");
        Drug drug = drugService.create(createReq);
        assertNotNull(drug.getId());
        assertTrue(drug.getDrugNo().startsWith("YP"));

        DrugVersion v1 = drugService.requireVersion(drug.getId(), 1);
        assertEquals(CcConstants.EFFECTIVE, v1.getStatus());

        // ---------- 2. 车辆登记 ----------
        VehicleCreateRequest vehicleReq = new VehicleCreateRequest();
        vehicleReq.setPlateNo("京A·冷0001");
        vehicleReq.setCarrierName("华冷物流");
        vehicleReq.setDriverName("张师傅");
        Vehicle vehicle = vehicleService.create(vehicleReq);

        // ---------- 3. 发货单（锁定 V1） ----------
        OrderCreateRequest orderReq = new OrderCreateRequest();
        orderReq.setDrugId(drug.getId());
        orderReq.setReceiverOrg("XX市第一人民医院药学部");
        orderReq.setShipQty(100);
        orderReq.setSenderOrg("XX医药中心仓");
        orderReq.setPlanShipTime(T0);
        ShipmentOrder order = orderService.create(orderReq);
        assertEquals(1, order.getDrugVersionNo());

        // ---------- 4. 档案先变更（V2：温区调整为 2~8 -> 未变，批准文号换发 + 包装变更） ----------
        DrugChangeRequest changeReq = new DrugChangeRequest();
        changeReq.setApprovalNo("国药准字S20260099");
        changeReq.setTempMin(new BigDecimal("2.00"));
        changeReq.setTempMax(new BigDecimal("8.00"));
        changeReq.setPackageSpec("300IU/笔芯×10支/盒");
        changeReq.setChangeReason("批准文号换发并调整中包装");
        DrugVersion v2 = drugService.change(drug.getId(), changeReq);
        assertEquals(2, v2.getVersionNo());
        assertEquals(CcConstants.EFFECTIVE, v2.getStatus());

        // V1 被封存但原值不变
        DrugVersion v1Reload = drugService.requireVersion(drug.getId(), 1);
        assertEquals(CcConstants.SUPERSEDED, v1Reload.getStatus());
        assertEquals("国药准字S20260001", v1Reload.getApprovalNo());
        assertEquals("300IU/笔芯×5支/盒", v1Reload.getPackageSpec());
        assertEquals(0, v1Reload.getTempMin().compareTo(new BigDecimal("2.00")));

        // ---------- 5. 派车：批次必须固化 V1 快照，而非档案现值 V2 ----------
        DispatchRequest dispatchReq = new DispatchRequest();
        dispatchReq.setOrderId(order.getId());
        dispatchReq.setVehicleId(vehicle.getId());
        TransportBatch batch = batchService.dispatch(dispatchReq);

        assertNotNull(batch.getBatchNo());
        assertTrue(batch.getBatchNo().startsWith("PC"));
        assertEquals(CcConstants.BATCH_DISPATCHED, batch.getStatus());
        assertEquals(1, batch.getDrugVersionNo(), "派车时应固化发货单锁定的 V1");
        assertEquals("国药准字S20260001", batch.getApprovalNoSnap());
        assertEquals("300IU/笔芯×5支/盒", batch.getPackageSpecSnap());
        assertEquals(0, batch.getTempMaxSnap().compareTo(new BigDecimal("8.00")));
        assertNotNull(batch.getDispatchTime());
        assertNotNull(batch.getCreateBy());
        assertNotNull(batch.getCreateTime());

        // ---------- 6. 发运：未绑定车载探头必须拦截；绑定持有效凭证的探头后才放行 ----------
        final Long firstBatchId = batch.getId();
        BusinessException noProbe = assertThrows(BusinessException.class,
                () -> batchService.ship(firstBatchId));
        assertTrue(noProbe.getMessage().contains("尚未绑定车载温度探头"));

        Long probeId = bindValidProbe(vehicle.getId(), "req-v1-flow-1", "SN-V1-0001",
                T0.plusYears(1));
        batch = batchService.ship(batch.getId());
        assertEquals(CcConstants.BATCH_SHIPPED, batch.getStatus());
        assertNotNull(batch.getShipTime());
        // 发运瞬间逐字段固化校准凭证快照
        assertEquals(probeId, batch.getProbeId());
        assertEquals("SN-V1-0001", batch.getProbeSerialNoSnap());
        assertEquals("华东计量院", batch.getProbeCalibrateOrgSnap());
        assertNotNull(batch.getProbeCertNoSnap());
        assertNotNull(batch.getProbeSha256Snap());
        assertEquals(64, batch.getProbeSha256Snap().length());
        assertEquals(0, T0.plusYears(1).compareTo(batch.getProbeValidUntilSnap()));

        // ---------- 7. 温度留痕：正常点 + 越限点（越限自动追加异常） ----------
        TempReportRequest okTemp = tempReq(batch.getId(), T0.plusHours(1), new BigDecimal("5.20"));
        TemperatureLog okLog = batchService.reportTemperature(okTemp);
        assertTrue(okLog.getInRange());
        assertEquals(probeId, okLog.getProbeId(), "温度测点应归属发运时固化的探头");

        TempReportRequest badTemp = tempReq(batch.getId(), T0.plusHours(3), new BigDecimal("11.50"));
        TemperatureLog badLog = batchService.reportTemperature(badTemp);
        assertFalse(badLog.getInRange());

        TransportBatch afterExc = batchService.requireBatch(batch.getId());
        assertTrue(afterExc.getHasTempExcursion());
        assertEquals(CcConstants.BATCH_EXCEPTION, afterExc.getStatus());

        List<ExceptionEvent> autoExc = batchService.listExceptions(batch.getId());
        assertEquals(1, autoExc.size());
        assertEquals(CcConstants.EXC_TEMP, autoExc.get(0).getExceptionType());
        // V6：温度超限客观成立即确认启钟（不再是待确认 OPEN），等级 L1，时钟起点=首次发现临界时刻
        assertEquals(CcConstants.EXC_CONFIRMED, autoExc.get(0).getStatus());
        assertEquals(CcConstants.GRADE_L1, autoExc.get(0).getGrade());
        assertEquals(0, autoExc.get(0).getFoundTime().compareTo(T0.plusHours(3)),
                "首次发现时间=越限临界采样时刻");
        assertEquals(0, autoExc.get(0).getConfirmTime().compareTo(T0.plusHours(3)),
                "处置时钟按临界时间同一口径归属");
        assertEquals(0, autoExc.get(0).getResponseDueTime().compareTo(T0.plusHours(3).plusMinutes(30)));
        assertEquals(0, autoExc.get(0).getResolveDueTime().compareTo(T0.plusHours(3).plusMinutes(120)));
        assertEquals(CcConstants.ROLE_QA, autoExc.get(0).getReviewerRoleSnap());
        String originalDescription = autoExc.get(0).getDescription();
        Long autoExcId = autoExc.get(0).getId();

        // 再补一个越限点，验证温度/异常均为追加
        batchService.reportTemperature(tempReq(batch.getId(), T0.plusHours(4), new BigDecimal("9.80")));
        assertEquals(3, batchService.listTemperatures(batch.getId()).size());
        assertEquals(2, batchService.listExceptions(batch.getId()).size());

        // ---------- 8. 人工异常标记 + 两条凭证（追加，不覆盖） ----------
        ExceptionMarkRequest markReq = new ExceptionMarkRequest();
        markReq.setBatchId(batch.getId());
        markReq.setExceptionType(CcConstants.EXC_DAMAGE);
        markReq.setDescription("外包装水渍，2盒轻度破损");
        markReq.setOccurredTime(T0.plusHours(3).plusMinutes(20));
        ExceptionEvent damageExc = batchService.markException(markReq);

        ExceptionEvidence e1 = appendEvidence(damageExc.getId(), "photo-01.jpg", "/v/photo-01", "到货外箱照");
        ExceptionEvidence e2 = appendEvidence(damageExc.getId(), "photo-02.jpg", "/v/photo-02", "破损近照补充");
        assertNotEquals(e1.getId(), e2.getId());

        List<ExceptionEvidence> evidences = batchService.listEvidence(damageExc.getId());
        assertEquals(2, evidences.size(), "凭证必须逐次追加为独立行");
        // 第一条凭证原样保留，未被第二条覆盖
        assertEquals("photo-01.jpg", evidences.get(0).getFileName());
        assertEquals("/v/photo-01", evidences.get(0).getFileUrl());
        assertEquals("到货外箱照", evidences.get(0).getNote());

        // ---------- 9. 存在未关闭异常时签收必须被拦截 ----------
        SignRequest signReq = signReq(batch.getId(), batch);
        BusinessException blocked = assertThrows(BusinessException.class, () -> batchService.sign(signReq));
        assertTrue(blocked.getMessage().contains("未关闭异常"));

        // ---------- 10. 分级温度异常按新闭环关闭：引用证据的处置结论 -> QA 复核 ----------
        // 旧类型 DAMAGE 仍可一句话直接闭环
        ExceptionEvent resolved2 = batchService.resolveException(damageExc.getId(), "破损2盒隔离拒收，其余正常");
        assertEquals(CcConstants.EXC_RESOLVED, resolved2.getStatus());

        // 分级温度异常不能再直接闭环
        BusinessException noDirect = assertThrows(BusinessException.class,
                () -> batchService.resolveException(autoExcId, "一句话试图直接关闭"));
        assertTrue(noDirect.getMessage().contains("分级异常"));

        // 两条温度异常各追加证据、提交引用证据的结论，再由 QA 岗位复核关闭
        List<ExceptionEvent> allExc = batchService.listExceptions(batch.getId());
        for (ExceptionEvent e : allExc) {
            if (CcConstants.EXC_TEMP.equals(e.getExceptionType())
                    && CcConstants.EXC_CONFIRMED.equals(e.getStatus())) {
                appendEvidence(e.getId(), "temp-curve-" + e.getId() + ".csv",
                        "/v/temp-curve-" + e.getId(), "越限时段温控曲线与回温记录");
                Long evidenceId = batchService.listEvidence(e.getId()).get(0).getId();
                lifecycle.conclude(e.getId(), "复核温控曲线，越限时段短暂且已回温，药品评估合格",
                        java.util.Collections.singletonList(evidenceId));
                assertEquals(CcConstants.EXC_REVIEWING, exceptionMapper.selectById(e.getId()).getStatus());
            }
        }
        // 非 QA 角色复核一律拒绝（服务直调无主体时同样拒绝）
        BusinessException notQa = assertThrows(BusinessException.class,
                () -> lifecycle.review(autoExcId, true, "无资格角色试图复核"));
        assertTrue(notQa.getMessage().contains("QA"));

        final long batchIdForReview = batch.getId();
        testSubjects.runAs(6L, "qa", () -> {
            for (ExceptionEvent e : batchService.listExceptions(batchIdForReview)) {
                if (CcConstants.EXC_REVIEWING.equals(e.getStatus())) {
                    lifecycle.review(e.getId(), true, "QA 复核温控曲线与评估结论，证据充分，同意关闭");
                }
            }
        });
        ExceptionEvent resolved1 = exceptionMapper.selectById(autoExcId);
        assertEquals(CcConstants.EXC_RESOLVED, resolved1.getStatus());
        assertEquals(CcConstants.REVIEW_APPROVED, resolved1.getReviewStatus());
        assertNotNull(resolved1.getResolveTime());
        assertNotNull(resolved1.getEvidenceRefs(), "处置结论必须引用证据");
        assertEquals(originalDescription, resolved1.getDescription(),
                "处置/复核动作不得覆盖异常原描述");
        // 凭证仍为两条且内容不变
        assertEquals(2, batchService.listEvidence(damageExc.getId()).size());
        assertEquals(0, lifecycle.unclosedCount(batch.getId()));

        // ---------- 11. 按 V1 快照核对签收：用 V1 值通过，用档案现值 V2 必须失败 ----------
        SignRequest wrongApproval = signReq(batch.getId(), batch);
        wrongApproval.setReceivedApprovalNo("国药准字S20260099"); // 档案现值 V2
        BusinessException mismatch = assertThrows(BusinessException.class, () -> batchService.sign(wrongApproval));
        assertTrue(mismatch.getMessage().contains("批准文号"));

        SignRequest wrongSpec = signReq(batch.getId(), batch);
        wrongSpec.setReceivedPackageSpec("300IU/笔芯×10支/盒"); // 档案现值 V2
        BusinessException specMismatch = assertThrows(BusinessException.class, () -> batchService.sign(wrongSpec));
        assertTrue(specMismatch.getMessage().contains("包装规格"));

        TransportBatch signed = batchService.sign(signReq(batch.getId(), batch));
        assertEquals(CcConstants.BATCH_SIGNED, signed.getStatus());
        assertNotNull(signed.getSignTime());
        assertEquals("李收货", signed.getSignBy());

        // 已签收不可重复签收/不可撤回
        assertThrows(BusinessException.class, () -> batchService.sign(signReq(signed.getId(), signed)));
        RevokeRequest revokeSigned = new RevokeRequest();
        revokeSigned.setBatchId(batch.getId());
        revokeSigned.setReason("误操作");
        assertThrows(BusinessException.class, () -> batchService.revoke(revokeSigned));

        // ---------- 12. 操作日志：关键动作全部有操作者和时间 ----------
        List<OpLog> logs = opLogService.listByBatch(batch.getId());
        assertTrue(logs.size() >= 8, "派车/发运/温度×3/异常标记/凭证×2/处理/签收都应留痕");
        for (OpLog log : logs) {
            assertNotNull(log.getOperator());
            assertNotNull(log.getCreateTime());
        }
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_DISPATCH.equals(l.getAction())));
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_SHIP.equals(l.getAction())));
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_TEMP_REPORT.equals(l.getAction())));
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_SIGN.equals(l.getAction())));

        // ========================================================
        // 第二条批次：验证批次号不重复 + 撤回不删除任何原始记录
        // ========================================================
        OrderCreateRequest order2Req = new OrderCreateRequest();
        order2Req.setDrugId(drug.getId()); // 此时档案当前版本为 V2，新单锁 V2
        order2Req.setReceiverOrg("XX社区卫生服务中心");
        order2Req.setShipQty(20);
        ShipmentOrder order2 = orderService.create(order2Req);
        assertEquals(2, order2.getDrugVersionNo());

        DispatchRequest dispatch2 = new DispatchRequest();
        dispatch2.setOrderId(order2.getId());
        dispatch2.setVehicleId(vehicle.getId());
        TransportBatch batch2 = batchService.dispatch(dispatch2);
        bindValidProbe(vehicle.getId(), "req-v1-flow-2", "SN-V1-0002", T0.plusYears(1));
        batchService.ship(batch2.getId());
        batchService.reportTemperature(tempReq(batch2.getId(), T0.plusHours(1), new BigDecimal("6.00")));

        assertNotEquals(batch.getBatchNo(), batch2.getBatchNo(), "批次号必须稳定且不可重复");

        long tempsBefore = batchService.listTemperatures(batch2.getId()).size();
        long excBefore = batchService.listExceptions(batch2.getId()).size();
        long logsBefore = opLogService.listByBatch(batch2.getId()).size();
        long batchRowsBefore = batchMapper.selectCount(null);

        RevokeRequest revokeReq = new RevokeRequest();
        revokeReq.setBatchId(batch2.getId());
        revokeReq.setReason("客户取消订单");
        TransportBatch revoked = batchService.revoke(revokeReq);
        assertEquals(CcConstants.BATCH_REVOKED, revoked.getStatus());

        // 撤回后原始记录一行不少
        assertEquals(tempsBefore, batchService.listTemperatures(batch2.getId()).size());
        assertEquals(excBefore, batchService.listExceptions(batch2.getId()).size());
        assertEquals(logsBefore + 1, opLogService.listByBatch(batch2.getId()).size(), "撤回本身也要留痕");
        assertEquals(batchRowsBefore, batchMapper.selectCount(null), "撤回不得删除批次行");
        assertNotNull(batchMapper.selectById(batch2.getId()));
        // 撤回后禁止再发运/上报
        assertThrows(BusinessException.class, () -> batchService.ship(batch2.getId()));
        assertThrows(BusinessException.class,
                () -> batchService.reportTemperature(tempReq(batch2.getId(), T0.plusHours(2), new BigDecimal("5.00"))));
    }

    /** 建档一支持证探头并关联到车辆，返回探头ID */
    private Long bindValidProbe(Long vehicleId, String requestId, String serialNo,
                                LocalDateTime validUntil) {
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo(serialNo);
        pc.setModel("TP-100");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(validUntil);
        pc.setCertNo("CERT-" + serialNo);
        pc.setCalibrateDate(T0.minusDays(1));
        pc.setCertFileName(serialNo + ".txt");
        pc.setCertFileContent("calibration certificate of " + serialNo);
        Long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId(requestId);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);
        return probeId;
    }

    private ExceptionEvidence appendEvidence(Long exceptionId, String fileName, String fileUrl, String note) {        EvidenceAppendRequest req = new EvidenceAppendRequest();
        req.setExceptionId(exceptionId);
        req.setFileName(fileName);
        req.setFileUrl(fileUrl);
        req.setNote(note);
        return batchService.appendEvidence(req);
    }

    private TempReportRequest tempReq(Long batchId, LocalDateTime time, BigDecimal temp) {
        TempReportRequest req = new TempReportRequest();
        req.setBatchId(batchId);
        req.setRecordTime(time);
        req.setTemperature(temp);
        req.setRecordedBy("车载温控仪");
        return req;
    }

    private SignRequest signReq(Long batchId, TransportBatch batch) {
        SignRequest req = new SignRequest();
        req.setBatchId(batchId);
        req.setSignBy("李收货");
        req.setReceivedApprovalNo(batch.getApprovalNoSnap());
        req.setReceivedPackageSpec(batch.getPackageSpecSnap());
        req.setConfirmQty(batch.getShipQty());
        return req;
    }
}
