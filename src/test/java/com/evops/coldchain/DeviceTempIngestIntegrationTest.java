package com.evops.coldchain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.DeviceManualIngestRequest;
import com.evops.coldchain.dto.DeviceRegisterRequest;
import com.evops.coldchain.dto.DeviceRevokeRequest;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.Device;
import com.evops.coldchain.entity.DeviceIngestBatch;
import com.evops.coldchain.entity.DeviceReplayAudit;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TempBatchQuarantine;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.DeviceIngestBatchMapper;
import com.evops.coldchain.mapper.DeviceMapper;
import com.evops.coldchain.mapper.DeviceReplayAuditMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.TempBatchQuarantineMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.service.DeviceRejectReason;
import com.evops.coldchain.service.DeviceService;
import com.evops.coldchain.service.DeviceSignatureService;
import com.evops.coldchain.service.DeviceTempIngestService;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertInfo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链 V9 验收：车载设备温度数据接入。
 *
 * 覆盖：
 * 1) 设备证书登记、签名批量上报：按“设备序列+采样时间”去重，重复批次回第一次结果（replay），
 *    补传/补录/重试对同一测点只形成一条温度结果；同 clientBatchNo 换载荷拒绝；
 * 2) 断网批量补传后确定性对账：不重复开预警/异常，已 QA 复核关闭的异常结论保持 RESOLVED 不被改变；
 * 3) 无类别模板批次走 V1 快照温区对账：补传不重复登记温度异常；
 * 4) 已签收批次补传：只追加温度留痕，不评估、不重开结论、签收事实不变；
 * 5) 验签失败整批隔离（证书未备案/签名篡改/序列不符/设备作废/证书过期），接口返回可读原因码+说明；
 * 6) 运维只能重放未入账数据：失败重放留 FAILED 审计且隔离单保留；登记设备后重放成功释放；
 *    已释放再次重放被拒绝并留 REJECTED 审计；
 * 7) 人工补录与设备上报跨通道去重；Shiro 权限：设备入口免登录、补录/查看/重放/登记 401/403/200。
 */
@SuppressWarnings("restriction")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeviceTempIngestIntegrationTest {

    private static final String BASE = "/api/cold-chain";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 与服务端 DeviceSignatureService 规范化报文的采样时间口径一致（固定到秒） */
    private static final java.time.format.DateTimeFormatter POINT_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;

    @Autowired private DeviceService deviceService;
    @Autowired private DeviceTempIngestService ingestService;
    @Autowired private DrugService drugService;
    @Autowired private OrderService orderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;

    @Autowired private DeviceMapper deviceMapper;
    @Autowired private DeviceIngestBatchMapper ingestMapper;
    @Autowired private TempBatchQuarantineMapper quarantineMapper;
    @Autowired private DeviceReplayAuditMapper replayAuditMapper;
    @Autowired private TemperatureLogMapper temperatureMapper;
    @Autowired private ExceptionEventMapper exceptionMapper;
    @Autowired private com.evops.coldchain.mapper.TransportBatchMapper transportBatchMapper;
    @Autowired private com.evops.coldchain.mapper.TempAlertMapper tempAlertMapper;
    @Autowired private com.evops.coldchain.service.DrugCategoryService categoryService;

    // ====================================================================
    // 1) 签名批量上报 + 设备序列/采样时间去重 + 批幂等 + 换载荷拒绝
    // ====================================================================
    @Test
    void signedBatch_dedupByDeviceSerialAndSampleTime_idempotentReplay_payloadChangeRejected() {
        long nano = System.nanoTime();
        DeviceKit kit = newDeviceKit("DEV-A-" + nano, "沪A·冷" + Math.abs(nano % 100000));

        LocalDateTime t = LocalDateTime.now().withNano(0).plusMinutes(1);
        // 设备已绑车并存在在途批次，首批 3 个测点全部新入账（设备断网后首次批量补传同入口）
        createShippedBatchForDevice(kit, "去重批次-" + nano);
        JsonNode r1Node = postIngress(ingestBody(kit, "B1-" + nano, Arrays.asList(
                point(t, "5.1", null), point(t.plusMinutes(5), "5.2", null),
                point(t.plusMinutes(10), "5.3", null)), kit.privateKey, kit.serial));
        assertTrue(r1Node.path("success").asBoolean());
        assertEquals(3, r1Node.path("data").path("insertedCount").asInt());
        assertEquals(3, deviceLogs(kit.serial).size());

        // 重发完全相同批次（设备断网重试）：success=true，replay=true，不新增留痕
        JsonNode replay = postIngress(ingestBody(kit, "B1-" + nano, Arrays.asList(
                point(t, "5.1", null), point(t.plusMinutes(5), "5.2", null),
                point(t.plusMinutes(10), "5.3", null)), kit.privateKey, kit.serial));
        assertTrue(replay.path("success").asBoolean());
        assertTrue(replay.path("data").path("replay").asBoolean());
        assertEquals(3, deviceLogs(kit.serial).size());

        // 第二批：1 个旧测点（去重）+ 1 个新测点
        JsonNode r2 = postIngress(ingestBody(kit, "B2-" + nano, Arrays.asList(
                point(t, "5.1", null), point(t.plusMinutes(15), "5.4", null)), kit.privateKey, kit.serial));
        assertTrue(r2.path("success").asBoolean());
        assertEquals(1, r2.path("data").path("insertedCount").asInt());
        assertEquals(1, r2.path("data").path("duplicateCount").asInt());
        assertEquals(4, deviceLogs(kit.serial).size(), "同一设备序列+采样时间只能有一条温度结果");

        // 同 clientBatchNo 换载荷（改温度）：拒绝并入隔离，原因码 BATCH_PAYLOAD_CHANGED
        JsonNode changed = postIngress(ingestBody(kit, "B1-" + nano, Arrays.asList(
                point(t, "9.9", null)), kit.privateKey, kit.serial));
        assertFalse(changed.path("success").asBoolean());
        assertTrue(changed.path("message").asText().contains("BATCH_PAYLOAD_CHANGED"));
        assertEquals(DeviceRejectReason.BATCH_PAYLOAD_CHANGED,
                changed.path("data").path("reasonCode").asText());
        // 首次结果不被污染：原测点温度不变
        TemperatureLog first = deviceLogs(kit.serial).get(0);
        assertEquals(0, first.getTemperature().compareTo(new BigDecimal("5.1")));
    }

    // ====================================================================
    // 2) 断网补传不改变已 QA 复核关闭的异常结论（V1 快照温区对账路径）
    // ====================================================================
    @Test
    void offlineBackfill_afterQaReviewedConclusion_keepsOneResolvedException() {
        long nano = System.nanoTime() + 1;
        DeviceKit kit = newDeviceKit("DEV-B-" + nano, "沪B·冷" + Math.abs(nano % 100000));
        long batchId = createShippedBatchForDevice(kit, "补传疫苗-" + nano);
        // 样本时间锚定真实发运时刻，保证落在 dispatch~sign 窗口内可归属
        LocalDateTime base = batchService.requireBatch(batchId).getShipTime().plusMinutes(15).withNano(0);

        // 实时上报越限点（2~8℃ 快照温区：首个越限即登记温度异常并确认启钟）
        sendSignedRaw(kit, "LIVE-" + nano, Arrays.asList(
                point(base, "9.0", null), point(base.plusMinutes(20), "9.2", null),
                point(base.plusMinutes(40), "9.1", null)));
        List<ExceptionEvent> excs = exceptions(batchId);
        assertEquals(1, excs.size());
        Long excId = excs.get(0).getId();
        assertEquals(CcConstants.EXC_CONFIRMED, excs.get(0).getStatus());

        // QA 复核闭环：追加证据 -> 提交引用证据的处置结论 -> QA 复核通过
        EvidenceAppendRequest ev = new EvidenceAppendRequest();
        ev.setExceptionId(excId);
        ev.setFileName("处置.png");
        ev.setFileUrl("/v9/ev");
        ev.setNote("回温处置记录");
        Long evidenceId = appendEvidence(ev);
        lifecycleService.conclude(excId, "补传前已完成处置，药品质量无影响",
                Collections.singletonList(evidenceId));
        ExceptionEvent reviewing = exceptionMapper.selectById(excId);
        assertEquals(CcConstants.EXC_REVIEWING, reviewing.getStatus());
        ResponseEntity<String> review = exchange("/exceptions/" + excId + "/review", HttpMethod.POST,
                mapOf("approved", true, "reviewNote", "证据齐备，同意关闭"), "qa", String.class);
        assertEquals(HttpStatus.OK, review.getStatusCode());
        assertEquals(CcConstants.EXC_RESOLVED, exceptionMapper.selectById(excId).getStatus());

        // 断网补传：更早的“迟到测点”与同次越限窗内多个测点（全部越限）
        sendSignedRaw(kit, "FILL-" + nano, Arrays.asList(
                point(base.minusMinutes(10), "9.4", null),
                point(base.plusMinutes(12), "9.3", null),
                point(base.plusMinutes(28), "9.0", null),
                point(base.plusMinutes(33), "9.0", null)));

        // 温度测点 3+4=7 条（同序列+采样时间各一条），异常仍只有一条且保持 QA 关闭结论
        assertEquals(7, deviceLogs(kit.serial).size());
        List<ExceptionEvent> after = exceptions(batchId);
        assertEquals(1, after.size(), "补传不得重复登记温度异常");
        ExceptionEvent resolved = after.get(0);
        assertEquals(excId, resolved.getId());
        assertEquals(CcConstants.EXC_RESOLVED, resolved.getStatus(), "补传不得改变已审核结论");
        assertEquals(CcConstants.REVIEW_APPROVED, resolved.getReviewStatus());
        assertNotNull(resolved.getReviewTime());
        assertTrue(resolved.getConclusion().contains("补传前已完成处置"));
        // 批次不被补传重新挂起
        assertEquals(CcConstants.BATCH_EXCEPTION, batchService.requireBatch(batchId).getStatus());
    }

    // ====================================================================
    // 2b) 断网补传不重复产生 V5 预警、不改变已复核关闭结论（时间窗重叠去重）
    // ====================================================================
    @Test
    void offlineBackfill_v5Alert_staysOneAlertAndResolved() {
        long nano = System.nanoTime() + 7;
        DeviceKit kit = newDeviceKit("DEV-E-" + nano, "沪E·冷" + Math.abs(nano % 100000));
        Long categoryId = createAlertCategory("V9补传类-" + nano);
        long batchId = createShippedBatchForDevice(kit, "V5补传疫苗-" + nano, categoryId);
        LocalDateTime base = batchService.requireBatch(batchId).getShipTime().plusMinutes(15).withNano(0);

        // 实时：08:00/08:20/08:40 连续超限（>容忍30分）→ 1 条预警 + 1 条联动温度异常
        sendSignedRaw(kit, "V5LIVE-" + nano, Arrays.asList(
                point(base, "9.0", null), point(base.plusMinutes(20), "9.2", null),
                point(base.plusMinutes(40), "9.1", null)));
        assertEquals(1, tempAlerts(batchId).size());
        ExceptionEvent exc = exceptions(batchId).get(0);
        Long excId = exc.getId();

        // QA 复核关闭联动温度异常
        EvidenceAppendRequest ev = new EvidenceAppendRequest();
        ev.setExceptionId(excId);
        ev.setFileName("e.png");
        ev.setFileUrl("/v9/e");
        ev.setNote("n");
        Long evId = appendEvidence(ev);
        lifecycleService.conclude(excId, "V5补传前已闭环", Collections.singletonList(evId));
        ResponseEntity<String> v5review = exchange("/exceptions/" + excId + "/review", HttpMethod.POST,
                mapOf("approved", true, "reviewNote", "同意关闭"), "qa", String.class);
        assertEquals(HttpStatus.OK, v5review.getStatusCode());

        // 补传更早起点的越限点：重放会把连续段起点前移，与既有预警时间窗重叠 → 不新增预警/异常
        sendSignedRaw(kit, "V5FILL-" + nano, Arrays.asList(
                point(base.minusMinutes(5), "9.5", null),
                point(base.plusMinutes(25), "9.0", null)));
        assertEquals(1, tempAlerts(batchId).size(), "补传不得产生重复预警");
        assertEquals(1, exceptions(batchId).size());
        assertEquals(CcConstants.EXC_RESOLVED, exceptionMapper.selectById(excId).getStatus());
    }

    private List<TempAlert> tempAlerts(long batchId) {
        return tempAlertMapper.selectList(new QueryWrapper<TempAlert>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    private Long createAlertCategory(String name) {
        com.evops.coldchain.dto.CategoryCreateRequest cc =
                new com.evops.coldchain.dto.CategoryCreateRequest();
        cc.setCategoryName(name);
        return categoryService.createCategory(cc).getId();
    }

    // ====================================================================
    // 3) 已签收批次补传：只留痕，不评估、不重开结论
    // ====================================================================
    @Test
    void backfillAfterSigned_onlyAppended_noReopen() {
        long nano = System.nanoTime() + 2;
        DeviceKit kit = newDeviceKit("DEV-C-" + nano, "沪C·冷" + Math.abs(nano % 100000));
        long batchId = createShippedBatchForDevice(kit, "签收后补传-" + nano, null);
        // 样本时间锚定真实发运时刻（测试中签收紧随发运，下方把签收时刻校正为发运 1 小时后，
        // 以模拟“长时间在途后才签收、设备断网补传途中数据”的真实时序）
        LocalDateTime shipTime = batchService.requireBatch(batchId).getShipTime().withNano(0);
        LocalDateTime inTransit = shipTime.plusSeconds(2);

        // 途中全程达标，签收闭环
        sendSignedRaw(kit, "OK-" + nano, Arrays.asList(
                point(inTransit.plusSeconds(5), "5.0", null),
                point(inTransit.plusSeconds(10), "5.1", null)));
        TransportBatch b = batchService.requireBatch(batchId);
        SignRequest sign = new SignRequest();
        sign.setBatchId(batchId);
        sign.setSignBy("李收货");
        sign.setReceivedApprovalNo(b.getApprovalNoSnap());
        sign.setReceivedPackageSpec(b.getPackageSpecSnap());
        sign.setConfirmQty(b.getShipQty());
        batchService.sign(sign);
        assertEquals(CcConstants.BATCH_SIGNED, batchService.requireBatch(batchId).getStatus());
        // 模拟真实长在途：签收时刻校正为发运 1 小时后（补传点仍在途中窗口内）
        TransportBatch signed = batchService.requireBatch(batchId);
        signed.setSignTime(shipTime.plusHours(1));
        com.evops.coldchain.mapper.TransportBatchMapper bm = transportBatchMapper;
        bm.updateById(signed);

        // 签收后设备断网恢复，补传途中两个测点（一个甚至越限）：只追加留痕，不开异常、不改签收
        long excBefore = exceptionMapper.selectCount(new QueryWrapper<ExceptionEvent>().eq("batch_id", batchId));
        JsonNode resp = postIngress(ingestBody(kit, "LATE-" + nano, Arrays.asList(
                point(inTransit.plusSeconds(7), "5.2", null),
                point(inTransit.plusSeconds(8), "12.0", null)), kit.privateKey, kit.serial));
        assertTrue(resp.path("success").asBoolean());
        assertEquals(2, resp.path("data").path("insertedCount").asInt());

        TransportBatch after = batchService.requireBatch(batchId);
        assertEquals(CcConstants.BATCH_SIGNED, after.getStatus());
        assertNotNull(after.getSignTime());
        long excAfter = exceptionMapper.selectCount(new QueryWrapper<ExceptionEvent>().eq("batch_id", batchId));
        assertEquals(excBefore, excAfter, "已签收批次补传只留痕，不得重新评估开异常");
        assertEquals(4, temperatureMapper.selectCount(new QueryWrapper<TemperatureLog>().eq("batch_id", batchId)).intValue());
    }

    // ====================================================================
    // 4) 验签失败整批隔离 + 可读原因码（未备案/篡改/序列不符/作废/过期）
    // ====================================================================
    @Test
    void verificationFailures_entireBatchQuarantined_withReadableReasons() {
        long nano = System.nanoTime() + 3;

        // a) 证书未备案
        DeviceKit stranger = newDeviceKit("STRANGER-" + nano, null);
        LocalDateTime t = LocalDateTime.now().withNano(0).plusMinutes(1);
        JsonNode r1 = postIngress(ingestBody(stranger, "X1-" + nano,
                Collections.singletonList(point(t, "5.0", null)), stranger.privateKey, stranger.serial));
        assertFalse(r1.path("success").asBoolean());
        assertEquals(DeviceRejectReason.CERT_NOT_REGISTERED, r1.path("data").path("reasonCode").asText());
        assertTrue(r1.path("message").asText().contains("已整批进入隔离队列"));
        Long q1 = r1.path("data").path("quarantineId").asLong();
        assertEquals(CcConstants.QUARANTINED, quarantineMapper.selectById(q1).getStatus());
        assertEquals(0, deviceTemperatureCount(stranger.serial));

        // b) 备案后签名篡改
        DeviceKit kit = newDeviceKit("DEV-D-" + nano, "沪D·冷" + Math.abs(nano % 100000));
        JsonNode r2 = postIngress(ingestBodyWithSignature(kit, "X2-" + nano,
                Collections.singletonList(point(t.plusMinutes(1), "5.0", null)),
                tamperedSignature(kit, "X2-" + nano, t.plusMinutes(1)), kit.serial));
        assertFalse(r2.path("success").asBoolean());
        assertEquals(DeviceRejectReason.SIGNATURE_INVALID, r2.path("data").path("reasonCode").asText());
        assertTrue(r2.path("message").asText().contains("签名验证失败"));

        // c) 报文设备序列与证书备案设备不一致
        JsonNode r3 = postIngress(ingestBody(kit, "X3-" + nano,
                Collections.singletonList(point(t.plusMinutes(2), "5.0", null)),
                kit.privateKey, "OTHER-DEVICE-SERIAL"));
        assertEquals(DeviceRejectReason.DEVICE_SERIAL_MISMATCH, r3.path("data").path("reasonCode").asText());

        // d) 设备作废后报文一律隔离
        DeviceRevokeRequest revoke = new DeviceRevokeRequest();
        revoke.setDeviceId(kit.deviceId);
        revoke.setReason("设备失窃");
        deviceService.revoke(revoke);
        JsonNode r4 = postIngress(ingestBody(kit, "X4-" + nano,
                Collections.singletonList(point(t.plusMinutes(3), "5.0", null)),
                kit.privateKey, kit.serial));
        assertEquals(DeviceRejectReason.DEVICE_REVOKED, r4.path("data").path("reasonCode").asText());

        // e) 证书已过期：登记时允许备案过期证书，接收时按当前时间判定
        DeviceKit expired = newDeviceKit("EXP-" + nano, null,
                LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(1));
        registerDevice(expired, null);
        JsonNode r5 = postIngress(ingestBody(expired, "X5-" + nano,
                Collections.singletonList(point(t.plusMinutes(4), "5.0", null)),
                expired.privateKey, expired.serial));
        assertEquals(DeviceRejectReason.CERT_EXPIRED, r5.path("data").path("reasonCode").asText());

        // 5 条全部隔离，无一条温度入账
        assertEquals(5, quarantineMapper.selectCount(new QueryWrapper<TempBatchQuarantine>()
                .in("client_batch_no", Arrays.asList("X1-" + nano, "X2-" + nano, "X3-" + nano,
                        "X4-" + nano, "X5-" + nano))).intValue());
    }

    // ====================================================================
    // 5) 运维重放：失败留 FAILED 审计；登记设备后重放成功释放；已释放拒绝重放
    // ====================================================================
    @Test
    void replay_onlyUnposted_failedAudited_thenSuccessReleased_thenRejected() {
        long nano = System.nanoTime() + 4;
        DeviceKit kit = newDeviceKit("REPLAY-" + nano, null);
        LocalDateTime t = LocalDateTime.now().withNano(0).plusMinutes(1);

        // 未备案 -> 隔离
        JsonNode q = postIngress(ingestBody(kit, "R1-" + nano, Arrays.asList(
                point(t, "5.0", null), point(t.plusMinutes(5), "5.1", null)),
                kit.privateKey, kit.serial));
        Long quarantineId = q.path("data").path("quarantineId").asLong();

        // 登记设备但暂不绑车：重放可验签通过，但测点不可归属（unmatched=2）仍算“验签通过已处理”，
        // 为贴合“入账”语义，登记同时绑到新车批次车辆上
        Long vehicleId = registerDevice(kit, "沪R·冷" + Math.abs(nano % 100000));
        createShippedBatchForVehicle(vehicleId, "重放批次-" + nano);

        // 先演示失败重放：另取一条签名篡改隔离单
        DeviceKit tamper = newDeviceKit("TAMPER-" + nano, "沪T·冷" + Math.abs(nano % 100000));
        JsonNode qTamper = postIngress(ingestBodyWithSignature(tamper, "R2-" + nano,
                Collections.singletonList(point(t.plusMinutes(2), "5.0", null)),
                tamperedSignature(tamper, "R2-" + nano, t.plusMinutes(2)), tamper.serial));
        Long tamperQid = qTamper.path("data").path("quarantineId").asLong();

        DeviceTempIngestService.ReplayOutcome fail = ingestService.replay(tamperQid);
        assertEquals(CcConstants.REPLAY_FAILED, fail.getAudit().getResult());
        assertEquals(DeviceRejectReason.SIGNATURE_INVALID, fail.getAudit().getReasonCode());
        assertEquals(CcConstants.QUARANTINED, quarantineMapper.selectById(tamperQid).getStatus());
        assertEquals(1, quarantineMapper.selectById(tamperQid).getReplayCount().intValue());
        List<DeviceReplayAudit> failAudits = ingestService.listReplayAudits(tamperQid);
        assertEquals(1, failAudits.size());
        assertEquals("system", failAudits.get(0).getOperator());

        // 正式重放未入账的 R1：验签通过、测点入账、隔离单释放
        DeviceTempIngestService.ReplayOutcome ok = ingestService.replay(quarantineId);
        assertEquals(CcConstants.REPLAY_SUCCESS, ok.getAudit().getResult());
        TempBatchQuarantine released = quarantineMapper.selectById(quarantineId);
        assertEquals(CcConstants.QUARANTINE_RELEASED, released.getStatus());
        assertNotNull(released.getReleasedTime());
        assertEquals(2, ok.getAudit().getInsertedCount().intValue());
        assertEquals(2, deviceTemperatureCount(kit.serial));
        // 报文结果行可反查
        DeviceIngestBatch ingest = ingestMapper.selectById(released.getReleasedIngestId());
        assertEquals("R1-" + nano, ingest.getClientBatchNo());

        // 已释放再次重放：被拒绝，留 REJECTED 审计，不新增温度
        DeviceTempIngestService.ReplayOutcome reject = ingestService.replay(quarantineId);
        assertEquals(CcConstants.REPLAY_REJECTED, reject.getAudit().getResult());
        assertEquals(DeviceRejectReason.REPLAY_ALREADY_RELEASED, reject.getAudit().getReasonCode());
        assertEquals(2, ingestService.listReplayAudits(quarantineId).size());
        assertEquals(2, deviceTemperatureCount(kit.serial), "已入账数据不能被重复重放入账");
    }

    // ====================================================================
    // 5b) 设备先隔离、登记后自行重发成功：历史隔离单自动释放并写 SUCCESS 审计，再重放被拒绝
    // ====================================================================
    @Test
    void deviceResendAfterRegistration_autoReleasesQuarantine_thenReplayRejected() {
        long nano = System.nanoTime() + 8;
        DeviceKit kit = generateKit("SELF-" + nano,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusYears(1));

        // 先建车/批次并绑定到（即将登记的）车辆，拿到真实在途时间窗
        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("沪S·冷" + Math.abs(nano % 100000));
        vr.setCarrierName("V9物流");
        vr.setDriverName("孙司机");
        Long vehicleId = vehicleService.create(vr).getId();
        long batchId = createShippedBatchForVehicle(vehicleId, "自发重放批次-" + nano);
        LocalDateTime t = batchService.requireBatch(batchId).getShipTime().plusMinutes(10).withNano(0);

        // 设备未登记 -> 隔离
        JsonNode q = postIngress(ingestBody(kit, "SELF-R-" + nano,
                Collections.singletonList(point(t, "5.0", null)), kit.privateKey, kit.serial));
        Long qid = q.path("data").path("quarantineId").asLong();
        assertEquals(CcConstants.QUARANTINED, quarantineMapper.selectById(qid).getStatus());
        assertEquals(0, deviceTemperatureCount(kit.serial));

        // 登记设备（不绑车），再换绑到该批次车辆
        registerDevice(kit, null);
        assertNotNull(kit.deviceId);
        com.evops.coldchain.dto.DeviceBindRequest bind = new com.evops.coldchain.dto.DeviceBindRequest();
        bind.setDeviceId(kit.deviceId);
        bind.setVehicleId(vehicleId);
        deviceService.bindVehicle(bind);

        // 设备自行重发同一批（同 clientBatchNo、同载荷、合法签名）：验签通过入账，旧隔离单自动释放
        JsonNode ok = postIngress(ingestBody(kit, "SELF-R-" + nano,
                Collections.singletonList(point(t, "5.0", null)), kit.privateKey, kit.serial));
        assertTrue(ok.path("success").asBoolean(), ok.path("message").asText());
        TempBatchQuarantine released = quarantineMapper.selectById(qid);
        assertEquals(CcConstants.QUARANTINE_RELEASED, released.getStatus());
        assertNotNull(released.getReleasedIngestId());
        assertEquals(1, deviceTemperatureCount(kit.serial));
        List<DeviceReplayAudit> audits = ingestService.listReplayAudits(qid);
        assertEquals(1, audits.size());
        assertEquals(CcConstants.REPLAY_SUCCESS, audits.get(0).getResult());
        assertEquals("system", audits.get(0).getOperator(), "设备免登录重发，操作人记 system");

        // 已释放隔离单再由运维重放：拒绝并追加 REJECTED 审计，不新增温度
        DeviceTempIngestService.ReplayOutcome rejected = ingestService.replay(qid);
        assertEquals(CcConstants.REPLAY_REJECTED, rejected.getAudit().getResult());
        assertEquals(2, ingestService.listReplayAudits(qid).size());
        assertEquals(1, deviceTemperatureCount(kit.serial));
    }

    // ====================================================================
    // 6) 人工补录：跨通道去重 + 未登记拒绝
    // ====================================================================
    @Test
    void manualIngest_dedupAcrossChannels_unregisteredRejected() {
        long nano = System.nanoTime() + 5;
        DeviceKit kit = newDeviceKit("MAN-" + nano, "沪M·冷" + Math.abs(nano % 100000));
        long batchId = createShippedBatchForDevice(kit, "补录批次-" + nano, null);
        LocalDateTime t = LocalDateTime.now().withNano(0).plusMinutes(1);

        // 设备签名先报一点
        sendSignedRaw(kit, "D1-" + nano, Collections.singletonList(point(t, "5.0", null)));
        // 人工补录同一设备同一采样时间：去重，不产生第二条结果
        DeviceManualIngestRequest manual = new DeviceManualIngestRequest();
        manual.setDeviceSerial(kit.serial);
        manual.setClientBatchNo("M1-" + nano);
        manual.setPoints(Collections.singletonList(dpoint(t, "5.0", null)));
        DeviceTempIngestService.IngestResult mr = ingestService.receiveManual(manual);
        assertEquals(0, mr.getInsertedCount());
        assertEquals(1, mr.getDuplicateCount());
        assertEquals(1, deviceTemperatureCount(kit.serial));

        // 人工补录新测点并入同一批次
        manual.setClientBatchNo("M2-" + nano);
        manual.setPoints(Collections.singletonList(dpoint(t.plusMinutes(10), "5.6", null)));
        DeviceTempIngestService.IngestResult mr2 = ingestService.receiveManual(manual);
        assertEquals(1, mr2.getInsertedCount());
        TemperatureLog manualRow = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("device_serial", kit.serial).eq("record_time", t.plusMinutes(10))).get(0);
        assertEquals(CcConstants.TEMP_SOURCE_MANUAL, manualRow.getSourceType());

        // 未登记设备人工补录被拒绝
        DeviceManualIngestRequest bad = new DeviceManualIngestRequest();
        bad.setDeviceSerial("NOT-EXIST-" + nano);
        bad.setPoints(Collections.singletonList(dpoint(t, "5.0", null)));
        boolean thrown = false;
        try {
            ingestService.receiveManual(bad);
        } catch (com.evops.common.BusinessException e) {
            thrown = true;
            assertTrue(e.getMessage().contains(DeviceRejectReason.DEVICE_NOT_FOUND));
        }
        assertTrue(thrown);
    }

    // ====================================================================
    // 7) Shiro 权限：设备入口免登录；登记/查看/补录/重放 401/403/200
    // ====================================================================
    @Test
    void shiroGuards_anonIngress_registerViewManualReplay() {
        long nano = System.nanoTime() + 6;
        LocalDateTime t = LocalDateTime.now().withNano(0).plusMinutes(1);

        // 设备入口免登录：未带 Basic 也可访问（证书未备案 -> HTTP 200 + success=false 隔离）
        DeviceKit kit = generateKit("ANON-" + nano, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusYears(1));
        ResponseEntity<String> anon = exchangeRaw("/device-temperatures", HttpMethod.POST,
                ingestBodyMap(kit, "A1-" + nano, Collections.singletonList(point(t, "5.0", null)),
                        kit.privateKey, kit.serial), null);
        assertEquals(HttpStatus.OK, anon.getStatusCode());
        assertTrue(anon.getBody().contains("\"success\":false"));

        // 未认证访问隔离队列 -> 401
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("/quarantine", HttpMethod.GET, null, null, String.class).getStatusCode());

        // carrier 无任何 V9 权限：查看隔离/设备列表/登记/补录/重放 全部 403
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/quarantine", HttpMethod.GET, null, "carrier", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/devices/list", HttpMethod.GET, null, "carrier", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/devices", HttpMethod.POST,
                        registerBodyMap("P-" + nano, "PEM", null), "carrier", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/device-temperatures/manual", HttpMethod.POST,
                        Collections.emptyMap(), "carrier", String.class).getStatusCode());

        // dispatcher：可查看、可补录；不能登记设备、不能重放
        assertEquals(HttpStatus.OK,
                exchange("/quarantine", HttpMethod.GET, null, "dispatcher", String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                exchange("/devices/list", HttpMethod.GET, null, "dispatcher", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/devices", HttpMethod.POST,
                        registerBodyMap("P2-" + nano, "PEM", null), "dispatcher", String.class).getStatusCode());

        // admin：登记设备 200（真实证书）
        DeviceKit adminKit = generateKit("ADMIN-DEV-" + nano,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusYears(1));
        ResponseEntity<String> registered = exchangeRaw("/devices", HttpMethod.POST,
                registerBodyMap(adminKit.serial, adminKit.certPem, null), "admin");
        assertEquals(HttpStatus.OK, registered.getStatusCode());
        assertTrue(registered.getBody().contains("\"success\":true"));

        // admin 可重放（对任一隔离单；此处重放上面 anon 的未备案单仍失败，但权限放行 200）
        JsonNode anonBody;
        try {
            anonBody = JSON.readTree(anon.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Long qid = anonBody.path("data").path("quarantineId").asLong();
        assertEquals(HttpStatus.OK,
                exchange("/quarantine/" + qid + "/replay", HttpMethod.POST,
                        Collections.emptyMap(), "admin", String.class).getStatusCode());
        // dispatcher 重放 -> 403
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/quarantine/" + qid + "/replay", HttpMethod.POST,
                        Collections.emptyMap(), "dispatcher", String.class).getStatusCode());

        // dispatcher 人工补录路径权限放行（无此设备 -> 业务失败但非 403）
        DeviceManualIngestRequest manual = new DeviceManualIngestRequest();
        manual.setDeviceSerial("ANON-" + nano);
        manual.setPoints(Collections.singletonList(dpoint(t, "5.0", null)));
        assertEquals(HttpStatus.OK,
                exchange("/device-temperatures/manual", HttpMethod.POST, manual,
                        "dispatcher", String.class).getStatusCode());
    }

    // ====================================================================
    // 测试辅助
    // ====================================================================

    /** 一整套设备密钥/证书/序列号（未登记，需 registerDevice） */
    private DeviceKit newDeviceKit(String serial, String plateToRegister) {
        DeviceKit kit = generateKit(serial, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusYears(1));
        Long vehicleId = plateToRegister == null ? null : registerDevice(kit, plateToRegister);
        kit.boundVehicleId = vehicleId;
        return kit;
    }

    private DeviceKit newDeviceKit(String serial, String plateToRegister,
                                   LocalDateTime notBefore, LocalDateTime notAfter) {
        DeviceKit kit = generateKit(serial, notBefore, notAfter);
        kit.boundVehicleId = plateToRegister == null ? null : registerDevice(kit, plateToRegister);
        return kit;
    }

    private DeviceKit generateKit(String serial, LocalDateTime notBefore, LocalDateTime notAfter) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            X509Certificate cert = selfSigned(kp, "CN=" + serial + ",O=EVOPS",
                    java.sql.Timestamp.valueOf(notBefore), java.sql.Timestamp.valueOf(notAfter));
            DeviceKit kit = new DeviceKit();
            kit.serial = serial;
            kit.keyPair = kp;
            kit.privateKey = kp.getPrivate();
            kit.cert = cert;
            kit.certPem = toPem(cert);
            return kit;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 登记设备并备案证书；plate 非空时同时建车并安装，返回车辆ID */
    private Long registerDevice(DeviceKit kit, String plate) {
        Long vehicleId = null;
        if (plate != null) {
            VehicleCreateRequest vr = new VehicleCreateRequest();
            vr.setPlateNo(plate);
            vr.setCarrierName("V9物流");
            vr.setDriverName("赵司机");
            vehicleId = vehicleService.create(vr).getId();
        }
        DeviceRegisterRequest req = new DeviceRegisterRequest();
        req.setDeviceSerial(kit.serial);
        req.setDeviceModel("TK-900");
        req.setFirmwareVersion("v9.1");
        req.setCertPem(kit.certPem);
        req.setVehicleId(vehicleId);
        Device device = deviceService.register(req);
        kit.deviceId = device.getId();
        return vehicleId;
    }

    /** 建档（categoryId 非空则挂类别并维护 2~8℃ 容忍30 抖动10 的模板）->订单->持证探头->派车->发运 */
    private long createShippedBatchForDevice(DeviceKit kit, String uniq) {
        return createShippedBatchForDevice(kit, uniq, null);
    }

    private long createShippedBatchForDevice(DeviceKit kit, String uniq, Long categoryId) {
        Long vehicleId = kit.boundVehicleId;
        if (vehicleId == null) {
            vehicleId = registerDevice(kit, "沪X·冷" + Math.abs(uniq.hashCode() % 100000));
        }
        return createShippedBatchForVehicle(vehicleId, uniq, categoryId);
    }

    private long createShippedBatchForVehicle(Long vehicleId, String uniq) {
        return createShippedBatchForVehicle(vehicleId, uniq, null);
    }

    private long createShippedBatchForVehicle(Long vehicleId, String uniq, Long categoryId) {
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName(uniq);
        dc.setApprovalNo("国药准字V9-" + Math.abs(uniq.hashCode() % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        dc.setCategoryId(categoryId);
        Long drugId = drugService.create(dc).getId();

        if (categoryId != null) {
            com.evops.coldchain.dto.TemplateSaveRequest tpl =
                    new com.evops.coldchain.dto.TemplateSaveRequest();
            tpl.setInheritParent(true);
            tpl.setChangeReason("V9补传测试模板");
            com.evops.coldchain.dto.ZoneDraftRequest zone =
                    new com.evops.coldchain.dto.ZoneDraftRequest();
            zone.setZoneCode(CcConstants.ZONE_REFRIGERATE);
            zone.setZoneName(CcConstants.ZONE_REFRIGERATE_NAME);
            zone.setTempMin(new BigDecimal("2.00"));
            zone.setTempMax(new BigDecimal("8.00"));
            zone.setTolerateMinutes(30);
            zone.setJitterGapMinutes(10);
            tpl.setZones(Collections.singletonList(zone));
            categoryService.saveTemplate(categoryId, tpl);
        }

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V9验收医院");
        oc.setShipQty(8);
        Long orderId = orderService.create(oc).getId();

        // 发运卡口要求车辆绑定持证探头
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V9-" + Math.abs(uniq.hashCode()));
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V9-" + Math.abs(uniq.hashCode()));
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName("c.txt");
        pc.setCertFileContent("cert " + uniq);
        Long probeId = probeService.createProbe(pc).getId();
        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("bind9-" + uniq);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    private Long appendEvidence(EvidenceAppendRequest ev) {
        return batchService.appendEvidence(ev).getId();
    }

    @Autowired
    private com.evops.coldchain.service.ExceptionLifecycleService lifecycleService;

    // ---------------- 报文构造/签名/发送 ----------------

    private Map<String, Object> point(LocalDateTime time, String temp, BigDecimal mileage) {
        Map<String, Object> p = new LinkedHashMap<>();
        // 协议规定采样时间固定 yyyy-MM-dd'T'HH:mm:ss（真实设备按此格式上送，避免秒为0时被省略）
        p.put("sampleTime", POINT_TIME.format(time));
        p.put("temperature", new BigDecimal(temp));
        if (mileage != null) {
            p.put("mileageKm", mileage);
        }
        return p;
    }

    /** 人工补录 DTO 测点 */
    private com.evops.coldchain.dto.DeviceTempPoint dpoint(LocalDateTime time, String temp, BigDecimal mileage) {
        com.evops.coldchain.dto.DeviceTempPoint p = new com.evops.coldchain.dto.DeviceTempPoint();
        p.setSampleTime(time);
        p.setTemperature(new BigDecimal(temp));
        p.setMileageKm(mileage);
        return p;
    }

    private String sign(String clientBatchNo, String serial, List<Map<String, Object>> points,
                        PrivateKey privateKey) {
        try {
            String canonical = canonical(clientBatchNo, serial, points);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 与服务端 DeviceSignatureService.canonicalPayload 同口径（测试侧构造报文） */
    private String canonical(String clientBatchNo, String serial, List<Map<String, Object>> points) {
        StringBuilder sb = new StringBuilder();
        sb.append(clientBatchNo).append('|').append(serial).append('|');
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            Map<String, Object> p = points.get(i);
            sb.append(p.get("sampleTime")).append(',').append(p.get("temperature")).append(',');
            if (p.get("mileageKm") != null) {
                sb.append(p.get("mileageKm"));
            }
        }
        return sb.toString();
    }

    /** 用“另一个设备私钥”签名，制造与证书公钥不匹配的篡改签名 */
    private String tamperedSignature(DeviceKit victim, String batchNo, LocalDateTime t) {
        DeviceKit attacker = generateKit("attacker-" + batchNo,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusYears(1));
        return sign(batchNo, victim.serial,
                Collections.singletonList(point(t, "5.0", null)), attacker.privateKey);
    }

    private Map<String, Object> ingestBody(DeviceKit kit, String batchNo, List<Map<String, Object>> points,
                                           PrivateKey signKey, String claimSerial) {
        return ingestBodyWithSignature(kit, batchNo, points, sign(batchNo, claimSerial, points, signKey), claimSerial);
    }

    private Map<String, Object> ingestBodyWithSignature(DeviceKit kit, String batchNo,
                                                        List<Map<String, Object>> points,
                                                        String signatureBase64, String claimSerial) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientBatchNo", batchNo);
        body.put("deviceSerial", claimSerial);
        body.put("points", points);
        body.put("signature", signatureBase64);
        body.put("certPem", kit.certPem);
        return body;
    }

    private Map<String, Object> ingestBodyMap(DeviceKit kit, String batchNo, List<Map<String, Object>> points,
                                              PrivateKey signKey, String claimSerial) {
        return ingestBody(kit, batchNo, points, signKey, claimSerial);
    }

    private Map<String, Object> registerBodyMap(String serial, String certPem, Long vehicleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceSerial", serial);
        body.put("deviceModel", "TK-900");
        body.put("certPem", certPem);
        if (vehicleId != null) {
            body.put("vehicleId", vehicleId);
        }
        return body;
    }

    private JsonNode postIngress(Map<String, Object> body) {
        ResponseEntity<String> resp = exchangeRaw("/device-temperatures", HttpMethod.POST, body, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        try {
            return JSON.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 服务直调发送（断言成功） */
    private void sendSignedRaw(DeviceKit kit, String batchNo, List<Map<String, Object>> points) {
        JsonNode node = postIngress(ingestBody(kit, batchNo, points, kit.privateKey, kit.serial));
        assertTrue(node.path("success").asBoolean(), node.path("message").asText());
    }

    private List<TemperatureLog> deviceLogs(String serial) {
        return temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("device_serial", serial).orderByAsc("record_time"));
    }

    private int deviceTemperatureCount(String serial) {
        return temperatureMapper.selectCount(new QueryWrapper<TemperatureLog>()
                .eq("device_serial", serial)).intValue();
    }

    private List<ExceptionEvent> exceptions(long batchId) {
        return exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body,
                                           String user, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        TestRestTemplate template = user == null ? http : http.withBasicAuth(user, user + "123");
        return template.exchange("http://localhost:" + port + BASE + path, method, entity, type);
    }

    private ResponseEntity<String> exchangeRaw(String path, HttpMethod method, Object body, String user) {
        return exchange(path, method, body, user, String.class);
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** JDK8 内部 API 生成自签 X.509 证书（测试用） */
    @SuppressWarnings("restriction")
    private X509Certificate selfSigned(KeyPair kp, String dn, Date notBefore, Date notAfter) throws Exception {
        X500Name owner = new X500Name(dn);
        X509CertInfo info = new X509CertInfo();
        info.set(X509CertInfo.VALIDITY, new CertificateValidity(notBefore, notAfter));
        info.set(X509CertInfo.SERIAL_NUMBER,
                new CertificateSerialNumber(new java.math.BigInteger(64, new SecureRandom())));
        info.set(X509CertInfo.SUBJECT, owner);
        info.set(X509CertInfo.ISSUER, owner);
        info.set(X509CertInfo.KEY, new CertificateX509Key(kp.getPublic()));
        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algo = new AlgorithmId(AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));
        sun.security.x509.X509CertImpl cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(kp.getPrivate(), "SHA256withRSA");
        info.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM,
                cert.get(sun.security.x509.X509CertImpl.SIG_ALG));
        cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(kp.getPrivate(), "SHA256withRSA");
        return cert;
    }

    private String toPem(X509Certificate cert) throws Exception {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    /** 测试设备套件 */
    private static class DeviceKit {
        String serial;
        Long deviceId;
        Long boundVehicleId;
        KeyPair keyPair;
        PrivateKey privateKey;
        X509Certificate cert;
        String certPem;
    }
}
