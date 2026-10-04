package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.InspectionService;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药品运输抽检（V7）验收测试。
 *
 * 覆盖：
 * 1. 按批次立项抽检 -> 封存样本生成唯一封存码（采样人/位置/数量/封存照片留痕）->
 *    转移双人确认（同一账号自确认被拒，第二账号确认才转移）-> 结论回写批次独立列、
 *    不覆盖原始运输记录；结论一次定稿；已封存样本无删除路径；批次台账可追溯全过程。
 * 2. 查询/详情/导出按监管区域隔离（inspector 管 EAST/NORTH，inspector2 仅 SOUTH）。
 * 3. 导出 CSV 对承运人联系方式脱敏（139******78），无导出权限 403。
 * 4. 冷链运输审批只完成一半时停留 PENDING、拦截发运；审批期间抽检/封存照常可办；
 *    审批同意后才放行发运；驳回可重新申请。
 * 5. Shiro 权限点：无抽检权限 403、无审批权限 403。
 *
 * 内置账号：inspector/inspector123（EAST/NORTH，可抽检+导出+审批）、
 * inspector2/inspector123（仅 SOUTH，无导出/审批权）、dispatcher/dispatcher123（申请审批）、
 * admin/admin123（全域，作为双人转移的第二账号）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InspectionSamplingIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    private static final String DISPATCHER = "dispatcher";
    private static final String INSPECTOR = "inspector";
    private static final String INSPECTOR2 = "inspector2";
    private static final String ADMIN = "admin";
    private static final String PW = "dispatcher123";
    private static final String INSPW = "inspector123";
    private static final String ADMINPW = "admin123";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;
    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;
    @Autowired private InspectionService inspectionService;
    @Autowired private TransportBatchMapper batchMapper;

    // ====================================================================
    // 1) 封存唯一码/留痕 + 双人转移 + 结论回写不覆盖原始记录 + 全程可追溯
    // ====================================================================
    @Test
    void seal_dualTransfer_conclude_writesBatchButKeepsTransportRecord() throws Exception {
        long batchId = shipNewBatch("EAST", "13912345678");

        // 监管人立项抽检
        JsonNode taskResp = post(INSPECTOR, INSPW, "/inspections",
                map("batchId", batchId, "superviseRegion", "EAST", "taskName", "在途疫苗随机抽检",
                        "samplingLocation", "沪杭高速嘉兴服务区冷链车车厢", "samplerName", "周监"));
        assertTrue(taskResp.path("success").asBoolean(), taskResp.toString());
        long taskId = taskResp.path("data").path("id").asLong();
        String taskNo = taskResp.path("data").path("taskNo").asText();
        assertTrue(taskNo.startsWith("CJ"));

        // 封存两个样本：唯一封存码 + 采样人/位置/数量/封存照片
        JsonNode s1 = post(INSPECTOR, INSPW, "/inspections/samples/seal", sealBody(taskId, 3,
                "车厢上层A位", "周监", "seal1.jpg", "/v7/seal1", "照片内容-1"));
        JsonNode s2 = post(INSPECTOR, INSPW, "/inspections/samples/seal", sealBody(taskId, 2,
                "车厢下层B位", "吴监", "seal2.jpg", "/v7/seal2", "照片内容-2"));
        assertTrue(s1.path("success").asBoolean(), s1.toString());
        assertTrue(s2.path("success").asBoolean(), s2.toString());
        long sampleId = s1.path("data").path("id").asLong();
        String code1 = s1.path("data").path("sealCode").asText();
        String code2 = s2.path("data").path("sealCode").asText();
        assertTrue(code1.startsWith("FC"), code1);
        assertNotEquals(code1, code2, "封存码必须库内唯一");
        assertEquals("SEALED", s1.path("data").path("status").asText());
        assertEquals("周监", s1.path("data").path("samplerName").asText());
        assertEquals(3, s1.path("data").path("quantity").asInt());
        assertNotNull(s1.path("data").path("sealPhotoSha256").asText());
        assertTrue(s1.path("data").path("sealPhotoSha256").asText().length() > 0);
        assertEquals(64, s1.path("data").path("sealPhotoSha256").asText().length());

        // 已封存样本不提供删除路径：DELETE 无此映射（404，若映射到其他方法则 405），样本仍在
        HttpResp deleteAttempt = rawRequest(INSPECTOR, INSPW, HttpMethod.DELETE,
                "/inspections/samples/" + sampleId, null);
        assertTrue(deleteAttempt.status == 404 || deleteAttempt.status == 405,
                "已封存样本不可删除，系统不应提供 DELETE 路径，实际状态码=" + deleteAttempt.status);

        // 双人转移：移交人发起停留 PENDING
        JsonNode tr = post(INSPECTOR, INSPW, "/inspections/samples/transfers/request",
                map("sampleId", sampleId, "toCustodianName", "检验所收件员郑检",
                        "toCustodianOrg", "市药检所收样室", "handoverLocation", "嘉兴服务区",
                        "transferNote", "全程冷链箱交接"));
        assertTrue(tr.path("success").asBoolean(), tr.toString());
        long transferId = tr.path("data").path("id").asLong();
        assertEquals("PENDING", tr.path("data").path("status").asText());
        assertEquals(INSPECTOR, tr.path("data").path("senderName").asText());

        // 同一账号不能自确认（双人确认卡口）
        JsonNode selfConfirm = post(INSPECTOR, INSPW,
                "/inspections/samples/transfers/" + transferId + "/confirm",
                map("receiverNote", "我自己确认"));
        assertStop(selfConfirm, "双人确认");
        // PENDING 期间不能发起第二次移交
        JsonNode secondRequest = post(INSPECTOR, INSPW, "/inspections/samples/transfers/request",
                map("sampleId", sampleId, "toCustodianName", "另一人", "handoverLocation", "X"));
        assertStop(secondRequest, "尚未完成接收人双人确认");

        // 第二账号（admin 全域）确认 -> CONFIRMED，持有方转移
        JsonNode confirmed = post(ADMIN, ADMINPW,
                "/inspections/samples/transfers/" + transferId + "/confirm",
                map("receiverNote", "封签完好，冷链温度达标"));
        assertTrue(confirmed.path("success").asBoolean(), confirmed.toString());
        assertEquals("CONFIRMED", confirmed.path("data").path("status").asText());
        assertEquals(ADMIN, confirmed.path("data").path("receiverName").asText());
        assertNotNull(confirmed.path("data").path("receiverConfirmTime").asText());
        assertFalse(confirmed.path("data").path("receiverConfirmTime").asText().isEmpty());
        // 重复确认被拒（事实只进不退）
        assertStop(post(ADMIN, ADMINPW,
                "/inspections/samples/transfers/" + transferId + "/confirm",
                map("receiverNote", "再确认")), "不可重复");

        // 样本状态推进为 TRANSFERRED，持有方变更，转移次数 +1
        JsonNode detail = get(INSPECTOR, INSPW, "/inspections/" + taskId);
        JsonNode moved = null;
        for (JsonNode s : detail.path("samples")) {
            if (s.path("sample").path("id").asLong() == sampleId) {
                moved = s;
            }
        }
        assertNotNull(moved);
        assertEquals("TRANSFERRED", moved.path("sample").path("status").asText());
        assertEquals("检验所收件员郑检", moved.path("sample").path("custodianName").asText());
        assertEquals("市药检所收样室", moved.path("sample").path("custodianOrg").asText());
        assertEquals(1, moved.path("sample").path("transferCount").asInt());
        assertEquals(1, moved.path("transfers").size());

        // 出结论前快照原始运输记录
        TransportBatch before = batchMapper.selectById(batchId);
        assertEquals(CcConstants.BATCH_SHIPPED, before.getStatus());
        assertEquals(Integer.valueOf(20), before.getShipQty());
        // 结论回写批次独立列（FAIL），但不改任何原始运输记录
        JsonNode conclude = post(INSPECTOR, INSPW, "/inspections/" + taskId + "/conclude",
                map("result", "FAIL", "conclusion", "包装温度链完整但标签批号与发货单不符，判不合格，移交稽查"));
        assertTrue(conclude.path("success").asBoolean(), conclude.toString());
        TransportBatch after = batchMapper.selectById(batchId);
        assertEquals("FAIL", after.getInspectionResult());
        assertEquals(taskId, after.getInspectionTaskId());
        assertNotNull(after.getInspectionTime());
        assertEquals(CcConstants.BATCH_SHIPPED, after.getStatus(), "抽检结论不得改动批次运输状态");
        assertEquals(before.getShipQty(), after.getShipQty());
        assertEquals(before.getSignTime(), after.getSignTime());
        assertEquals(before.getDispatchTime(), after.getDispatchTime());
        // 结论一次定稿
        assertStop(post(INSPECTOR, INSPW, "/inspections/" + taskId + "/conclude",
                map("result", "PASS", "conclusion", "改判")), "一次定稿");
        // 已定稿任务不能再封存样本
        assertStop(post(INSPECTOR, INSPW, "/inspections/samples/seal", sealBody(taskId, 1,
                "X", "Y", "z.jpg", "/z", "c")), "已出结论");

        // 无样本任务不能出结论
        JsonNode emptyTask = post(INSPECTOR, INSPW, "/inspections",
                map("batchId", batchId, "taskName", "无样本任务"));
        long emptyTaskId = emptyTask.path("data").path("id").asLong();
        assertStop(post(INSPECTOR, INSPW, "/inspections/" + emptyTaskId + "/conclude",
                map("result", "PASS", "conclusion", "空结论")), "尚未封存任何样本");

        // 批次台账可追溯抽检任务、样本封存、双人转移链
        JsonNode ledger = get(INSPECTOR, INSPW, "/batches/" + batchId + "/ledger");
        JsonNode inspection = ledger.path("inspection");
        assertEquals("EAST", inspection.path("superviseRegion").asText());
        assertEquals("FAIL", inspection.path("inspectionResult").asText());
        assertTrue(inspection.path("tasks").size() >= 2);
        boolean foundTransferChain = false;
        for (JsonNode t : inspection.path("tasks")) {
            for (JsonNode s : t.path("samples")) {
                if (s.path("sample").path("sealCode").asText().equals(code1)) {
                    assertEquals(1, s.path("transfers").size());
                    assertEquals("CONFIRMED", s.path("transfers").get(0).path("status").asText());
                    foundTransferChain = true;
                }
            }
        }
        assertTrue(foundTransferChain, "台账必须能追溯样本封存与双人转移链");
    }

    // ====================================================================
    // 2) 查询/详情按监管区域隔离
    // ====================================================================
    @Test
    void queries_areIsolatedBySuperviseRegion() throws Exception {
        long eastBatch = shipNewBatch("EAST", "13700000001");
        long southBatch = shipNewBatch("SOUTH", "13700000002");
        post(INSPECTOR, INSPW, "/inspections", map("batchId", eastBatch, "taskName", "华东抽检"));
        post(INSPECTOR2, INSPW, "/inspections", map("batchId", southBatch, "taskName", "华南抽检"));

        // inspector（EAST/NORTH）列表只见自己区域任务
        JsonNode eastList = get(INSPECTOR, INSPW, "/inspections");
        for (JsonNode t : eastList) {
            assertNotEquals("SOUTH", t.path("superviseRegion").asText(),
                    "区域隔离：EAST 账号不得看到 SOUTH 任务");
        }
        // inspector2（SOUTH）列表只见 SOUTH
        JsonNode southList = get(INSPECTOR2, INSPW, "/inspections");
        for (JsonNode t : southList) {
            assertEquals("SOUTH", t.path("superviseRegion").asText());
        }

        // 越权立项被服务层区域卡口拒绝（业务错误信封）
        JsonNode crossCreate = post(INSPECTOR, INSPW, "/inspections",
                map("batchId", southBatch, "superviseRegion", "SOUTH", "taskName", "越权抽检"));
        assertStop(crossCreate, "监管区域隔离");

        // 越权详情拒绝
        JsonNode southTasks = get(INSPECTOR2, INSPW, "/inspections?region=SOUTH");
        long southTaskId = southTasks.get(0).path("id").asLong();
        JsonNode crossDetail = rawGetEnvelope(INSPECTOR, INSPW, "/inspections/" + southTaskId);
        assertStop(crossDetail, "监管区域隔离");
        // 越权按区域过滤同样被拒
        assertStop(rawGetEnvelope(INSPECTOR, INSPW, "/inspections?region=SOUTH"), "监管区域隔离");
        // 按批次查询越权区域也被拒
        assertStop(rawGetEnvelope(INSPECTOR, INSPW, "/inspections/batch/" + southBatch), "监管区域隔离");
    }

    // ====================================================================
    // 3) 导出脱敏 + 导出权限
    // ====================================================================
    @Test
    void export_isRegionScoped_andMasksCarrierPhone() throws Exception {
        long batchId = shipNewBatch("EAST", "13912345678");
        JsonNode task = post(INSPECTOR, INSPW, "/inspections",
                map("batchId", batchId, "taskName", "导出厂抽检"));
        long taskId = task.path("data").path("id").asLong();
        post(INSPECTOR, INSPW, "/inspections/samples/seal", sealBody(taskId, 5,
                "车厢", "周监", "p.jpg", "/p", "pic"));

        HttpResp csv = rawRequest(INSPECTOR, INSPW, HttpMethod.GET,
                "/inspections/export?region=EAST", null);
        assertEquals(200, csv.status);
        assertTrue(csv.body.contains("139******78"), "承运人联系方式应脱敏：" + csv.body);
        assertFalse(csv.body.contains("13912345678"), "导出文件不得出现承运人原始联系方式");
        assertTrue(csv.body.contains("承运人联系方式(脱敏)"));

        // inspector2 无导出权限 -> 403；越区导出也不允许
        assertEquals(403, rawRequest(INSPECTOR2, INSPW, HttpMethod.GET,
                "/inspections/export", null).status);
        assertEquals(403, rawRequest(DISPATCHER, PW, HttpMethod.GET,
                "/inspections/export", null).status);
    }

    // ====================================================================
    // 4) 审批只完成一半停留待处理：拦截发运，抽检照常，同意后放行；驳回可重申
    // ====================================================================
    @Test
    void approvalHalfDone_staysPending_blocksShip_butInspectionContinues() throws Exception {
        long batchId = dispatchNewBatch("EAST", "13800000001");

        // 第一步申请：停留 PENDING
        JsonNode apply = post(DISPATCHER, PW, "/approvals",
                map("batchId", batchId, "reason", "疫苗冷链运输放行申请"));
        assertTrue(apply.path("success").asBoolean(), apply.toString());
        long approvalId = apply.path("data").path("id").asLong();
        assertEquals("PENDING", apply.path("data").path("status").asText());
        // 重复申请被拒（半成品停留）
        assertStop(post(DISPATCHER, PW, "/approvals",
                map("batchId", batchId, "reason", "重复申请")), "只完成一半");
        // 待处理列表可见
        JsonNode pending = get(INSPECTOR, INSPW, "/approvals/pending");
        boolean listed = false;
        for (JsonNode a : pending) {
            if (a.path("id").asLong() == approvalId) {
                listed = true;
            }
        }
        assertTrue(listed);

        // 半成品状态下拦截发运
        assertStop(post(DISPATCHER, PW, "/batches/" + batchId + "/ship", new LinkedHashMap<>()),
                "只完成一半");

        // 审批期间抽检任务与样本封存照常可办、可追溯
        JsonNode task = post(INSPECTOR, INSPW, "/inspections",
                map("batchId", batchId, "taskName", "待审批期间先行抽检"));
        assertTrue(task.path("success").asBoolean(), task.toString());
        long taskId = task.path("data").path("id").asLong();
        JsonNode sealed = post(INSPECTOR, INSPW, "/inspections/samples/seal", sealBody(taskId, 1,
                "车厢", "周监", "q.jpg", "/q", "pic"));
        assertTrue(sealed.path("success").asBoolean(), sealed.toString());

        // 无审批权限的账号不能审批：dispatcher 403
        assertEquals(403, rawRequest(DISPATCHER, PW, HttpMethod.POST,
                "/approvals/" + approvalId + "/approve", map("reviewNote", "调度自批")).status);
        // inspector 同意 -> APPROVED，发运放行
        JsonNode approved = post(INSPECTOR, INSPW, "/approvals/" + approvalId + "/approve",
                map("reviewNote", "冷链资料齐全，同意放行"));
        assertTrue(approved.path("success").asBoolean(), approved.toString());
        assertEquals("APPROVED", approved.path("data").path("status").asText());
        JsonNode shipped = post(DISPATCHER, PW, "/batches/" + batchId + "/ship", new LinkedHashMap<>());
        assertTrue(shipped.path("success").asBoolean(), shipped.toString());
        assertEquals(CcConstants.BATCH_SHIPPED, batchMapper.selectById(batchId).getStatus());

        // 驳回路径：新批次申请 -> 驳回停留 REJECTED -> 拦截发运 -> 重新申请 -> 同意 -> 放行
        long batch2 = dispatchNewBatch("NORTH", "13800000002");
        long approval2 = post(DISPATCHER, PW, "/approvals",
                map("batchId", batch2, "reason", "第二次运输申请")).path("data").path("id").asLong();
        JsonNode rejected = post(INSPECTOR, INSPW, "/approvals/" + approval2 + "/reject",
                map("reviewNote", "温控预案缺失，驳回补正"));
        assertEquals("REJECTED", rejected.path("data").path("status").asText());
        assertStop(post(DISPATCHER, PW, "/batches/" + batch2 + "/ship", new LinkedHashMap<>()), "已驳回");
        long approval3 = post(DISPATCHER, PW, "/approvals",
                map("batchId", batch2, "reason", "补正后重新申请")).path("data").path("id").asLong();
        assertNotEquals(approval2, approval3);
        assertTrue(post(INSPECTOR, INSPW, "/approvals/" + approval3 + "/approve",
                map("reviewNote", "补正到位，同意")).path("success").asBoolean());
        assertTrue(post(DISPATCHER, PW, "/batches/" + batch2 + "/ship", new LinkedHashMap<>())
                .path("success").asBoolean());
    }

    // ====================================================================
    // 5) 无抽检权限的账号不能进入抽检接口
    // ====================================================================
    @Test
    void shiroPermissions_denyWrongRoles() throws Exception {
        long batchId = shipNewBatch("EAST", "13800000003");
        // dispatcher 没有任何抽检权限点：列表 403、立项 403
        assertEquals(403, rawRequest(DISPATCHER, PW, HttpMethod.GET, "/inspections", null).status);
        assertEquals(403, rawRequest(DISPATCHER, PW, HttpMethod.POST, "/inspections",
                map("batchId", batchId, "taskName", "越权立项")).status);
        // inspector 没有审批申请权限
        assertEquals(403, rawRequest(INSPECTOR, INSPW, HttpMethod.POST, "/approvals",
                map("batchId", batchId, "reason", "监管自申请")).status);
        // inspector2 无审批权
        assertEquals(403, rawRequest(INSPECTOR2, INSPW, HttpMethod.GET, "/approvals/pending", null).status);
    }

    // ---------------- 全链路建样 ----------------

    private long shipNewBatch(String region, String driverPhone) {
        long batchId = dispatchNewBatch(region, driverPhone);
        batchService.ship(batchId);
        return batchId;
    }

    private long dispatchNewBatch(String region, String driverPhone) {
        long nano = System.nanoTime();

        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V7验收药品-" + region + "-" + nano);
        dc.setApprovalNo("国药准字V7-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V7验收医院-" + region);
        oc.setShipQty(20);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测7·" + region + Math.abs(nano % 1000000));
        vr.setCarrierName("V7验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
        vr.setDriverPhone(driverPhone);
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V7-" + nano);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V7-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V7校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v7bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        dr.setSuperviseRegion(region);
        Long batchId = batchService.dispatch(dr).getId();
        return batchId;
    }

    // ---------------- HTTP 辅助 ----------------

    private Map<String, Object> sealBody(long taskId, int qty, String location, String sampler,
                                         String photoName, String photoUrl, String content) {
        return map("taskId", taskId, "quantity", qty, "unit", "盒",
                "sampleLocation", location, "samplerName", sampler,
                "sealPhotoName", photoName, "sealPhotoUrl", photoUrl,
                "sealPhotoContent", content, "custodianOrg", "在途承运车");
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private JsonNode post(String user, String pw, String path, Object body) {
        HttpResp resp = rawRequest(user, pw, HttpMethod.POST, path, body);
        if (resp.status != 200) {
            throw new AssertionError("期望 200 业务响应，实际 " + resp.status + "：" + resp.body);
        }
        return readTree(resp.body);
    }

    private JsonNode get(String user, String pw, String path) {
        HttpResp resp = rawRequest(user, pw, HttpMethod.GET, path, null);
        assertEquals(200, resp.status, "GET " + path + " -> " + resp.status + " " + resp.body);
        return readTree(resp.body).path("data");
    }

    /** GET 返回完整信封（用于断言业务层区域拒绝 success=false 的场景） */
    private JsonNode rawGetEnvelope(String user, String pw, String path) {
        HttpResp resp = rawRequest(user, pw, HttpMethod.GET, path, null);
        assertEquals(200, resp.status, resp.body);
        return readTree(resp.body);
    }

    private HttpResp rawRequest(String user, String pw, HttpMethod method, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = http.withBasicAuth(user, pw)
                    .exchange("http://localhost:" + port + BASE + path, method, entity, String.class);
            return new HttpResp(resp.getStatusCodeValue(), resp.getBody());
        } catch (HttpClientErrorException e) {
            return new HttpResp(e.getRawStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拦截: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private static class HttpResp {
        final int status;
        final String body;

        HttpResp(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
