package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.CarrierQuote;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.QuoteSurcharge;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.CarrierQuoteMapper;
import com.evops.coldchain.mapper.LockedPriceMapper;
import com.evops.coldchain.mapper.QuoteBatchMapper;
import com.evops.coldchain.mapper.QuoteSurchargeMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链承运商报价比选与锁价（V8）验收测试。
 *
 * 覆盖：
 * 1. 报价按路线/温区/箱型/服务等级四维发起；多家承运商并行报价、截止前修订只追加新版本；
 *    金额必须拆 基础运费+附加费+税率（系统重算小计/税额/总价）；比选按含税总价排名。
 * 2. 锁价单只能选一个有效方案：中选 LOCKED、落选 REJECTED、批次 CLOSED、重复锁价拒绝；
 *    锁价保存全要素快照（含附加费逐行快照），锁价后报价不能再修订，原始报价金额不覆盖。
 * 3. 报价过期后不能被新批次引用（过期批次/报价置 EXPIRED，拒绝再提交、拒绝锁价、新批次不能引用旧报价）。
 * 4. 人工改价必须经审批：PENDING 期间派车卡口拦截，QA 驳回继续按原价执行，同意后 final_* 生效
 *    而 *_snap 原始报价金额与原始附加费快照永久保留。
 * 5. 从冷链药品运输批次（锁价单）反查承运商报价：定位原单、各环节处理人及异常原因
 *    （落选/修订替换/改价驳回）。
 * 6. Shiro 权限点：401/403/200。
 *
 * 账号：dispatcher（发起批次/锁价/改价申请）、carrier（承运商提交报价）、
 * qa（改价审批）、receiver（仅查看）、admin（建承运商，*）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CarrierQuoteLockIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    private static final String DISPATCHER = "dispatcher";
    private static final String CARRIER = "carrier";
    private static final String QA = "qa";
    private static final String RECEIVER = "receiver";
    private static final String ADMIN = "admin";
    private static final String PW = "dispatcher123";
    private static final String CARRIERPW = "carrier123";
    private static final String QAPW = "qa123";
    private static final String ADMINPW = "admin123";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DrugService drugService;
    @Autowired private OrderService orderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private QuoteBatchMapper quoteBatchMapper;
    @Autowired private CarrierQuoteMapper quoteMapper;
    @Autowired private QuoteSurchargeMapper surchargeMapper;
    @Autowired private LockedPriceMapper lockedPriceMapper;

    private Long carrierA;
    private Long carrierB;

    @BeforeEach
    void resolveCarriers() {
        // 使用 schema 内置的两家演示承运商；按名称找ID（名称库内唯一）
        carrierA = carrierId("华东冰链物流");
        carrierB = carrierId("沪杭鲜冷运输");
        assertNotNull(carrierA);
        assertNotNull(carrierB);
    }

    // ====================================================================
    // 1) 并行报价 + 修订只追加 + 金额拆项 + 比选排名
    // ====================================================================
    @Test
    void parallelQuotes_revisionsAppend_compareByTotal() throws Exception {
        long batchId = openBatch(LocalDateTime.now().plusHours(2));

        // A 首报：基础1000 + 附加费 燃油60+制冷40，税率9%
        JsonNode a1 = submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "1000.00", "0.09", surcharges(fuel("60"), cold("40")), quoteValid(2));
        long a1Id = a1.path("data").path("id").asLong();
        assertEquals(1, a1.path("data").path("versionNo").asInt());
        assertMoney(a1, "baseAmount", "1000.00");
        assertMoney(a1, "surchargeAmount", "100.00");
        assertMoney(a1, "subtotalAmount", "1100.00");
        assertMoney(a1, "taxAmount", "99.00");
        assertMoney(a1, "totalAmount", "1199.00");

        // B 首报：基础900，无附加费，税率9% -> 含税981，暂列第一
        JsonNode b1 = submitQuote(CARRIER, CARRIERPW, batchId, carrierB,
                "900.00", "0.09", null, quoteValid(2));
        long b1Id = b1.path("data").path("id").asLong();

        JsonNode compare = get(DISPATCHER, PW, "/quotes/batches/" + batchId + "/compare");
        assertEquals(2, compare.size());
        assertEquals(carrierB, compare.get(0).path("quote").path("carrierId").asLong());
        assertEquals(1, compare.get(0).path("rank").asInt());
        assertTrue(compare.get(0).path("valid").asBoolean());

        // A 修订（更低价）：只追加 V2，旧版 SUPERSEDED 原值保留
        JsonNode a2 = submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "800.00", "0.09", surcharges(fuel("50")), quoteValid(2));
        long a2Id = a2.path("data").path("id").asLong();
        assertEquals(2, a2.path("data").path("versionNo").asInt());
        assertMoney(a2, "surchargeAmount", "50.00");
        assertMoney(a2, "subtotalAmount", "850.00");
        assertMoney(a2, "taxAmount", "76.50");
        assertMoney(a2, "totalAmount", "926.50");

        CarrierQuote oldA = quoteMapper.selectById(a1Id);
        assertEquals(CcConstants.QUOTE_SUPERSEDED, oldA.getStatus());
        // 旧版原值与原附加费明细不被覆盖
        assertEquals(new BigDecimal("1199.00"), oldA.getTotalAmount());
        assertEquals(2, surchargeRows(a1Id, null).size());
        assertEquals(new BigDecimal("60.00"), surchargeRows(a1Id, null).get(0).getAmount());

        compare = get(DISPATCHER, PW, "/quotes/batches/" + batchId + "/compare");
        assertEquals(carrierA, compare.get(0).path("quote").path("carrierId").asLong(),
                "A 修订后应排名第一");
        assertEquals(2, compare.get(0).path("quote").path("versionNo").asInt());
        // 比选行带附加费拆项
        JsonNode winnerSurcharges = compare.get(0).path("surcharges");
        assertEquals(1, winnerSurcharges.size());
        assertEquals("FUEL", winnerSurcharges.get(0).path("surchargeType").asText());

        // 物理表核对：该批次共 3 行报价（A V1/V2 + B V1），当前有效仅 2 行
        assertEquals(3, quoteMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CarrierQuote>()
                        .eq("quote_batch_id", batchId)).intValue());
    }

    // ====================================================================
    // 2) 锁价唯一中选 + 全要素快照 + 派车引用 + 反查
    // ====================================================================
    @Test
    void lock_picksOne_snapshots_andDispatchTrace() throws Exception {
        long batchId = openBatch(LocalDateTime.now().plusHours(3));
        long aId = submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "1000.00", "0.09", surcharges(fuel("60"), cold("40")), quoteValid(3))
                .path("data").path("id").asLong();
        long bId = submitQuote(CARRIER, CARRIERPW, batchId, carrierB,
                "900.00", "0.09", null, quoteValid(3))
                .path("data").path("id").asLong();

        // 锁价选 A（不是最低价也允许——业务比选，唯一有效方案约束的是“只能选一张”）
        JsonNode lockResp = post(DISPATCHER, PW, "/locked-prices/lock",
                map("quoteBatchId", batchId, "quoteId", aId, "lockRemark", "选服务等级更高的A"));
        assertTrue(lockResp.path("success").asBoolean(), lockResp.toString());
        long lockedId = lockResp.path("data").path("id").asLong();
        String lockedNo = lockResp.path("data").path("lockedPriceNo").asText();
        assertTrue(lockedNo.startsWith("SJ"));
        assertMoney(lockResp, "totalAmountSnap", "1199.00");
        assertEquals("REFRIGERATE", lockResp.path("data").path("tempZoneCodeSnap").asText());
        assertEquals("INSULATED_BOX", lockResp.path("data").path("boxTypeSnap").asText());
        assertEquals("DEDICATED", lockResp.path("data").path("serviceLevelSnap").asText());
        assertEquals(CcConstants.LP_LOCKED, lockResp.path("data").path("status").asText());

        // 物理表：中选 LOCKED、落选 REJECTED、批次 CLOSED；重复锁价拒绝
        assertEquals(CcConstants.QUOTE_LOCKED, quoteMapper.selectById(aId).getStatus());
        assertEquals(CcConstants.QUOTE_REJECTED, quoteMapper.selectById(bId).getStatus());
        assertEquals(CcConstants.QB_CLOSED, quoteBatchMapper.selectById(batchId).getStatus());
        assertStop(post(DISPATCHER, PW, "/locked-prices/lock",
                map("quoteBatchId", batchId, "quoteId", bId)), "只能选定一个有效方案");

        // 锁价后批次已 CLOSED，承运商不能再修订报价
        assertStop(submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "700.00", "0.09", null, quoteValid(2)), "不能再提交或修订");

        // 附加费逐行快照（locked_price_id 非空），原始报价明细行仍在且不被改动
        List<QuoteSurcharge> snaps = surchargeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuoteSurcharge>()
                        .eq("locked_price_id", lockedId).orderByAsc("id"));
        assertEquals(2, snaps.size());
        assertEquals(new BigDecimal("100.00"),
                snaps.get(0).getAmount().add(snaps.get(1).getAmount()));
        assertEquals(2, surchargeRows(aId, null).size(), "报价原始附加费行保留");

        // 派车引用锁价单（未携带锁价单的既有流程不受卡口影响，见其他 V1~V7 测试）
        long transportBatchId = dispatchNewBatch(lockedId);
        TransportBatch tb = batchMapper.selectById(transportBatchId);
        assertEquals(lockedId, tb.getLockedPriceId());

        // 已被运输批次引用的锁价单不能作废
        assertStop(post(DISPATCHER, PW, "/locked-prices/" + lockedId + "/void",
                map("reason", "想作废")), "已被");

        // —— 反查：从锁价单 / 从批次两个入口定位原单、处理人、异常原因 ——
        JsonNode trace = get(DISPATCHER, PW, "/locked-prices/" + lockedId + "/trace");
        assertEquals(aId, trace.path("originalQuote").path("id").asLong());
        assertEquals("华东冰链物流", trace.path("carrier").path("carrierName").asText());
        assertEquals(batchId, trace.path("quoteBatch").path("id").asLong());
        // 处理人链：批次发起 + 两家报价 + 锁价
        boolean carrierHandler = false;
        boolean lockHandler = false;
        for (JsonNode h : trace.path("handlers")) {
            if ("QUOTE_SUBMIT".equals(h.path("node").asText())
                    && CARRIER.equals(h.path("handler").asText())) {
                carrierHandler = true;
            }
            if ("QUOTE_LOCK".equals(h.path("node").asText())
                    && DISPATCHER.equals(h.path("handler").asText())) {
                lockHandler = true;
            }
        }
        assertTrue(carrierHandler, "反查必须定位到报价处理人 carrier");
        assertTrue(lockHandler, "反查必须定位到锁价处理人 dispatcher");
        // 异常原因：B 落选
        String reasons = trace.path("anomalyReasons").toString();
        assertTrue(reasons.contains("落选"), reasons);
        assertTrue(reasons.contains("沪杭鲜冷运输"), reasons);
        // 引用批次
        assertEquals(1, trace.path("referencedBatches").size());

        JsonNode byBatch = get(DISPATCHER, PW, "/locked-prices/by-batch/" + transportBatchId);
        assertTrue(byBatch.path("locked").asBoolean());
        assertEquals(aId, byBatch.path("trace").path("originalQuote").path("id").asLong());

        // 批次台账也含锁价反查块
        JsonNode ledger = get(DISPATCHER, PW, "/batches/" + transportBatchId + "/ledger");
        assertEquals(lockedNo, ledger.path("lockedPrice").path("lockedPrice").path("lockedPriceNo").asText());
    }

    // ====================================================================
    // 3) 报价过期不能被新批次引用
    // ====================================================================
    @Test
    void expiredQuote_cannotBeReferencedOrLocked() throws Exception {
        long batchId = openBatch(LocalDateTime.now().plusHours(1));
        long aId = submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "1000.00", "0.09", surcharges(fuel("60")), quoteValid(1))
                .path("data").path("id").asLong();
        submitQuote(CARRIER, CARRIERPW, batchId, carrierB,
                "900.00", "0.09", null, quoteValid(1));

        // 时间快进：直接把开始/截止时间回拨到过去（受 check 约束需同时回拨），再触发过期扫描
        jdbcTemplate.update("UPDATE t_cc_quote_batch SET quote_start_time = ?, quote_expire_time = ? WHERE id = ?",
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1), batchId);
        JsonNode scan = post(DISPATCHER, PW, "/quotes/expire-scan", new LinkedHashMap<>());
        assertEquals(1, scan.path("data").path("expiredBatches").asInt());
        assertEquals(CcConstants.QB_EXPIRED, quoteBatchMapper.selectById(batchId).getStatus());
        assertEquals(CcConstants.QUOTE_EXPIRED, quoteMapper.selectById(aId).getStatus());

        // 过期批次不能再提交/修订报价
        assertStop(submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "800.00", "0.09", null, LocalDateTime.now().plusMinutes(30)), "不能再提交或修订");
        // 过期报价不能锁价
        assertStop(post(DISPATCHER, PW, "/locked-prices/lock",
                map("quoteBatchId", batchId, "quoteId", aId)), "已过期");

        // 新报价批次不能引用旧（过期）报价单：锁价时报价不属于该批次，被拒绝
        long newBatchId = openBatch(LocalDateTime.now().plusHours(2));
        assertStop(post(DISPATCHER, PW, "/locked-prices/lock",
                map("quoteBatchId", newBatchId, "quoteId", aId)), "不属于报价批次");
    }

    // ====================================================================
    // 4) 人工改价必须审批，驳回按原价，同意改 final_* 且原始快照保留
    // ====================================================================
    @Test
    void manualAdjust_requiresApproval_preservesOriginal() throws Exception {
        long batchId = openBatch(LocalDateTime.now().plusHours(4));
        long quoteId = submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "1000.00", "0.09", surcharges(fuel("60"), cold("40")), quoteValid(4))
                .path("data").path("id").asLong();
        long lockedId = post(DISPATCHER, PW, "/locked-prices/lock",
                map("quoteBatchId", batchId, "quoteId", quoteId))
                .path("data").path("id").asLong();

        // 派车引用
        long batch1 = dispatchNewBatch(lockedId);

        // 发起改价：基础 700 + 燃油10，税率 6% -> 小计710 税额42.60 总价752.60
        JsonNode apply = post(DISPATCHER, PW, "/locked-prices/adjustments",
                map("lockedPriceId", lockedId, "reason", "回程货源稳定，协商降价",
                        "newBaseAmount", "700.00", "newTaxRate", "0.06",
                        "newSurcharges", listOf(fuel("10"))));
        assertTrue(apply.path("success").asBoolean(), apply.toString());
        long adjustId = apply.path("data").path("id").asLong();
        assertEquals("PENDING", apply.path("data").path("status").asText());
        assertMoney(apply, "beforeTotalAmount", "1199.00");
        assertMoney(apply, "afterTotalAmount", "752.60");

        // 改价审批中：锁价单 ADJUSTING，派车卡口拦截
        assertEquals(CcConstants.LP_ADJUSTING, lockedPriceMapper.selectById(lockedId).getStatus());
        assertDispatchBlockedWhileAdjusting(lockedId);
        // 重复申请拒绝
        assertStop(post(DISPATCHER, PW, "/locked-prices/adjustments",
                map("lockedPriceId", lockedId, "reason", "再改", "newBaseAmount", "600.00",
                        "newTaxRate", "0.06")), "停留待审批");

        // 无审批权角色：carrier 403、dispatcher 403
        assertEquals(403, rawRequest(CARRIER, CARRIERPW, HttpMethod.POST,
                "/locked-prices/adjustments/" + adjustId + "/approve",
                map("reviewNote", "承运自批")).status);
        assertEquals(403, rawRequest(DISPATCHER, PW, HttpMethod.POST,
                "/locked-prices/adjustments/" + adjustId + "/approve",
                map("reviewNote", "调度自批")).status);

        // QA 驳回：必须留意见；锁价单恢复 LOCKED，金额不变
        JsonNode rejected = post(QA, QAPW,
                "/locked-prices/adjustments/" + adjustId + "/reject",
                map("reviewNote", "缺少降价依据，驳回"));
        assertEquals("REJECTED", rejected.path("data").path("status").asText());
        LockedPrice afterReject = lockedPriceMapper.selectById(lockedId);
        assertEquals(CcConstants.LP_LOCKED, afterReject.getStatus());
        assertEquals(new BigDecimal("1199.00"), afterReject.getFinalTotalAmount());

        // 重新申请，QA 同意：final_* 生效，*_snap 原始报价金额不变
        long adjust2 = post(DISPATCHER, PW, "/locked-prices/adjustments",
                map("lockedPriceId", lockedId, "reason", "补齐回程合同，申请降价",
                        "newBaseAmount", "700.00", "newTaxRate", "0.06",
                        "newSurcharges", listOf(fuel("10"))))
                .path("data").path("id").asLong();
        JsonNode approved = post(QA, QAPW,
                "/locked-prices/adjustments/" + adjust2 + "/approve",
                map("reviewNote", "依据充分，同意"));
        assertEquals("APPROVED", approved.path("data").path("status").asText());
        LockedPrice finalLp = lockedPriceMapper.selectById(lockedId);
        assertEquals(new BigDecimal("752.60"), finalLp.getFinalTotalAmount());
        assertEquals(new BigDecimal("710.00"), finalLp.getFinalSubtotalAmount());
        assertEquals(new BigDecimal("42.60"), finalLp.getFinalTaxAmount());
        // 原始报价快照永不覆盖
        assertEquals(new BigDecimal("1199.00"), finalLp.getTotalAmountSnap());
        assertEquals(new BigDecimal("1000.00"), finalLp.getBaseAmountSnap());
        assertEquals(new BigDecimal("0.0900"), finalLp.getTaxRateSnap());
        // 原报价行金额不动
        assertEquals(new BigDecimal("1199.00"), quoteMapper.selectById(quoteId).getTotalAmount());
        // 原始附加费快照 2 行保留；本次同意的改价生效附加费 1 行（adjust_id=已同意审批）；
        // 第一次被驳回的申请附加费行也保留（adjust_id=驳回审批），可区分未生效
        List<QuoteSurcharge> originalSnap = surchargeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuoteSurcharge>()
                        .eq("locked_price_id", lockedId).isNull("adjust_id"));
        List<QuoteSurcharge> adjustRows = surchargeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuoteSurcharge>()
                        .eq("locked_price_id", lockedId).eq("adjust_id", finalLp.getLastAdjustId()));
        List<QuoteSurcharge> rejectedAdjustRows = surchargeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuoteSurcharge>()
                        .eq("locked_price_id", lockedId).eq("adjust_id", adjustId));
        assertEquals(2, originalSnap.size());
        assertEquals(1, adjustRows.size());
        assertEquals(new BigDecimal("10.00"), adjustRows.get(0).getAmount());
        assertEquals(1, rejectedAdjustRows.size(), "被驳回改价的附加费行同样保留可追溯，但未生效");

        // 反查带改价链与驳回异常原因，处理人含申请人与 QA
        JsonNode trace = get(DISPATCHER, PW, "/locked-prices/" + lockedId + "/trace");
        String reasons = trace.path("anomalyReasons").toString();
        assertTrue(reasons.contains("被审批驳回"), reasons);
        assertTrue(reasons.contains("缺少降价依据"), reasons);
        boolean qaReviewer = false;
        for (JsonNode h : trace.path("handlers")) {
            if (h.path("node").asText().startsWith("PRICE_ADJUST_")
                    && QA.equals(h.path("handler").asText())) {
                qaReviewer = true;
            }
        }
        assertTrue(qaReviewer, "反查必须定位改价审批人 qa");
        // 两条审批都保留
        assertEquals(2, trace.path("adjustments").size());

        // 已派车批次仍持原引用且快照可追（历史批次不回写）
        assertEquals(lockedId, batchMapper.selectById(batch1).getLockedPriceId());
    }

    // ====================================================================
    // 5) 维度与金额校验停点
    // ====================================================================
    @Test
    void invalidDimensionsAndAmounts_areRejected() throws Exception {
        // 截止时间早于当前：拒绝发起即过期批次
        assertStop(post(DISPATCHER, PW, "/quotes/batches/open", batchOpenBody(
                null, LocalDateTime.now().minusMinutes(1))), "必须晚于当前时间");
        // 未知温区代码
        assertStop(post(DISPATCHER, PW, "/quotes/batches/open", batchOpenBody(
                "UNKNOWN_ZONE", LocalDateTime.now().plusHours(1))), "未知温区");
        // 无路线且缺起终点
        Map<String, Object> noEndpoints = batchOpenBody(null, LocalDateTime.now().plusHours(1));
        noEndpoints.remove("origin");
        noEndpoints.remove("destination");
        assertStop(post(DISPATCHER, PW, "/quotes/batches/open", noEndpoints), "起点");

        long batchId = openBatch(LocalDateTime.now().plusHours(1));
        // 税率越界
        assertStop(submitQuoteRaw(CARRIER, CARRIERPW,
                quoteBody(batchId, carrierA, "1000.00", "1.00", null, quoteValid(1))), "税率");
        // 负基础运费
        assertStop(submitQuoteRaw(CARRIER, CARRIERPW,
                quoteBody(batchId, carrierA, "-1.00", "0.09", null, quoteValid(1))), "基础运费不能为负");
        // 报价有效期晚于批次截止
        assertStop(submitQuoteRaw(CARRIER, CARRIERPW,
                quoteBody(batchId, carrierA, "1000.00", "0.09", null, quoteValid(2))), "不能晚于报价批次截止");
        // 已停用承运商不能报价：admin 新建一家后停用
        long revokedCarrier = createCarrierThenRevoke();
        assertStop(submitQuoteRaw(CARRIER, CARRIERPW,
                quoteBody(batchId, revokedCarrier, "1000.00", "0.09", null, quoteValid(1))), "承运商已停用");
    }

    // ====================================================================
    // 6) Shiro 权限点
    // ====================================================================
    @Test
    void shiroPermissions() throws Exception {
        long batchId = openBatch(LocalDateTime.now().plusHours(2));
        // 承运商不能发起报价批次；调度不能替承运商报价
        assertEquals(403, rawRequest(CARRIER, CARRIERPW, HttpMethod.POST,
                "/quotes/batches/open", batchOpenBody(null, LocalDateTime.now().plusHours(1))).status);
        assertEquals(403, rawRequest(DISPATCHER, PW, HttpMethod.POST,
                "/quotes/submit", quoteBody(batchId, carrierA, "1000", "0.09", null, quoteValid(2))).status);
        // 收货岗无任何报价操作权，但可查看
        assertEquals(403, rawRequest(RECEIVER, "receiver123", HttpMethod.POST,
                "/quotes/batches/" + batchId + "/close", null).status);
        assertEquals(200, rawRequest(RECEIVER, "receiver123", HttpMethod.GET,
                "/quotes/batches/" + batchId, null).status);
        // 无锁价权
        long quoteId = submitQuote(CARRIER, CARRIERPW, batchId, carrierA,
                "1000.00", "0.09", null, quoteValid(2)).path("data").path("id").asLong();
        assertEquals(403, rawRequest(CARRIER, CARRIERPW, HttpMethod.POST,
                "/locked-prices/lock", map("quoteBatchId", batchId, "quoteId", quoteId)).status);
        // 未认证 401
        assertEquals(401, rawRequest(null, null, HttpMethod.GET, "/quotes/batches", null).status);
    }

    // ---------------- 辅助 ----------------

    private long openBatch(LocalDateTime expire) {
        JsonNode resp = post(DISPATCHER, PW, "/quotes/batches/open",
                batchOpenBody(null, expire));
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> batchOpenBody(String zoneCode, LocalDateTime expire) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origin", "上海中心仓");
        m.put("destination", "杭州市一院");
        m.put("tempZoneCode", zoneCode == null ? "REFRIGERATE" : zoneCode);
        m.put("boxType", "INSULATED_BOX");
        m.put("serviceLevel", "DEDICATED");
        m.put("boxQty", 20);
        m.put("quoteExpireTime", expire.toString());
        return m;
    }

    private Map<String, Object> quoteBody(long batchId, long carrierId, String base, String taxRate,
                                          List<Map<String, Object>> surcharges, LocalDateTime validUntil) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quoteBatchId", batchId);
        m.put("carrierId", carrierId);
        m.put("baseAmount", base);
        m.put("taxRate", taxRate);
        m.put("validUntil", validUntil.toString());
        if (surcharges != null) {
            m.put("surcharges", surcharges);
        }
        return m;
    }

    private JsonNode submitQuote(String user, String pw, long batchId, long carrierId,
                                 String base, String taxRate, List<Map<String, Object>> sur,
                                 LocalDateTime validUntil) {
        return post(user, pw, "/quotes/submit", quoteBody(batchId, carrierId, base, taxRate, sur, validUntil));
    }

    private JsonNode submitQuoteRaw(String user, String pw, Map<String, Object> body) {
        return post(user, pw, "/quotes/submit", body);
    }

    private Map<String, Object> fuel(String amount) {
        return surcharge("FUEL", amount, "燃油附加费");
    }

    private Map<String, Object> cold(String amount) {
        return surcharge("COLD", amount, "制冷费");
    }

    private Map<String, Object> surcharge(String type, String amount, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("surchargeType", type);
        m.put("typeName", name);
        m.put("amount", amount);
        return m;
    }

    private List<Map<String, Object>> surcharges(Map<String, Object>... items) {
        return listOf(items);
    }

    @SafeVarargs
    private static List<Map<String, Object>> listOf(Map<String, Object>... items) {
        List<Map<String, Object>> l = new java.util.ArrayList<>();
        for (Map<String, Object> i : items) {
            l.add(i);
        }
        return l;
    }

    /** 报价有效期：相对报价批次截止（+h 小时）提前 30 分钟，避免与批次截止同刻比较 */
    private LocalDateTime quoteValid(int batchExpireInHours) {
        return LocalDateTime.now().plusHours(batchExpireInHours).minusMinutes(30);
    }

    private List<QuoteSurcharge> surchargeRows(Long quoteId, Long lockedPriceId) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuoteSurcharge> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuoteSurcharge>()
                        .eq("quote_id", quoteId);
        if (lockedPriceId == null) {
            qw.isNull("locked_price_id");
        } else {
            qw.eq("locked_price_id", lockedPriceId);
        }
        return surchargeMapper.selectList(qw.orderByAsc("id"));
    }

    private Long carrierId(String name) {
        JsonNode list = get(DISPATCHER, PW, "/carriers");
        for (JsonNode c : list) {
            if (name.equals(c.path("carrierName").asText())) {
                return c.path("id").asLong();
            }
        }
        return null;
    }

    private long createCarrierThenRevoke() {
        long nano = System.nanoTime();
        JsonNode resp = post(ADMIN, ADMINPW, "/carriers/create",
                map("carrierName", "停用测试承运商-" + Math.abs(nano),
                        "contactName", "停", "qualification", "无"));
        assertTrue(resp.path("success").asBoolean(), "建档停用承运商失败: " + resp);
        long id = resp.path("data").path("id").asLong();
        jdbcTemplate.update("UPDATE t_cc_carrier SET status = 'REVOKED' WHERE id = ?", id);
        return id;
    }

    private long dispatchNewBatch(Long lockedPriceId) {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V8验收药品-" + nano);
        dc.setApprovalNo("国药准字V8-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V8验收医院");
        oc.setShipQty(20);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测8·" + Math.abs(nano % 1000000));
        vr.setCarrierName("华东冰链物流");
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        dr.setLockedPriceId(lockedPriceId);
        return batchServiceDispatch(dr);
    }

    @Autowired
    private com.evops.coldchain.service.TransportBatchService batchService;

    private long batchServiceDispatch(DispatchRequest dr) {
        return batchService.dispatch(dr).getId();
    }

    private void assertDispatchBlockedWhileAdjusting(long lockedId) {
        // HTTP 层完整验证：ADJUSTING 锁价单用于派车被卡口拦截
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V8改价中-" + nano);
        dc.setApprovalNo("国药准字V8B-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();
        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V8改价中医院");
        oc.setShipQty(10);
        Long orderId = orderService.create(oc).getId();
        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测8G·" + Math.abs(nano % 1000000));
        vr.setCarrierName("华东冰链物流");
        Long vehicleId = vehicleService.create(vr).getId();
        JsonNode resp = post(DISPATCHER, PW, "/batches/dispatch",
                map("orderId", orderId, "vehicleId", vehicleId, "lockedPriceId", lockedId));
        assertStop(resp, "待审批");
    }

    private void assertMoney(JsonNode resp, String field, String expected) {
        JsonNode node = resp.path("data").path(field);
        assertFalse(node.isMissingNode(), "缺少金额字段: " + field);
        assertEquals(0, new BigDecimal(node.asText()).compareTo(new BigDecimal(expected)),
                field + " 应为 " + expected + "，实际 " + node.asText());
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

    private HttpResp rawRequest(String user, String pw, HttpMethod method, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            TestRestTemplate template = user == null ? http : http.withBasicAuth(user, pw);
            ResponseEntity<String> resp = template.exchange(
                    "http://localhost:" + port + BASE + path, method, entity, String.class);
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
