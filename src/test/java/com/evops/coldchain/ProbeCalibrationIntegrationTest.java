package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.ProbeCert;
import com.evops.coldchain.entity.TempProbe;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.entity.VehicleProbe;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.ProbeBindIdempotentMapper;
import com.evops.coldchain.mapper.ProbeCertMapper;
import com.evops.coldchain.mapper.TempProbeMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleProbeMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车载温度探头校准凭证管理（V3）验收测试。
 *
 * 主线：冷链药品运输先提交有效样本（建档→订单→派车），再经 HTTP 两次重复请求
 * “车载探头校准凭证关联”（同一 requestId），两次返回必须指向同一条绑定，物理表不得
 * 产生第二条结果；随后发运固化凭证快照、温度归属探头；并覆盖过期/作废拦截、替换前后
 * 关系与历史温度不回写、上传摘要确定性、下载/作废审计流水、页面端拦截。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProbeCalibrationIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;

    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;

    @Autowired private TempProbeMapper probeMapper;
    @Autowired private ProbeCertMapper certMapper;
    @Autowired private VehicleProbeMapper bindMapper;
    @Autowired private ProbeBindIdempotentMapper bindReqMapper;
    @Autowired private OpLogMapper opLogMapper;
    @Autowired private TemperatureLogMapper temperatureMapper;
    @Autowired private TransportBatchMapper batchMapper;

    // ====================================================================
    // 1) 有效样本 + 重复关联幂等 + 发运固化 + 温度归属
    // ====================================================================
    @Test
    void validSample_duplicateBindRequest_createsNoSecondResult() throws Exception {
        // —— 先提交有效样本：药品建档 → 车辆 → 发货单 → 派车 ——
        Long vehicleId = createVehicle();
        Long orderId = createOrder();
        Long batchId = dispatch(vehicleId, orderId);

        // 探头建档（序列号/量程/校准机构/有效期 + 首份凭证）
        long probeId;
        String requestId = "idem-" + System.nanoTime();
        {
            Map<String, Object> body = probeBody("SN-IDEM-" + System.nanoTime(),
                    LocalDateTime.now().plusYears(1), "幂等证书内容-v1");
            JsonNode resp = post("/probes", body);
            assertTrue(resp.path("success").asBoolean(), resp.toString());
            probeId = resp.path("data").path("id").asLong();
            assertTrue(resp.path("data").path("probeNo").asText().startsWith("PW"));
        }
        TempProbe probe = probeMapper.selectById(probeId);
        assertNotNull(probe.getCurrentCertId(), "随附凭证后应挂当前凭证");
        ProbeCert cert = certMapper.selectById(probe.getCurrentCertId());
        assertEquals(CcConstants.CERT_VALID, cert.getStatus());
        assertEquals(ProbeService.sha256Hex("幂等证书内容-v1".getBytes(StandardCharsets.UTF_8)),
                cert.getSha256(), "上传后必须按文件内容生成确定的 SHA-256 摘要");
        // 发运前未绑定 → 接口拦截
        JsonNode shipBlocked = post("/batches/" + batchId + "/ship", new LinkedHashMap<>());
        assertStop(shipBlocked, "尚未绑定车载温度探头");

        // —— 第一次请求“车载探头校准凭证关联” ——
        JsonNode first = post("/probes/bind", bindBody(requestId, vehicleId, probeId));
        assertTrue(first.path("success").asBoolean(), first.toString());
        assertTrue(first.path("data").path("firstTime").asBoolean());
        assertFalse(first.path("data").path("replay").asBoolean());
        long bindId = first.path("data").path("bind").path("id").asLong();
        assertTrue(bindId > 0);
        JsonNode previousNode = first.path("data").path("previous");
        assertTrue(previousNode == null || previousNode.isNull(),
                "首次绑定不应有被替换的旧绑定");

        // —— 第二次重复请求（同 requestId、同参数）：不能产生第二条结果 ——
        JsonNode again = post("/probes/bind", bindBody(requestId, vehicleId, probeId));
        assertTrue(again.path("success").asBoolean(), again.toString());
        assertTrue(again.path("data").path("replay").asBoolean(), "重复请求应为幂等回放");
        assertEquals(bindId, again.path("data").path("bind").path("id").asLong(),
                "两次返回必须指向同一条绑定结果");

        // 物理表层面：绑定行只有 1 条、幂等登记只有 1 行
        long bindRows = bindMapper.selectCount(new QueryWrapper<VehicleProbe>()
                .eq("vehicle_id", vehicleId));
        assertEquals(1, bindRows, "重复关联不得产生第二条绑定结果");
        long reqRows = bindReqMapper.selectCount(new QueryWrapper<com.evops.coldchain.entity.ProbeBindIdempotent>()
                .eq("request_id", requestId));
        assertEquals(1, reqRows, "同一 requestId 只能有一条幂等登记");
        // 审计流水只记一次绑定（第二次回放不留痕）
        long bindLogs = countOp(CcConstants.ACT_PROBE_BIND, bindId);
        assertEquals(1, bindLogs, "重复请求不得再写一条审计流水");

        // 同 requestId 换参数 → 拒绝（幂等键不可复用给另一组对象）
        long otherProbe = createProbeOnly("SN-IDEM-X-" + System.nanoTime(), LocalDateTime.now().plusYears(1));
        JsonNode reused = post("/probes/bind", bindBody(requestId, vehicleId, otherProbe));
        assertStop(reused, "已用于另一组");

        // —— 绑定有效探头后发运放行，凭证逐字段快照 ——
        JsonNode ship = post("/batches/" + batchId + "/ship", new LinkedHashMap<>());
        assertTrue(ship.path("success").asBoolean(), ship.toString());
        TransportBatch frozen = batchMapper.selectById(batchId);
        assertEquals(probeId, frozen.getProbeId());
        assertEquals(bindId, frozen.getProbeBindId());
        assertEquals(cert.getId(), frozen.getProbeCertId());
        assertEquals(probe.getSerialNo(), frozen.getProbeSerialNoSnap());
        assertEquals(cert.getCertNo(), frozen.getProbeCertNoSnap());
        assertEquals(cert.getSha256(), frozen.getProbeSha256Snap());
        assertEquals("华东计量院", frozen.getProbeCalibrateOrgSnap());

        // 温度上报归属发运时固化的探头
        Map<String, Object> temp = new LinkedHashMap<>();
        temp.put("batchId", batchId);
        temp.put("recordTime", LocalDateTime.now().toString());
        temp.put("temperature", 5.5);
        temp.put("recordedBy", "车载温控仪");
        JsonNode tempResp = post("/batches/temperatures", temp);
        assertTrue(tempResp.path("success").asBoolean(), tempResp.toString());
        long tempLogId = tempResp.path("data").path("id").asLong();
        TemperatureLog tl = temperatureMapper.selectById(tempLogId);
        assertEquals(probeId, tl.getProbeId());
    }

    // ====================================================================
    // 2) 接口拦截过期/无凭证/已作废设备（页面同样不允许选中/发运）
    // ====================================================================
    @Test
    void expiredOrVoidProbe_isRejected_byApiAndPage() throws Exception {
        Long vehicleId = createVehicle();

        // 2.1 建档即登记一个已过期的凭证：探头存在但不可绑定
        String expiredSerial = "SN-EXP-" + System.nanoTime();
        long expiredId;
        {
            JsonNode r = post("/probes", probeBody(expiredSerial,
                    LocalDateTime.now().minusDays(1), "过期证书内容"));
            assertTrue(r.path("success").asBoolean(), r.toString());
            expiredId = r.path("data").path("id").asLong();
        }
        JsonNode bindExpired = post("/probes/bind",
                bindBody("exp-" + System.nanoTime(), vehicleId, expiredId));
        assertStop(bindExpired, "已过期");

        // 2.2 无凭证探头不能绑定（建档不带证书）
        long noCertId;
        {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("serialNo", "SN-NOCERT-" + System.nanoTime());
            body.put("rangeMin", -40);
            body.put("rangeMax", 60);
            body.put("calibrateOrg", "华东计量院");
            body.put("validUntil", LocalDateTime.now().plusYears(1).toString());
            JsonNode r = post("/probes", body);
            assertTrue(r.path("success").asBoolean(), r.toString());
            noCertId = r.path("data").path("id").asLong();
        }
        assertStop(post("/probes/bind", bindBody("nc-" + System.nanoTime(), vehicleId, noCertId)),
                "尚未登记有效校准凭证");

        // 2.3 有效探头可绑定；证书被作废后发运立即被拦截
        long validId = createProbeOnly("SN-VALID-" + System.nanoTime(), LocalDateTime.now().plusYears(1));
        String reqId = "void-case-" + System.nanoTime();
        assertTrue(post("/probes/bind", bindBody(reqId, vehicleId, validId)).path("success").asBoolean());
        Long orderId = createOrder();
        Long batchId = dispatch(vehicleId, orderId);

        TempProbe validProbe = probeMapper.selectById(validId);
        // 作废当前证书（写审计流水）
        long certDownloadsBefore = countOp(CcConstants.ACT_CERT_VOID, null);
        JsonNode voided = post("/probes/certs/void",
                map("certId", validProbe.getCurrentCertId(), "reason", "发现校准原始记录瑕疵"));
        assertTrue(voided.path("success").asBoolean(), voided.toString());
        long certId = validProbe.getCurrentCertId();
        assertEquals(CcConstants.CERT_VOID, certMapper.selectById(certId).getStatus());
        assertNull(probeMapper.selectById(validId).getCurrentCertId(), "作废当前凭证后应摘除当前凭证");
        assertEquals(certDownloadsBefore + 1, countOp(CcConstants.ACT_CERT_VOID, null));

        assertStop(post("/batches/" + batchId + "/ship", new LinkedHashMap<>()), "校准证书已作废");

        // 重新关联另一支持有效凭证的探头后放行
        long replacement = createProbeOnly("SN-REPLACE-" + System.nanoTime(), LocalDateTime.now().plusYears(1));
        JsonNode rebind = post("/probes/bind",
                bindBody("void-case-rebind-" + System.nanoTime(), vehicleId, replacement));
        assertTrue(rebind.path("success").asBoolean(), rebind.toString());
        assertTrue(post("/batches/" + batchId + "/ship", new LinkedHashMap<>()).path("success").asBoolean());

        // 页面：服务端渲染成功，过期探头在台账中明确标“证书已过期”，且绑定下拉中被禁用
        ResponseEntity<String> page = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/probes", String.class);
        assertEquals(200, page.getStatusCode().value());
        assertTrue(page.getBody().contains("车载温度探头校准凭证管理"));
        assertTrue(page.getBody().contains("证书已过期"));
        assertTrue(page.getBody().contains(expiredSerial), "过期探头应出现在页面台账中");
        // 从绑定下拉中该探头选项的文案位置回看，确认同一 <option> 带 disabled（页面端拦截）
        int serialIdx = page.getBody().indexOf(expiredSerial + " [证书已过期]");
        assertTrue(serialIdx > 0, "过期探头应出现在关联下拉选项中");
        String optionHead = page.getBody().substring(Math.max(0, serialIdx - 200), serialIdx);
        assertTrue(optionHead.contains("value=\"" + expiredId + "\""),
                "下拉选项应指向过期探头ID，实际片段：" + optionHead);
        assertTrue(optionHead.contains("disabled"),
                "过期探头在关联下拉中必须禁用（页面端拦截）");
    }

    // ====================================================================
    // 3) 替换探头保留前后关系；在途批次历史温度不回写
    // ====================================================================
    @Test
    void replaceProbe_keepsChainAndHistoricalTemperatureUntouched() {
        Long vehicleId = createVehicle();

        // 探头 P1 绑定车辆，批次A 发运并报两条温度
        long p1 = createProbeOnly("SN-CHAIN-1-" + System.nanoTime(), LocalDateTime.now().plusYears(1));
        probeService.bindIdempotent(svcBind("chain-1-" + System.nanoTime(), vehicleId, p1));
        Long batchA = dispatch(vehicleId, createOrder());
        batchService.ship(batchA);
        reportTemp(batchA, 5.0, p1);
        reportTemp(batchA, 6.0, p1);

        // 替换为 P2：旧绑定释放并指向新绑定，历史行保留
        long p2 = createProbeOnly("SN-CHAIN-2-" + System.nanoTime(), LocalDateTime.now().plusYears(1));
        ProbeService.ProbeBindOutcome outcome =
                probeService.bindIdempotent(svcBind("chain-2-" + System.nanoTime(), vehicleId, p2));
        assertNotNull(outcome.getPrevious());
        assertEquals(p1, outcome.getPrevious().getProbeId());
        assertNotNull(outcome.getPrevious().getReleasedTime());
        assertEquals(outcome.getBind().getId(), outcome.getPrevious().getReplacedByBindId());

        List<VehicleProbe> history = probeService.bindHistory(vehicleId);
        assertEquals(2, history.size(), "替换不得删除旧绑定行");
        assertEquals(p1, history.get(0).getProbeId());
        assertNotNull(history.get(0).getReleasedTime());
        assertEquals(history.get(1).getId(), history.get(0).getReplacedByBindId());
        assertNull(history.get(1).getReleasedTime(), "新绑定应为当前生效绑定");
        assertEquals(p2, probeService.currentBinding(vehicleId).getProbeId());

        // 批次A 的历史温度仍归属 P1，绝不回写为 P2
        List<TemperatureLog> tempsA = batchService.listTemperatures(batchA);
        assertEquals(2, tempsA.size());
        for (TemperatureLog t : tempsA) {
            assertEquals(p1, t.getProbeId(), "替换探头后历史温度记录不回写");
        }

        // 新批次B 发运后温度归属 P2
        Long batchB = dispatch(vehicleId, createOrder());
        batchService.ship(batchB);
        reportTemp(batchB, 4.5, p2);
        List<TemperatureLog> tempsB = batchService.listTemperatures(batchB);
        assertEquals(1, tempsB.size());
        assertEquals(p2, tempsB.get(0).getProbeId());
        // 批次A 的快照温度探头仍为 P1（台账不受替换影响）
        assertEquals(p1, batchMapper.selectById(batchA).getProbeId());
        assertEquals(p2, batchMapper.selectById(batchB).getProbeId());

        // 替换写的是 PROBE_REPLACE，且前后序列号都在摘要里
        long replaceLogs = countOp(CcConstants.ACT_PROBE_REPLACE, outcome.getBind().getId());
        assertEquals(1, replaceLogs);
    }

    // ====================================================================
    // 4) 证书下载/作废写审计流水；摘要随内容确定；作废凭证仍可下载追溯
    // ====================================================================
    @Test
    void certDigest_downloadAndVoid_areAudited() {
        long probeId = createProbeOnly("SN-AUDIT-" + System.nanoTime(), LocalDateTime.now().plusYears(1));
        TempProbe probe = probeMapper.selectById(probeId);
        Long certId = probe.getCurrentCertId();

        long downloadsBefore = opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_CERT)
                .eq("entity_id", certId)
                .eq("action", CcConstants.ACT_CERT_DOWNLOAD));
        ProbeCert downloaded = probeService.downloadCert(certId);
        assertEquals("审计证书内容-" + probe.getSerialNo(), downloaded.getFileContent());
        assertNotNull(certMapper.selectById(certId).getLastDownloadTime());
        long downloadsAfter = opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_CERT)
                .eq("entity_id", certId)
                .eq("action", CcConstants.ACT_CERT_DOWNLOAD));
        assertEquals(downloadsBefore + 1, downloadsAfter, "下载必须写审计流水");

        // 再下载一次：流水逐次追加
        probeService.downloadCert(certId);
        assertEquals(downloadsAfter + 1, opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_CERT)
                .eq("entity_id", certId)
                .eq("action", CcConstants.ACT_CERT_DOWNLOAD)));

        // 作废后文件仍可下载追溯，且下载照常留痕；作废不可重复
        probeService.voidCert(certId, "例行作废");
        assertEquals("审计证书内容-" + probe.getSerialNo(),
                probeService.downloadCert(certId).getFileContent(), "作废不删除证书原文");
        assertEquals(1, opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_CERT)
                .eq("entity_id", certId)
                .eq("action", CcConstants.ACT_CERT_VOID)));
        assertEquals(CcConstants.CERT_VOID, certMapper.selectById(certId).getStatus());
        assertEquals("例行作废", certMapper.selectById(certId).getVoidReason());

        // 上传同内容的新凭证：摘要与旧凭证一致（摘要只取决于文件内容）
        com.evops.coldchain.dto.ProbeCertUploadRequest up =
                new com.evops.coldchain.dto.ProbeCertUploadRequest();
        up.setProbeId(probeId);
        up.setCertNo("CERT-NEW-" + System.nanoTime());
        up.setCalibrateOrg("华北计量院");
        up.setCalibrateDate(LocalDateTime.now().minusDays(2));
        up.setValidUntil(LocalDateTime.now().plusYears(2));
        up.setFileName("new.txt");
        up.setFileContent("审计证书内容-" + probe.getSerialNo());
        ProbeCert newCert = probeService.uploadCert(up);
        assertEquals(certMapper.selectById(certId).getSha256(), newCert.getSha256(),
                "相同文件内容必须生成相同摘要");
        assertEquals(newCert.getId(), probeMapper.selectById(probeId).getCurrentCertId(),
                "新凭证上传后切换为当前凭证；旧凭证行原样保留");
        assertEquals(2, probeService.listCerts(probeId).size(), "校准历史只追加");
    }

    // ====================================================================
    // 5) 建档拦截：序列号重复、量程非法
    // ====================================================================
    @Test
    void probeCreate_duplicateSerialAndBadRange_rejected() {
        String serial = "SN-DUP-" + System.nanoTime();
        long first = createProbeOnly(serial, LocalDateTime.now().plusYears(1));
        assertTrue(first > 0);
        assertStop(post("/probes", probeBody(serial, LocalDateTime.now().plusYears(1), "x")),
                "序列号已登记");

        Map<String, Object> bad = probeBody("SN-BAD-" + System.nanoTime(),
                LocalDateTime.now().plusYears(1), "x");
        bad.put("rangeMin", 60);
        bad.put("rangeMax", -40);
        assertStop(post("/probes", bad), "量程");
    }

    // ---------------- 辅助 ----------------

    private Long createVehicle() {
        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测A·冷" + System.nanoTime() % 1000000);
        vr.setCarrierName("V3验收物流");
        Vehicle v = vehicleService.create(vr);
        return v.getId();
    }

    private Long createOrder() {
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V3验收药品-" + System.nanoTime());
        dc.setApprovalNo("国药准字V3-" + System.nanoTime() % 1000000);
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V3验收医院");
        oc.setShipQty(8);
        return orderService.create(oc).getId();
    }

    private Long dispatch(Long vehicleId, Long orderId) {
        DispatchRequest req = new DispatchRequest();
        req.setOrderId(orderId);
        req.setVehicleId(vehicleId);
        return batchService.dispatch(req).getId();
    }

    private void reportTemp(Long batchId, double temp, long expectedProbeId) {
        com.evops.coldchain.dto.TempReportRequest r = new com.evops.coldchain.dto.TempReportRequest();
        r.setBatchId(batchId);
        r.setRecordTime(LocalDateTime.now());
        r.setTemperature(BigDecimal.valueOf(temp));
        r.setRecordedBy("车载温控仪");
        TemperatureLog log = batchService.reportTemperature(r);
        assertEquals(expectedProbeId, log.getProbeId());
    }

    /** 服务层直接建档一支持证探头（证书内容固定，便于摘要核对） */
    private long createProbeOnly(String serial, LocalDateTime validUntil) {
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo(serial);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(validUntil);
        pc.setCertNo("CERT-" + System.nanoTime());
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(serial + ".txt");
        pc.setCertFileContent("审计证书内容-" + serial);
        return probeService.createProbe(pc).getId();
    }

    private ProbeBindRequest svcBind(String requestId, Long vehicleId, long probeId) {
        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId(requestId);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        return pb;
    }

    private Map<String, Object> probeBody(String serial, LocalDateTime validUntil, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serialNo", serial);
        m.put("model", "TP-200");
        m.put("rangeMin", -40);
        m.put("rangeMax", 60);
        m.put("calibrateOrg", "华东计量院");
        m.put("validUntil", validUntil.toString());
        m.put("certNo", "CERT-" + System.nanoTime());
        // 校准日期固定早于有效期（含“已过期”用例：有效期昨天，校准日期更早）
        m.put("calibrateDate", LocalDateTime.now().minusDays(3).toString());
        m.put("certFileName", serial + ".txt");
        m.put("certFileContent", content);
        return m;
    }

    private Map<String, Object> bindBody(String requestId, Long vehicleId, long probeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("vehicleId", vehicleId);
        m.put("probeId", probeId);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private long countOp(String action, Long entityId) {
        QueryWrapper<OpLog> qw = new QueryWrapper<OpLog>().eq("action", action);
        if (entityId != null) {
            qw.eq("entity_id", entityId);
        }
        Long n = opLogMapper.selectCount(qw);
        return n == null ? 0 : n;
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拦截: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private JsonNode post(String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            String url = "http://localhost:" + port + BASE + path;
            String resp = http.withBasicAuth("dispatcher", "dispatcher123")
                    .postForObject(url, entity, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
