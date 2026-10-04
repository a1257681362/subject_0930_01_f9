package com.evops.coldchain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.DeviceIngestRequest;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.DeviceCert;
import com.evops.coldchain.entity.DeviceIngestBatch;
import com.evops.coldchain.entity.DeviceQuarantine;
import com.evops.coldchain.entity.DeviceReplayLog;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.TempDedup;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.mapper.DeviceCertMapper;
import com.evops.coldchain.mapper.DeviceIngestBatchMapper;
import com.evops.coldchain.mapper.DeviceQuarantineMapper;
import com.evops.coldchain.mapper.DeviceReplayLogMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.TempDedupMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.service.DeviceCertService;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.ExceptionLifecycleService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.coldchain.support.TestSubjects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V9 验收：车载设备温度数据接入。
 *
 * 覆盖：
 * 1) 设备证书登记 + 正常批量上报（签名验证通过，数据入账，dedup 登记）；
 * 2) 断网补传去重（同 device_serial + record_time 只入账一次）；
 * 3) 幂等：同 requestId 重复上报返回第一次结果，不同参数拒绝；
 * 4) 签名不匹配/证书未登记/过期 → 隔离队列 + 可读错误；
 * 5) 重放成功（修复证书后）+ 审计记录 + 已入账不能重复重放；
 * 6) 补传不改变已审核异常结论（alert_exempt=TRUE，原异常不变）；
 * 7) 权限校验（401 未认证 / 403 无权限 / 200 正常）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeviceTemperatureIngestIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 8, 0);

    @LocalServerPort
    private int port;
    @Autowired private TestRestTemplate http;

    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;
    @Autowired private ExceptionLifecycleService lifecycle;
    @Autowired private TestSubjects testSubjects;

    @Autowired private DeviceCertMapper certMapper;
    @Autowired private DeviceIngestBatchMapper ingestBatchMapper;
    @Autowired private DeviceQuarantineMapper quarantineMapper;
    @Autowired private DeviceReplayLogMapper replayLogMapper;
    @Autowired private TempDedupMapper dedupMapper;
    @Autowired private TemperatureLogMapper temperatureMapper;
    @Autowired private ExceptionEventMapper exceptionMapper;

    // ====================================================================
    // 1) 证书登记 + 正常批量上报（签名验证通过，数据入账，dedup 登记）
    // ====================================================================
    @Test
    void certRegisterAndNormalIngest() throws Exception {
        long nano = System.nanoTime();
        String deviceSerial = "SN-V9-" + nano;
        String certContent = "cert-key-" + nano;
        String certFingerprint = DeviceCertService.sha256Hex(certContent);

        // 登记设备证书
        Map<String, Object> certBody = new LinkedHashMap<>();
        certBody.put("deviceSerial", deviceSerial);
        certBody.put("certFingerprint", certFingerprint);
        certBody.put("certContent", certContent);
        certBody.put("validUntil", LocalDateTime.now().plusYears(1).toString());
        JsonNode certResp = postAsAdmin("/device/certs", certBody);
        assertTrue(certResp.path("success").asBoolean(), certResp.toString());

        DeviceCert cert = certMapper.selectOne(new QueryWrapper<DeviceCert>()
                .eq("device_serial", deviceSerial));
        assertNotNull(cert);
        assertEquals(CcConstants.CERT_ACTIVE, cert.getStatus());

        // 创建批次并发运
        long batchId = shipNewBatch(nano);

        // 构建数据点
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(tempPoint(T0.plusHours(1), "5.20"));
        points.add(tempPoint(T0.plusHours(2), "6.10"));
        points.add(tempPoint(T0.plusHours(3), "4.80"));

        // 计算签名
        String canonical = buildCanonical(deviceSerial, batchId, points);
        String signature = DeviceCertService.hmacSha256Hex(canonical, certContent);

        // 上报
        Map<String, Object> ingestBody = new LinkedHashMap<>();
        ingestBody.put("requestId", "req-" + nano);
        ingestBody.put("deviceSerial", deviceSerial);
        ingestBody.put("certFingerprint", certFingerprint);
        ingestBody.put("signature", signature);
        ingestBody.put("batchId", batchId);
        ingestBody.put("points", points);

        JsonNode resp = postAsDevice("/device/temperatures", ingestBody);
        assertTrue(resp.path("success").asBoolean(), resp.toString());
        JsonNode data = resp.path("data");
        assertEquals("POSTED", data.path("status").asText());
        assertEquals(3, data.path("pointCount").asInt());
        assertEquals(3, data.path("acceptedCount").asInt());
        assertEquals(0, data.path("duplicateCount").asInt());
        assertFalse(data.path("replay").asBoolean());

        // 物理表验证：温度留痕 3 条 + dedup 3 条
        List<TemperatureLog> logs = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId).orderByAsc("record_time"));
        assertEquals(3, logs.size());
        assertEquals("BACKFILL", logs.get(0).getSource());
        assertNotNull(logs.get(0).getIngestBatchId());
        assertFalse(logs.get(0).getAlertExempt());

        List<TempDedup> dedups = dedupMapper.selectList(new QueryWrapper<TempDedup>()
                .eq("device_serial", deviceSerial));
        assertEquals(3, dedups.size());

        // 数据批次登记
        DeviceIngestBatch ingestBatch = ingestBatchMapper.selectOne(
                new QueryWrapper<DeviceIngestBatch>().eq("request_id", "req-" + nano));
        assertNotNull(ingestBatch);
        assertEquals("POSTED", ingestBatch.getStatus());
        assertEquals(3, ingestBatch.getAcceptedCount());
    }

    // ====================================================================
    // 2) 断网补传去重（同 device_serial + record_time 只入账一次）
    // ====================================================================
    @Test
    void backfillDedup_sameDeviceSerialAndRecordTime_onlyOneResult() throws Exception {
        long nano = System.nanoTime();
        String deviceSerial = "SN-V9D-" + nano;
        String certContent = "cert-key-d-" + nano;
        String certFingerprint = DeviceCertService.sha256Hex(certContent);
        registerCert(deviceSerial, certFingerprint, certContent);
        long batchId = shipNewBatch(nano);

        // 第一批：T1, T2, T3
        List<Map<String, Object>> batch1 = new ArrayList<>();
        batch1.add(tempPoint(T0.plusHours(1), "5.00"));
        batch1.add(tempPoint(T0.plusHours(2), "6.00"));
        batch1.add(tempPoint(T0.plusHours(3), "7.00"));
        String sig1 = DeviceCertService.hmacSha256Hex(
                buildCanonical(deviceSerial, batchId, batch1), certContent);
        JsonNode resp1 = postAsDevice("/device/temperatures",
                ingestBody("req-d1-" + nano, deviceSerial, certFingerprint, sig1, batchId, batch1));
        assertTrue(resp1.path("success").asBoolean());
        assertEquals(3, resp1.path("data").path("acceptedCount").asInt());

        // 第二批（断网恢复后补传）：T2, T3 重复 + T4 新点
        List<Map<String, Object>> batch2 = new ArrayList<>();
        batch2.add(tempPoint(T0.plusHours(2), "6.00"));
        batch2.add(tempPoint(T0.plusHours(3), "7.00"));
        batch2.add(tempPoint(T0.plusHours(4), "5.50"));
        String sig2 = DeviceCertService.hmacSha256Hex(
                buildCanonical(deviceSerial, batchId, batch2), certContent);
        JsonNode resp2 = postAsDevice("/device/temperatures",
                ingestBody("req-d2-" + nano, deviceSerial, certFingerprint, sig2, batchId, batch2));
        assertTrue(resp2.path("success").asBoolean());
        assertEquals(3, resp2.path("data").path("pointCount").asInt());
        assertEquals(1, resp2.path("data").path("acceptedCount").asInt(), "只有 T4 是新点");
        assertEquals(2, resp2.path("data").path("duplicateCount").asInt(), "T2/T3 重复");

        // 物理表：温度留痕只有 4 条（T1-T4），dedup 4 条
        List<TemperatureLog> logs = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId).orderByAsc("record_time"));
        assertEquals(4, logs.size());
        List<TempDedup> dedups = dedupMapper.selectList(new QueryWrapper<TempDedup>()
                .eq("device_serial", deviceSerial));
        assertEquals(4, dedups.size());
    }

    // ====================================================================
    // 3) 幂等：同 requestId 重复上报返回第一次结果，不同参数拒绝
    // ====================================================================
    @Test
    void idempotent_sameRequestId_returnsFirstResult() throws Exception {
        long nano = System.nanoTime();
        String deviceSerial = "SN-V9I-" + nano;
        String certContent = "cert-key-i-" + nano;
        String certFingerprint = DeviceCertService.sha256Hex(certContent);
        registerCert(deviceSerial, certFingerprint, certContent);
        long batchId = shipNewBatch(nano);

        List<Map<String, Object>> points = new ArrayList<>();
        points.add(tempPoint(T0.plusHours(1), "5.00"));
        String sig = DeviceCertService.hmacSha256Hex(
                buildCanonical(deviceSerial, batchId, points), certContent);
        String requestId = "req-i-" + nano;

        // 第一次上报
        JsonNode first = postAsDevice("/device/temperatures",
                ingestBody(requestId, deviceSerial, certFingerprint, sig, batchId, points));
        assertTrue(first.path("success").asBoolean());
        assertFalse(first.path("data").path("replay").asBoolean());
        long firstIngestId = first.path("data").path("ingestBatchId").asLong();

        // 第二次重复上报（同 requestId、同参数）
        JsonNode again = postAsDevice("/device/temperatures",
                ingestBody(requestId, deviceSerial, certFingerprint, sig, batchId, points));
        assertTrue(again.path("success").asBoolean());
        assertTrue(again.path("data").path("replay").asBoolean(), "重复请求应为幂等回放");
        assertEquals(firstIngestId, again.path("data").path("ingestBatchId").asLong());

        // 物理表：数据批次只有 1 条
        List<DeviceIngestBatch> batches = ingestBatchMapper.selectList(
                new QueryWrapper<DeviceIngestBatch>().eq("request_id", requestId));
        assertEquals(1, batches.size());

        // 同 requestId 不同参数 → 拒绝
        List<Map<String, Object>> differentPoints = new ArrayList<>();
        differentPoints.add(tempPoint(T0.plusHours(5), "9.99"));
        String sigDiff = DeviceCertService.hmacSha256Hex(
                buildCanonical(deviceSerial, batchId, differentPoints), certContent);
        JsonNode rejected = postAsDevice("/device/temperatures",
                ingestBody(requestId, deviceSerial, certFingerprint, sigDiff, batchId, differentPoints));
        assertFalse(rejected.path("success").asBoolean());
        assertTrue(rejected.path("message").asText().contains("不能复用"),
                "应提示 requestId 不能复用: " + rejected.path("message").asText());
    }

    // ====================================================================
    // 4) 签名不匹配/证书未登记/过期 → 隔离队列 + 可读错误
    // ====================================================================
    @Test
    void signatureFailure_goesToQuarantine() throws Exception {
        long nano = System.nanoTime();
        long batchId = shipNewBatch(nano);

        // 4a: 证书未登记
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(tempPoint(T0.plusHours(1), "5.00"));
        Map<String, Object> body1 = ingestBody("req-q1-" + nano, "SN-UNKNOWN-" + nano,
                "fp-unknown", "sig-unknown", batchId, points);
        JsonNode resp1 = postAsDevice("/device/temperatures", body1);
        assertTrue(resp1.path("success").asBoolean(), "隔离也应返回成功（业务结果）");
        assertEquals("QUARANTINED", resp1.path("data").path("status").asText());
        assertEquals("DEVICE_UNKNOWN", resp1.path("data").path("reasonCode").asText());
        assertTrue(resp1.path("data").path("reasonDetail").asText().contains("未登记证书"));

        // 4b: 签名不匹配
        String deviceSerial = "SN-V9Q-" + nano;
        String certContent = "cert-key-q-" + nano;
        String certFingerprint = DeviceCertService.sha256Hex(certContent);
        registerCert(deviceSerial, certFingerprint, certContent);
        Map<String, Object> body2 = ingestBody("req-q2-" + nano, deviceSerial,
                certFingerprint, "wrong-signature", batchId, points);
        JsonNode resp2 = postAsDevice("/device/temperatures", body2);
        assertEquals("QUARANTINED", resp2.path("data").path("status").asText());
        assertEquals("SIGN_MISMATCH", resp2.path("data").path("reasonCode").asText());
        assertTrue(resp2.path("data").path("reasonDetail").asText().contains("签名验证失败"));

        // 4c: 证书过期
        String expiredSerial = "SN-V9E-" + nano;
        String expiredContent = "cert-key-e-" + nano;
        String expiredFp = DeviceCertService.sha256Hex(expiredContent);
        Map<String, Object> expiredCertBody = new LinkedHashMap<>();
        expiredCertBody.put("deviceSerial", expiredSerial);
        expiredCertBody.put("certFingerprint", expiredFp);
        expiredCertBody.put("certContent", expiredContent);
        expiredCertBody.put("validUntil", LocalDateTime.now().plusSeconds(1).toString());
        postAsAdmin("/device/certs", expiredCertBody);
        Thread.sleep(1100); // 等证书过期
        Map<String, Object> body3 = ingestBody("req-q3-" + nano, expiredSerial,
                expiredFp, "any-sig", batchId, points);
        JsonNode resp3 = postAsDevice("/device/temperatures", body3);
        assertEquals("QUARANTINED", resp3.path("data").path("status").asText());
        assertEquals("CERT_EXPIRED", resp3.path("data").path("reasonCode").asText());
        assertTrue(resp3.path("data").path("reasonDetail").asText().contains("已过期"));

        // 隔离队列验证
        List<DeviceQuarantine> quarantines = quarantineMapper.selectList(
                new QueryWrapper<DeviceQuarantine>().orderByAsc("id"));
        assertTrue(quarantines.size() >= 3, "至少 3 条隔离记录");
        assertEquals("PENDING", quarantines.get(0).getStatus());
    }

    // ====================================================================
    // 5) 重放成功（修复证书后）+ 审计记录 + 已入账不能重复重放
    // ====================================================================
    @Test
    void replay_quarantinedData_successWithAudit() throws Exception {
        long nano = System.nanoTime();
        String deviceSerial = "SN-V9R-" + nano;
        String certContent = "cert-key-r-" + nano;
        String certFingerprint = DeviceCertService.sha256Hex(certContent);
        long batchId = shipNewBatch(nano);

        // 先上报（证书未登记 → 隔离）
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(tempPoint(T0.plusHours(1), "5.00"));
        points.add(tempPoint(T0.plusHours(2), "6.00"));
        String canonical = buildCanonical(deviceSerial, batchId, points);
        String signature = DeviceCertService.hmacSha256Hex(canonical, certContent);
        Map<String, Object> body = ingestBody("req-r-" + nano, deviceSerial,
                certFingerprint, signature, batchId, points);
        JsonNode resp = postAsDevice("/device/temperatures", body);
        assertEquals("QUARANTINED", resp.path("data").path("status").asText());
        long quarantineId = resp.path("data").path("quarantineId").asLong();

        // 修复：登记证书
        registerCert(deviceSerial, certFingerprint, certContent);

        // 重放
        Map<String, Object> replayBody = new LinkedHashMap<>();
        replayBody.put("replayReason", "证书已补登记，重放数据");
        JsonNode replayResp = postAsAdmin("/device/quarantine/" + quarantineId + "/replay", replayBody);
        assertTrue(replayResp.path("success").asBoolean(), replayResp.toString());
        JsonNode replayData = replayResp.path("data");
        assertEquals("REPLAYED", replayData.path("status").asText());
        JsonNode ingestResult = replayData.path("ingestResult");
        assertEquals("POSTED", ingestResult.path("status").asText());
        assertEquals(2, ingestResult.path("acceptedCount").asInt());

        // 审计记录
        List<DeviceReplayLog> logs = replayLogMapper.selectList(
                new QueryWrapper<DeviceReplayLog>().eq("quarantine_id", quarantineId));
        assertEquals(1, logs.size());
        assertEquals("SUCCESS", logs.get(0).getResult());
        assertEquals(2, logs.get(0).getPointsPosted());
        assertEquals(1, logs.get(0).getReplaySeq());

        // 隔离状态已更新
        DeviceQuarantine q = quarantineMapper.selectById(quarantineId);
        assertEquals("REPLAYED", q.getStatus());

        // 温度留痕已入账
        List<TemperatureLog> tempLogs = temperatureMapper.selectList(
                new QueryWrapper<TemperatureLog>().eq("batch_id", batchId));
        assertEquals(2, tempLogs.size());

        // 已入账不能重复重放
        Map<String, Object> replayBody2 = new LinkedHashMap<>();
        replayBody2.put("replayReason", "尝试重复重放");
        JsonNode replay2 = postAsAdmin("/device/quarantine/" + quarantineId + "/replay", replayBody2);
        assertFalse(replay2.path("success").asBoolean());
        assertTrue(replay2.path("message").asText().contains("不能重复重放")
                || replay2.path("message").asText().contains("PENDING"));
    }

    // ====================================================================
    // 6) 补传不改变已审核异常结论
    // ====================================================================
    @Test
    void backfill_doesNotChangeReviewedConclusion() throws Exception {
        long nano = System.nanoTime();
        String deviceSerial = "SN-V9B-" + nano;
        String certContent = "cert-key-b-" + nano;
        String certFingerprint = DeviceCertService.sha256Hex(certContent);
        registerCert(deviceSerial, certFingerprint, certContent);
        long batchId = shipNewBatch(nano);

        // 实时上报越限温度 → 触发异常（自动确认）
        reportTempViaService(batchId, T0.plusHours(1), new BigDecimal("11.50"));
        ExceptionEvent tempExc = onlyOpenException(batchId);
        assertEquals(CcConstants.EXC_TEMP, tempExc.getExceptionType());
        assertEquals(CcConstants.EXC_CONFIRMED, tempExc.getStatus());

        // 提交结论 + QA 复核关闭
        appendEvidenceViaService(tempExc.getId());
        Long evId = batchService.listEvidence(tempExc.getId()).get(0).getId();
        lifecycle.conclude(tempExc.getId(), "温度异常已处置，冷链未断",
                java.util.Collections.singletonList(evId));
        testSubjects.runAs(6L, "qa", () -> lifecycle.review(tempExc.getId(), true, "QA 复核通过"));
        ExceptionEvent resolved = lifecycle.requireEvent(tempExc.getId());
        assertEquals(CcConstants.EXC_RESOLVED, resolved.getStatus());
        assertEquals(CcConstants.REVIEW_APPROVED, resolved.getReviewStatus());
        LocalDateTime resolveTime = resolved.getResolveTime();
        assertNotNull(resolveTime);

        // 设备补传：record_time 在已审核窗口内（<= resolveTime）
        List<Map<String, Object>> backfillPoints = new ArrayList<>();
        backfillPoints.add(tempPoint(T0.plusMinutes(30), "10.00")); // 早于异常
        backfillPoints.add(tempPoint(T0.plusHours(1), "11.50"));    // 同异常时刻
        backfillPoints.add(tempPoint(T0.plusHours(2), "9.00"));     // 晚于异常但 <= resolveTime
        String canonical = buildCanonical(deviceSerial, batchId, backfillPoints);
        String signature = DeviceCertService.hmacSha256Hex(canonical, certContent);
        Map<String, Object> body = ingestBody("req-b-" + nano, deviceSerial,
                certFingerprint, signature, batchId, backfillPoints);
        JsonNode resp = postAsDevice("/device/temperatures", body);
        assertTrue(resp.path("success").asBoolean(), resp.toString());
        assertEquals("POSTED", resp.path("data").path("status").asText());
        assertTrue(resp.path("data").path("suppressedCount").asInt() > 0,
                "已审核窗口内的补传点应被标记为保护");

        // 验证：补传点 alert_exempt=TRUE
        List<TemperatureLog> logs = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId).eq("source", "BACKFILL").orderByAsc("record_time"));
        assertFalse(logs.isEmpty());
        for (TemperatureLog log : logs) {
            if (!log.getRecordTime().isAfter(resolveTime)) {
                assertTrue(log.getAlertExempt(),
                        "已审核窗口内的补传点应标记 alert_exempt: " + log.getRecordTime());
            }
        }

        // 验证：原异常结论不变
        ExceptionEvent unchanged = lifecycle.requireEvent(tempExc.getId());
        assertEquals(CcConstants.EXC_RESOLVED, unchanged.getStatus());
        assertEquals(CcConstants.REVIEW_APPROVED, unchanged.getReviewStatus());
        assertEquals(resolved.getConclusion(), unchanged.getConclusion());
        assertEquals(resolved.getReviewNote(), unchanged.getReviewNote());
        assertEquals(0, resolved.getResolveTime().compareTo(unchanged.getResolveTime()));

        // 验证：没有新异常产生（除了原有的）
        List<ExceptionEvent> allExceptions = exceptionMapper.selectList(
                new QueryWrapper<ExceptionEvent>().eq("batch_id", batchId));
        assertEquals(1, allExceptions.size(), "补传不应产生新异常");
    }

    // ====================================================================
    // 7) 权限校验（401 未认证 / 403 无权限 / 200 正常）
    // ====================================================================
    @Test
    void permissionCheck_unauthenticatedAndUnauthorized() throws Exception {
        long nano = System.nanoTime();
        String deviceSerial = "SN-V9P-" + nano;
        String certContent = "cert-key-p-" + nano;
        String certFingerprint = DeviceCertService.sha256Hex(certContent);
        registerCert(deviceSerial, certFingerprint, certContent);
        long batchId = shipNewBatch(nano);

        List<Map<String, Object>> points = new ArrayList<>();
        points.add(tempPoint(T0.plusHours(1), "5.00"));
        String canonical = buildCanonical(deviceSerial, batchId, points);
        String signature = DeviceCertService.hmacSha256Hex(canonical, certContent);
        Map<String, Object> body = ingestBody("req-p-" + nano, deviceSerial,
                certFingerprint, signature, batchId, points);

        // 401: 未认证
        ResponseEntity<String> noAuth = http.postForEntity(
                "http://localhost:" + port + BASE + "/device/temperatures",
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noAuth.getStatusCode());

        // 403: 无权限（receiver 没有 device:ingest 权限）
        ResponseEntity<String> forbidden = http.withBasicAuth("receiver", "receiver123")
                .postForEntity("http://localhost:" + port + BASE + "/device/temperatures",
                        new HttpEntity<>(body, jsonHeaders()), String.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        // 200: device 账号正常上报
        JsonNode ok = postAsDevice("/device/temperatures", body);
        assertTrue(ok.path("success").asBoolean());

        // 403: device 账号不能查看隔离队列
        ResponseEntity<String> deviceForbidden = http.withBasicAuth("device", "device123")
                .getForEntity("http://localhost:" + port + BASE + "/device/quarantine", String.class);
        assertEquals(HttpStatus.FORBIDDEN, deviceForbidden.getStatusCode());

        // 200: admin 可以查看隔离队列
        ResponseEntity<String> adminOk = http.withBasicAuth("admin", "admin123")
                .getForEntity("http://localhost:" + port + BASE + "/device/quarantine", String.class);
        assertEquals(HttpStatus.OK, adminOk.getStatusCode());
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    private long shipNewBatch(long nano) {
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V9验收药品-" + nano);
        dc.setApprovalNo("国药准字V9-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V9验收医院");
        oc.setShipQty(10);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测9·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName("V9验收物流");
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V9-PROBE-" + nano);
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V9-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V9校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v9bind-" + nano);
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

    private void registerCert(String deviceSerial, String certFingerprint, String certContent) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceSerial", deviceSerial);
        body.put("certFingerprint", certFingerprint);
        body.put("certContent", certContent);
        body.put("validUntil", LocalDateTime.now().plusYears(1).toString());
        JsonNode resp = postAsAdmin("/device/certs", body);
        assertTrue(resp.path("success").asBoolean(), resp.toString());
    }

    private Map<String, Object> tempPoint(LocalDateTime recordTime, String temperature) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("recordTime", recordTime.toString());
        p.put("temperature", temperature);
        return p;
    }

    private Map<String, Object> ingestBody(String requestId, String deviceSerial,
                                           String certFingerprint, String signature,
                                           Long batchId, List<Map<String, Object>> points) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("deviceSerial", deviceSerial);
        body.put("certFingerprint", certFingerprint);
        body.put("signature", signature);
        body.put("batchId", batchId);
        body.put("points", points);
        return body;
    }

    private String buildCanonical(String deviceSerial, Long batchId, List<Map<String, Object>> points) {
        List<DeviceIngestRequest.TempPoint> tpList = new ArrayList<>();
        for (Map<String, Object> p : points) {
            DeviceIngestRequest.TempPoint tp = new DeviceIngestRequest.TempPoint();
            tp.setRecordTime(LocalDateTime.parse((String) p.get("recordTime")));
            tp.setTemperature(new BigDecimal((String) p.get("temperature")));
            tpList.add(tp);
        }
        return DeviceCertService.buildCanonicalPayload(deviceSerial, batchId, tpList);
    }

    private void reportTempViaService(Long batchId, LocalDateTime recordTime, BigDecimal temperature) {
        com.evops.coldchain.dto.TempReportRequest req = new com.evops.coldchain.dto.TempReportRequest();
        req.setBatchId(batchId);
        req.setRecordTime(recordTime);
        req.setTemperature(temperature);
        req.setRecordedBy("test");
        batchService.reportTemperature(req);
    }

    private ExceptionEvent onlyOpenException(Long batchId) {
        List<ExceptionEvent> list = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batchId).orderByDesc("id"));
        assertFalse(list.isEmpty(), "应存在异常");
        return list.get(0);
    }

    private void appendEvidenceViaService(Long exceptionId) {
        com.evops.coldchain.dto.EvidenceAppendRequest req = new com.evops.coldchain.dto.EvidenceAppendRequest();
        req.setExceptionId(exceptionId);
        req.setFileName("evidence-" + exceptionId + ".txt");
        req.setFileUrl("/v9/evidence/" + exceptionId);
        req.setNote("V9 补传保护测试证据");
        batchService.appendEvidence(req);
    }

    private JsonNode postAsDevice(String path, Object body) {
        return post(path, body, "device", "device123");
    }

    private JsonNode postAsAdmin(String path, Object body) {
        return post(path, body, "admin", "admin123");
    }

    private JsonNode post(String path, Object body, String user, String pass) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            String url = "http://localhost:" + port + BASE + path;
            String resp = http.withBasicAuth(user, pass).postForObject(url, entity, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
