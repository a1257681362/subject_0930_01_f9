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
import com.evops.coldchain.entity.HandoverSeal;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.HandoverOrderMapper;
import com.evops.coldchain.mapper.HandoverSealMapper;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.PermissionService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链批次分仓交接（V4）验收测试。
 *
 * 覆盖：一车货拆多单、逐封签核验、箱数平衡、交接温度/接收人留痕、提交责任主体随单转移、
 * 原批次台账追溯全部分仓节点、未核验箱子拦截继续派送/签收；箱数不平/封签重复/温度缺失
 * 三类停点返回具体原因并禁止提交；权限收紧后建单/核验/提交/签收全部停止，但已确认封签
 * 事实保留、恢复后无需重新核验；作废释放箱数占用但封签/核验事实保留、号不可复用。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HandoverTransferIntegrationTest {

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
    @Autowired private PermissionService permissionService;

    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private HandoverOrderMapper handoverMapper;
    @Autowired private HandoverSealMapper sealMapper;
    @Autowired private OpLogMapper opLogMapper;

    /** 每个用例前后都把冷链权限恢复放行，避免收紧状态泄漏影响本类及其它验收测试 */
    @BeforeEach
    @AfterEach
    void resetPermission() {
        if (!permissionService.isPermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER)) {
            com.evops.coldchain.dto.PermissionChangeRequest req =
                    new com.evops.coldchain.dto.PermissionChangeRequest();
            req.setPermitted(true);
            req.setReason("用例初始化/清理：恢复放行");
            permissionService.change(req);
        }
    }

    // ====================================================================
    // 1) 拆多单 + 逐封签核验 + 责任随单转移 + 原批次可追溯全节点
    // ====================================================================
    @Test
    void splitVerifySubmit_transfersResponsibility_andBatchStaysTraceable() throws Exception {
        long batchId = shipNewBatch(30);
        TransportBatch frozen = batchMapper.selectById(batchId);
        // 发运即固化初始责任主体为承运方
        assertNotNull(frozen.getResponsibleOrg());
        assertEquals("王司机", frozen.getResponsibleParty());

        // 到仓登记 10 箱
        assertTrue(post("/handovers/arrive", arriveBody(batchId, 10, "华东中转仓")).path("success").asBoolean());
        JsonNode progress0 = get("/handovers/batch/" + batchId + "/progress");
        assertTrue(progress0.path("arrivedRegistered").asBoolean());
        assertFalse(progress0.path("readyForDispatch").asBoolean());

        // 拆成 A(6) B(4) 两个交接单
        JsonNode a = post("/handovers", createBody(batchId, "华东中转仓", 6, new BigDecimal("5.20"),
                "张仓", "华东中转仓收货组", seals("A", 6)));
        assertTrue(a.path("success").asBoolean(), a.toString());
        String aNo = a.path("data").path("order").path("handoverNo").asText();
        assertTrue(aNo.startsWith("JJ"));
        assertEquals(4, a.path("data").path("remainingBoxQty").asInt());
        long aId = a.path("data").path("order").path("id").asLong();
        // 提交前单上责任主体仍是原承运方
        assertEquals(frozen.getResponsibleOrg(), handoverMapper.selectById(aId).getResponsibleOrg());

        JsonNode b = post("/handovers", createBody(batchId, "华东中转仓", 4, new BigDecimal("4.80"),
                "李班", "同城配送二组", seals("B", 4)));
        assertTrue(b.path("success").asBoolean(), b.toString());
        long bId = b.path("data").path("order").path("id").asLong();

        // A 单 6 个封签全部核验
        for (JsonNode seal : a.path("data").path("seals")) {
            JsonNode v = post("/handovers/seals/verify",
                    verifyBody(seal.path("id").asLong(), "封签完好、箱内温度达标"));
            assertTrue(v.path("success").asBoolean(), v.toString());
        }
        // 已确认事实不能重复核验/改写
        JsonNode dupVerify = post("/handovers/seals/verify",
                verifyBody(a.path("data").path("seals").get(0).path("id").asLong(), "再核一次"));
        assertStop(dupVerify, "不能重复核验");

        // A 全部核验、且全批已分满 10 箱：A 可先提交，责任随单转移给 A 接收方
        JsonNode submitA = post("/handovers/" + aId + "/submit", new LinkedHashMap<>());
        assertTrue(submitA.path("success").asBoolean(), submitA.toString());
        assertEquals("华东中转仓收货组", handoverMapper.selectById(aId).getResponsibleOrg());
        assertEquals("张仓", handoverMapper.selectById(aId).getResponsibleParty());
        assertEquals(CcConstants.HANDOVER_SUBMITTED, handoverMapper.selectById(aId).getStatus());
        // B 尚未核验：批次整体还不能继续派送
        assertEquals("华东中转仓收货组", batchMapper.selectById(batchId).getResponsibleOrg());
        assertStop(post("/batches/sign", signBody(batchId, frozen)), "未完成核验的箱子不能继续派送");

        // B 核验并提交，责任主体再随单转移；全批就绪
        for (JsonNode seal : b.path("data").path("seals")) {
            assertTrue(post("/handovers/seals/verify",
                    verifyBody(seal.path("id").asLong(), "封签完好")).path("success").asBoolean());
        }
        assertTrue(post("/handovers/" + bId + "/submit", new LinkedHashMap<>()).path("success").asBoolean());
        assertEquals("同城配送二组", batchMapper.selectById(batchId).getResponsibleOrg());
        assertEquals("李班", batchMapper.selectById(batchId).getResponsibleParty());
        JsonNode progress = get("/handovers/batch/" + batchId + "/progress");
        assertTrue(progress.path("balanced").asBoolean());
        assertTrue(progress.path("allSubmitted").asBoolean());
        assertTrue(progress.path("readyForDispatch").asBoolean());

        // 签收闭环放行（全部箱数核验平衡）
        JsonNode signed = post("/batches/sign", signBody(batchId, frozen));
        assertTrue(signed.path("success").asBoolean(), signed.toString());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());

        // 原运输批次台账可追溯全部节点：到仓 + 2 交接单 + 10 封签(全核验) + 各类操作日志
        JsonNode ledger = get("/batches/" + batchId + "/ledger");
        JsonNode ho = ledger.path("handovers");
        assertEquals(10, ho.path("arrivedBoxQty").asInt());
        assertEquals(2, ho.path("handovers").size());
        int totalSeals = 0, confirmedSeals = 0;
        for (JsonNode o : ho.path("handovers")) {
            totalSeals += o.path("seals").size();
            for (JsonNode s : o.path("seals")) {
                if ("CONFIRMED".equals(s.path("verifyStatus").asText())) {
                    confirmedSeals++;
                }
            }
        }
        assertEquals(10, totalSeals);
        assertEquals(10, confirmedSeals);
        assertTrue(countAction(CcConstants.ACT_ARRIVE) >= 1);
        assertTrue(countAction(CcConstants.ACT_HANDOVER_CREATE) >= 2);
        assertTrue(countAction(CcConstants.ACT_SEAL_VERIFY) >= 10);
        assertTrue(countAction(CcConstants.ACT_HANDOVER_SUBMIT) >= 2);
        assertTrue(countAction(CcConstants.ACT_SHIP) >= 1);
    }

    // ====================================================================
    // 2) 未完成核验的箱子不能继续派送（签收被拦截），补齐后放行
    // ====================================================================
    @Test
    void unverifiedBoxes_blockDispatchAndSign_untilCompleted() throws Exception {
        long batchId = shipNewBatch(12);
        TransportBatch frozen = batchMapper.selectById(batchId);
        post("/handovers/arrive", arriveBody(batchId, 5, "华中中转仓"));

        // 先只建 A(3)：箱数不平，提交应被拦截（还差 2 箱）
        JsonNode a = post("/handovers", createBody(batchId, "华中中转仓", 3, new BigDecimal("6.00"),
                "赵收", "华中中转仓组", seals("GA", 3)));
        long aId = a.path("data").path("order").path("id").asLong();
        for (JsonNode seal : a.path("data").path("seals")) {
            post("/handovers/seals/verify", verifyBody(seal.path("id").asLong(), "完好"));
        }
        assertStop(post("/handovers/" + aId + "/submit", new LinkedHashMap<>()), "箱数不平");

        // 补齐 B(2) 但不核验：A 可提交（全批已分满），批次仍因 B 未核验不能继续派送
        JsonNode b = post("/handovers", createBody(batchId, "华中中转仓", 2, new BigDecimal("6.10"),
                "钱收", "末端配送组", seals("GB", 2)));
        long bId = b.path("data").path("order").path("id").asLong();
        assertTrue(post("/handovers/" + aId + "/submit", new LinkedHashMap<>()).path("success").asBoolean());
        JsonNode progress = get("/handovers/batch/" + batchId + "/progress");
        assertFalse(progress.path("readyForDispatch").asBoolean());
        assertEquals(3, progress.path("submittedBoxQty").asInt());
        assertStop(post("/batches/sign", signBody(batchId, frozen)), "未完成核验的箱子不能继续派送");

        // 核验并提交 B 后放行签收
        for (JsonNode seal : b.path("data").path("seals")) {
            assertTrue(post("/handovers/seals/verify",
                    verifyBody(seal.path("id").asLong(), "完好")).path("success").asBoolean());
        }
        assertTrue(post("/handovers/" + bId + "/submit", new LinkedHashMap<>()).path("success").asBoolean());
        assertTrue(get("/handovers/batch/" + batchId + "/progress").path("readyForDispatch").asBoolean());
        assertTrue(post("/batches/sign", signBody(batchId, frozen)).path("success").asBoolean());
    }

    // ====================================================================
    // 3) 箱数不平 / 封签重复 / 温度缺失：给出具体原因并禁止提交
    // ====================================================================
    @Test
    void imbalance_duplicateSeal_missingTemp_areRejectedWithReasons() throws Exception {
        long batchId = shipNewBatch(8);
        post("/handovers/arrive", arriveBody(batchId, 4, "华南中转仓"));

        // 箱数(3)与封签数(2)不平
        Map<String, Object> badQty = createBody(batchId, "华南中转仓", 3, new BigDecimal("5.00"),
                "孙收", "华南组", list("D1", "D2"));
        assertStop(post("/handovers", badQty), "箱数不平");

        // 单内封签重复
        assertStop(post("/handovers", createBody(batchId, "华南中转仓", 3, new BigDecimal("5.00"),
                "孙收", "华南组", list("X1", "X1", "X2"))), "封签重复");

        // 先建一单（2 箱），后续复用其封签号必须被拒（指明冲突单）
        JsonNode first = post("/handovers", createBody(batchId, "华南中转仓", 2, new BigDecimal("5.00"),
                "孙收", "华南组", list("U1", "U2")));
        assertTrue(first.path("success").asBoolean(), first.toString());
        String firstNo = first.path("data").path("order").path("handoverNo").asText();
        JsonNode dupAcross = post("/handovers", createBody(batchId, "华南中转仓", 1, new BigDecimal("5.00"),
                "周收", "末端组", list("U1")));
        assertStop(dupAcross, "封签重复");
        assertTrue(dupAcross.path("message").asText().contains(firstNo),
                "应指明与哪张交接单冲突：" + dupAcross.path("message").asText());

        // 累计箱数超出到仓总箱数：已分 2，再来 3 箱 -> 超出 1
        assertStop(post("/handovers", createBody(batchId, "华南中转仓", 3, new BigDecimal("5.00"),
                "周收", "末端组", seals("V", 3))), "超出 1 箱");

        // 交接温度缺失：Bean 校验直接拦截
        Map<String, Object> noTemp = createBody(batchId, "华南中转仓", 1, new BigDecimal("5.00"),
                "周收", "末端组", list("Z1"));
        noTemp.remove("handoverTemp");
        assertStop(post("/handovers", noTemp), "交接温度");

        // 接收人缺失
        Map<String, Object> noReceiver = createBody(batchId, "华南中转仓", 1, new BigDecimal("5.00"),
                "周收", "末端组", list("Z2"));
        noReceiver.remove("receiverName");
        assertStop(post("/handovers", noReceiver), "接收人");
    }

    // ====================================================================
    // 4) 权限收紧：停止推进，但已确认封签事实保留；恢复后无需重新核验
    // ====================================================================
    @Test
    void permissionTightened_stopsProgressButKeepsConfirmedFacts_andPageShowsBanner() throws Exception {
        long batchId = shipNewBatch(10);
        post("/handovers/arrive", arriveBody(batchId, 4, "华北中转仓"));
        JsonNode a = post("/handovers", createBody(batchId, "华北中转仓", 2, new BigDecimal("4.50"),
                "吴收", "华北中转仓组", seals("PA", 2)));
        long aId = a.path("data").path("order").path("id").asLong();
        long seal1 = a.path("data").path("seals").get(0).path("id").asLong();
        long seal2 = a.path("data").path("seals").get(1).path("id").asLong();
        assertTrue(post("/handovers/seals/verify", verifyBody(seal1, "收紧前已核验")).path("success").asBoolean());

        // 收紧权限
        JsonNode tighten = post("/permissions", permBody(false, "监管现场整改，暂停冷链交接"));
        assertTrue(tighten.path("success").asBoolean(), tighten.toString());
        assertFalse(get("/permissions/cold-chain-handover").path("permitted").asBoolean());

        // 一切推进动作停止：核验/建单/提交 全部被拒（同一道卡口）
        assertStop(post("/handovers/seals/verify", verifyBody(seal2, "收紧期间尝试核验")), "已被收紧");
        assertStop(post("/handovers", createBody(batchId, "华北中转仓", 2, new BigDecimal("4.50"),
                "郑收", "末端组", seals("PB", 2))), "已被收紧");
        assertStop(post("/handovers/" + aId + "/submit", new LinkedHashMap<>()), "已被收紧");
        assertStop(post("/batches/sign", signBody(batchId, batchMapper.selectById(batchId))), "已被收紧");

        // 页面顶部出现“已被收紧 / 停止推进 / 事实保留”横幅
        ResponseEntity<String> page = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/handovers", String.class);
        assertEquals(200, page.getStatusCode().value());
        assertTrue(page.getBody().contains("批次分仓交接"));
        assertTrue(page.getBody().contains("已被收紧"));

        // 已确认事实原样保留、仍可查询
        HandoverSeal kept = sealMapper.selectById(seal1);
        assertEquals(CcConstants.SEAL_CONFIRMED, kept.getVerifyStatus());
        assertEquals("收紧前已核验", kept.getVerifyNote());
        JsonNode detail = get("/handovers/" + aId);
        assertEquals("CONFIRMED", detail.path("seals").get(0).path("verifyStatus").asText());
        assertEquals("PENDING", detail.path("seals").get(1).path("verifyStatus").asText());

        // 恢复放行：之前已核验的封签无需重新核验，直接核验剩余、补齐 B 单并完成提交
        assertTrue(post("/permissions", permBody(true, "整改完成，恢复")).path("success").asBoolean());
        assertTrue(post("/handovers/seals/verify", verifyBody(seal2, "恢复后核验")).path("success").asBoolean());
        JsonNode b = post("/handovers", createBody(batchId, "华北中转仓", 2, new BigDecimal("4.60"),
                "郑收", "末端组", seals("PB", 2)));
        long bId = b.path("data").path("order").path("id").asLong();
        for (JsonNode s : b.path("data").path("seals")) {
            assertTrue(post("/handovers/seals/verify",
                    verifyBody(s.path("id").asLong(), "完好")).path("success").asBoolean());
        }
        assertTrue(post("/handovers/" + aId + "/submit", new LinkedHashMap<>()).path("success").asBoolean());
        assertTrue(post("/handovers/" + bId + "/submit", new LinkedHashMap<>()).path("success").asBoolean());
        assertTrue(get("/handovers/batch/" + batchId + "/progress").path("readyForDispatch").asBoolean());

        // 恢复放行后页面正常渲染到仓登记/拆单表单（覆盖放行分支的 Thymeleaf 模板）
        ResponseEntity<String> pageGo = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/handovers", String.class);
        assertEquals(200, pageGo.getStatusCode().value());
        assertTrue(pageGo.getBody().contains("登记到仓总箱数"));

        // seal1 全程只核验过一次（收紧前后都没有被改写）
        assertEquals(1, opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_SEAL).eq("entity_id", seal1)
                .eq("action", CcConstants.ACT_SEAL_VERIFY)));
        assertTrue(countAction(CcConstants.ACT_PERMISSION_TIGHTEN) >= 1);
        assertTrue(countAction(CcConstants.ACT_PERMISSION_RESTORE) >= 1);
    }

    // ====================================================================
    // 5) 作废只置状态：释放箱数占用，但封签/核验事实保留、封签号不可复用
    // ====================================================================
    @Test
    void voidOrder_releasesBoxesButKeepsSealFacts() throws Exception {
        long batchId = shipNewBatch(6);
        post("/handovers/arrive", arriveBody(batchId, 4, "西南中转仓"));
        JsonNode a = post("/handovers", createBody(batchId, "西南中转仓", 2, new BigDecimal("5.50"),
                "冯收", "西南组", list("W1", "W2")));
        long aId = a.path("data").path("order").path("id").asLong();
        long sealW1 = a.path("data").path("seals").get(0).path("id").asLong();
        post("/handovers/seals/verify", verifyBody(sealW1, "已核验后整单作废"));

        // 作废 A
        assertTrue(post("/handovers/void", map("handoverId", aId, "reason", "分单错误，重建")).path("success").asBoolean());
        assertEquals(CcConstants.HANDOVER_VOIDED, handoverMapper.selectById(aId).getStatus());
        // 作废单不能再提交
        assertStop(post("/handovers/" + aId + "/submit", new LinkedHashMap<>()), "已作废");
        // 封签与核验事实保留（2 行，W1 仍 CONFIRMED）
        List<HandoverSeal> keptSeals = sealMapper.selectList(new QueryWrapper<HandoverSeal>()
                .eq("handover_id", aId).orderByAsc("seal_seq"));
        assertEquals(2, keptSeals.size());
        assertEquals(CcConstants.SEAL_CONFIRMED, keptSeals.get(0).getVerifyStatus());

        // 作废单释放箱数占用：当前有效分箱为 0，可重新分 4 箱
        JsonNode progress = get("/handovers/batch/" + batchId + "/progress");
        assertEquals(0, progress.path("allocatedBoxQty").asInt());
        assertEquals(4, progress.path("remainingBoxQty").asInt());

        // 但作废单的封签号仍占用、不可复用（事实保留）
        assertStop(post("/handovers", createBody(batchId, "西南中转仓", 2, new BigDecimal("5.50"),
                "陈收", "末端组", list("W1", "X2"))), "封签重复");

        // 用全新封签号重建 B(2)+C(2)，平衡后全部提交
        JsonNode b = post("/handovers", createBody(batchId, "西南中转仓", 2, new BigDecimal("5.50"),
                "陈收", "末端组", list("X1", "X2")));
        JsonNode c = post("/handovers", createBody(batchId, "西南中转仓", 2, new BigDecimal("5.40"),
                "卫收", "末端二组", list("Y1", "Y2")));
        for (JsonNode o : new JsonNode[]{b, c}) {
            for (JsonNode s : o.path("data").path("seals")) {
                assertTrue(post("/handovers/seals/verify",
                        verifyBody(s.path("id").asLong(), "完好")).path("success").asBoolean());
            }
            assertTrue(post("/handovers/" + o.path("data").path("order").path("id").asLong()
                    + "/submit", new LinkedHashMap<>()).path("success").asBoolean());
        }
        assertTrue(get("/handovers/batch/" + batchId + "/progress").path("readyForDispatch").asBoolean());
        // 台账仍能看到作废单（含其封签事实）
        JsonNode ledger = get("/batches/" + batchId + "/ledger");
        boolean sawVoided = false;
        for (JsonNode o : ledger.path("handovers").path("handovers")) {
            if ("VOIDED".equals(o.path("order").path("status").asText())) {
                sawVoided = true;
                assertEquals(2, o.path("seals").size());
            }
        }
        assertTrue(sawVoided, "作废交接单及封签事实必须仍可在原批次台账追溯");
    }

    // ---------------- 全链路建样：建档->订单->车辆->持证探头->派车->发运 ----------------

    private long shipNewBatch(int shipQty) {
        long nano = System.nanoTime();

        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V4验收药品-" + nano);
        dc.setApprovalNo("国药准字V4-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V4验收医院");
        oc.setShipQty(shipQty);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测B·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName("V4验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V4-" + nano);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V4-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V4校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v4bind-" + nano);
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

    // ---------------- 请求体/断言/HTTP 辅助 ----------------

    private Map<String, Object> arriveBody(long batchId, int boxes, String wh) {
        return map("batchId", batchId, "arrivedBoxQty", boxes, "warehouseName", wh);
    }

    private Map<String, Object> createBody(long batchId, String wh, int boxQty, BigDecimal temp,
                                          String receiver, String receiverOrg, List<String> sealNos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("warehouseName", wh);
        m.put("boxQty", boxQty);
        m.put("handoverTemp", temp);
        m.put("receiverName", receiver);
        m.put("receiverOrg", receiverOrg);
        m.put("sealNos", sealNos);
        return m;
    }

    private Map<String, Object> verifyBody(long sealId, String note) {
        return map("sealId", sealId, "verifiedBy", "仓管员", "verifyNote", note);
    }

    private Map<String, Object> signBody(long batchId, TransportBatch frozen) {
        return map("batchId", batchId,
                "signBy", "院药库",
                "receivedApprovalNo", frozen.getApprovalNoSnap(),
                "receivedPackageSpec", frozen.getPackageSpecSnap(),
                "confirmQty", frozen.getShipQty());
    }

    private Map<String, Object> permBody(boolean permitted, String reason) {
        return map("permitted", permitted, "reason", reason);
    }

    private List<String> seals(String prefix, int n) {
        List<String> list = new ArrayList<>();
        long nano = System.nanoTime();
        for (int i = 1; i <= n; i++) {
            list.add(prefix + "-" + nano + "-" + i);
        }
        return list;
    }

    private List<String> list(String... v) {
        List<String> l = new ArrayList<>();
        for (String s : v) {
            l.add(s);
        }
        return l;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private long countAction(String action) {
        Long n = opLogMapper.selectCount(new QueryWrapper<OpLog>().eq("action", action));
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
            String resp = http.withBasicAuth("dispatcher", "dispatcher123")
                    .postForObject("http://localhost:" + port + BASE + path, entity, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** GET 统一返回 ApiResponse 的 data 节点（本类所有 GET 都取业务数据） */
    private JsonNode get(String path) {
        try {
            String resp = http.withBasicAuth("dispatcher", "dispatcher123")
                    .getForObject("http://localhost:" + port + BASE + path, String.class);
            JsonNode envelope = M.readTree(resp);
            assertTrue(envelope.path("success").asBoolean(), "GET 应成功: " + resp);
            return envelope.path("data");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
