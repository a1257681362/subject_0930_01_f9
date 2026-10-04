package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.RouteSegment;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.RouteSegmentMapper;
import com.evops.coldchain.mapper.RouteSegmentSnapMapper;
import com.evops.coldchain.mapper.RouteVersionMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.RouteService;
import com.evops.coldchain.service.SeasonResolver;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.coldchain.service.DrugService;
import com.evops.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线温区分段验收测试（真实 HTTP 入口 + 落库追溯）。
 *
 * 验收主线：
 *  入口 POST /api/cold-chain/routes（建档）-> /versions/draft（季节分段）-> /versions/{id}/diff
 *  （发布前差异）-> /versions/publish（热替换）-> /batches/dispatch（逐段快照）->
 *  /batches/temperatures（按里程命中分段温区 + 缓冲时长）；
 *  每一步都用 Mapper 直查物理表，结果必须追到 t_cc_route / t_cc_route_version /
 *  t_cc_route_segment / t_cc_route_segment_snap 的具体行；任何不合法操作 HTTP 成功返回，
 *  但业务体 success=false 且 message 说明停点（空档/重叠/未覆盖/越界/已生效/被引用…）。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RouteTemperatureSegmentIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    // 落库追溯 Mapper
    @Autowired
    private RouteVersionMapper versionMapper;
    @Autowired
    private RouteSegmentMapper segmentMapper;
    @Autowired
    private RouteSegmentSnapMapper snapMapper;
    @Autowired
    private TemperatureLogMapper temperatureMapper;
    @Autowired
    private ExceptionEventMapper exceptionMapper;

    // 用于准备药品/车辆/发货单（已被既有测试覆盖，这里直接用服务）
    @Autowired
    private DrugService drugService;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private TransportBatchService batchService;
    @Autowired
    private ProbeService probeService;

    // ====================================================================
    // 1) 配置入口 -> 发布 -> 派车 -> 分段温度/缓冲，全链路追到分段与快照落库
    // ====================================================================
    @Test
    void routeEntry_persistsToSegmentRows_andSnapshotDrivesBuffer() throws Exception {
        // —— 入口①：路线建档 ——
        Map<String, Object> routeReq = new LinkedHashMap<>();
        routeReq.put("routeName", "上海中心仓-杭州市一院冷链线");
        routeReq.put("origin", "上海中心仓");
        routeReq.put("destination", "杭州市第一人民医院");
        routeReq.put("totalKm", 100.00);
        JsonNode routeResp = post("/routes", routeReq);
        assertTrue(routeResp.path("success").asBoolean(), routeResp.toString());
        long routeId = routeResp.path("data").path("id").asLong();
        assertTrue(routeResp.path("data").path("routeNo").asText().startsWith("RL"));

        // —— 入口②：季节分段草稿（GENERAL 通用，两段连续覆盖 0~100km）——
        List<Map<String, Object>> segments = new ArrayList<>();
        segments.add(segment(0, 50, 2, 8, 30, "王冷链", "13800000001"));
        segments.add(segment(50, 100, 0, 5, 60, "李干线", "13800000002"));
        JsonNode draftResp = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_GENERAL, segments, "秋冬季全程分段"));
        assertTrue(draftResp.path("success").asBoolean(), draftResp.toString());
        long versionId = draftResp.path("data").path("id").asLong();
        assertEquals(1, draftResp.path("data").path("versionNo").asInt());
        assertEquals(CcConstants.ROUTE_DRAFT, draftResp.path("data").path("status").asText());

        // 落库铁证：t_cc_route_version 草稿行 + t_cc_route_segment 两行原值
        RouteVersion vRow = versionMapper.selectById(versionId);
        assertEquals(CcConstants.ROUTE_DRAFT, vRow.getStatus());
        List<RouteSegment> segRows = routeService.listSegments(versionId);
        assertEquals(2, segRows.size());
        assertSeg(segRows.get(0), 0, 50, 2, 8, 30, "王冷链");
        assertSeg(segRows.get(1), 50, 100, 0, 5, 60, "李干线");
        // 直接查物理表（不经过服务），确保真的落到 t_cc_route_segment
        assertEquals(2, segmentMapper.selectList(null).stream()
                .filter(s -> s.getVersionId().equals(versionId)).count());

        // —— 入口③：发布前差异（首次发布，两段都应 ADDED）——
        JsonNode diff = get("/routes/versions/" + versionId + "/diff");
        assertTrue(diff.path("success").asBoolean(), diff.toString());
        JsonNode diffData = diff.path("data");
        assertTrue(diffData.path("currentVersionNo").isNull(), "首次发布无当前版本");
        JsonNode changes = diffData.path("changes");
        assertEquals(2, changes.size());
        assertEquals("ADDED", changes.get(0).path("changeType").asText());
        assertEquals("ADDED", changes.get(1).path("changeType").asText());

        // —— 入口④：发布（即时生效，热替换；首次发布无被替换版本）——
        JsonNode pub = post("/routes/versions/publish", map("versionId", versionId));
        assertTrue(pub.path("success").asBoolean(), pub.toString());
        assertEquals(CcConstants.EFFECTIVE, pub.path("data").path("version").path("status").asText());
        assertNotNull(pub.path("data").path("version").path("publishTime").asText(null));
        assertEquals(0, pub.path("data").path("supersededVersionNos").size());

        // —— 派车（HTTP 入口，携带 routeId）：只登记路线，尚未绑定季节版本/快照 ——
        Long batchId = dispatchViaHttpWithRoute(routeId);
        TransportBatch dispatched = batchService.requireBatch(batchId);
        assertEquals(routeId, dispatched.getRouteId());
        assertNull(dispatched.getRouteVersionId(), "未发运批次不应在派车时固化路线版本");
        assertEquals(0, snapMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RouteSegmentSnap>()
                        .eq("batch_id", batchId)));

        // —— 发运（HTTP 入口）：发运瞬间按季节选定生效版本并逐段固化快照 ——
        JsonNode shipResp = post("/batches/" + batchId + "/ship", new LinkedHashMap<>());
        assertTrue(shipResp.path("success").asBoolean(), shipResp.toString());

        TransportBatch batch = batchService.requireBatch(batchId);
        assertEquals(versionId, batch.getRouteVersionId());
        assertEquals(CcConstants.SEASON_GENERAL, batch.getRouteSeason());
        assertEquals(1, batch.getRouteVersionNo());

        // 落库铁证：t_cc_route_segment_snap 两行，值与发布分段完全一致
        List<RouteSegmentSnap> snaps = snapMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RouteSegmentSnap>()
                        .eq("batch_id", batchId).orderByAsc("segment_seq"));
        assertEquals(2, snaps.size());
        assertSnap(snaps.get(0), 0, 50, 2, 8, 30, "王冷链");
        assertSnap(snaps.get(1), 50, 100, 0, 5, 60, "李干线");

        // 台账聚合必须能看到季节版本与逐段快照
        JsonNode ledger = get("/batches/" + batchId + "/ledger");
        assertTrue(ledger.path("success").asBoolean(), ledger.toString());
        JsonNode ledgerSnaps = ledger.path("data").path("route").path("segmentSnapshots");
        assertEquals(2, ledgerSnaps.size());
        assertEquals(1, ledger.path("data").path("route").path("frozenVersionNo").asInt());

        // —— 分段温度判定 + 缓冲时长（HTTP 入口）——
        // 段1（0~50，2~8℃，缓冲30分钟）：08:00、08:10 两次越限，连续仅10分钟 -> 不开异常
        postTemp(batchId, 10, "2026-10-01T08:00:00", 9.0);
        JsonNode t2 = postTemp(batchId, 12, "2026-10-01T08:10:00", 9.5);
        assertEquals(false, t2.path("data").path("inRange").asBoolean());
        assertEquals(1, t2.path("data").path("segmentSeq").asInt());
        assertEquals(0L, countExceptions(batchId));

        // 08:46 同段仍越限：连续越限 46 分钟 > 缓冲30 -> 自动开异常，定位到段1责任人
        JsonNode t3 = postTemp(batchId, 15, "2026-10-01T08:46:00", 9.2);
        assertEquals(false, t3.path("data").path("inRange").asBoolean());
        assertEquals(1L, countExceptions(batchId));
        JsonNode exc0 = get("/batches/" + batchId + "/exceptions").path("data").get(0);
        assertEquals(CcConstants.EXC_TEMP, exc0.path("exceptionType").asText());
        assertEquals(1, exc0.path("segmentSeq").asInt());
        assertTrue(exc0.path("description").asText().contains("王冷链"), exc0.path("description").asText());

        // 温度留痕物理行必须带里程与分段序号
        assertEquals(3, temperatureMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.coldchain.entity.TemperatureLog>()
                        .eq("batch_id", batchId)).size());
        assertTrue(temperatureMapper.selectList(null).stream()
                .filter(t -> t.getBatchId().equals(batchId))
                .allMatch(t -> t.getSegmentSeq() != null && t.getMileageKm() != null));

        // 段2（50~100，0~5℃，缓冲60分钟）：首次越限不立即开异常（连续0分钟）
        postTemp(batchId, 60, "2026-10-01T09:00:00", 5.5);
        assertEquals(1L, countExceptions(batchId));

        // 段1先回正常再越限：连续越限被正常点打断，不沿用旧的46分钟计时
        postTemp(batchId, 20, "2026-10-01T09:10:00", 5.0); // 段1正常
        postTemp(batchId, 20, "2026-10-01T09:15:00", 9.0); // 段1重新越限仅5分钟
        assertEquals(1L, countExceptions(batchId));

        // —— 失败停点：有路线分段却不报里程 / 里程越界 ——
        JsonNode noMileage = post("/batches/temperatures", tempBody(batchId, null, "2026-10-01T09:20:00", 9.0));
        assertEquals(false, noMileage.path("success").asBoolean());
        assertTrue(noMileage.path("message").asText().contains("里程"), noMileage.path("message").asText());

        JsonNode outOfRange = post("/batches/temperatures", tempBody(batchId, 150, "2026-10-01T09:25:00", 4.0));
        assertEquals(false, outOfRange.path("success").asBoolean());
        assertTrue(outOfRange.path("message").asText().contains("未落在路线任一分段"),
                outOfRange.path("message").asText());
    }

    // ====================================================================
    // 2) 分段连续性：空档/重叠/未覆盖全程/倒置/越界 必须拒绝并说明停点
    // ====================================================================
    @Test
    void segmentContiguity_violations_rejectedWithStopPoint() {
        long routeId = createRouteOf100km();

        // 空档：[0,50) 与 [60,100)，50~60 缺失
        JsonNode gap = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_SPRING, Arrays.asList(
                        segment(0, 50, 2, 8, 30, "甲"), segment(60, 100, 2, 8, 30, "乙")), null));
        assertStop(gap, "空档");

        // 重叠：[0,60) 与 [50,100)
        JsonNode overlap = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_SUMMER, Arrays.asList(
                        segment(0, 60, 2, 8, 30, "甲"), segment(50, 100, 2, 8, 30, "乙")), null));
        assertStop(overlap, "重叠");

        // 未覆盖全程：末段止于 80
        JsonNode under = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_AUTUMN, Arrays.asList(
                        segment(0, 40, 2, 8, 30, "甲"), segment(40, 80, 2, 8, 30, "乙")), null));
        assertStop(under, "未覆盖全程");

        // 倒置：起点 >= 终点
        JsonNode inverted = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_WINTER, Arrays.asList(
                        segment(50, 50, 2, 8, 30, "甲"), segment(50, 100, 2, 8, 30, "乙")), null));
        assertStop(inverted, "里程非法");

        // 超出总里程
        JsonNode beyond = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_GENERAL, Arrays.asList(
                        segment(0, 60, 2, 8, 30, "甲"), segment(60, 120, 2, 8, 30, "乙")), null));
        assertStop(beyond, "超出路线总里程");

        // 温区下限高于上限
        JsonNode badTemp = post("/routes/" + routeId + "/versions/draft",
                draft("SPRING-2", Arrays.asList(
                        segment(0, 50, 9, 8, 30, "甲"), segment(50, 100, 2, 8, 30, "乙")), null));
        // 季节非法先在归一化拦截（也是明确停点）；换合法季节验证温区
        assertEquals(false, badTemp.path("success").asBoolean());
        JsonNode badTemp2 = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_SPRING, Arrays.asList(
                        segment(0, 50, 9, 8, 30, "甲"), segment(50, 100, 2, 8, 30, "乙")), null, null, null, null));
        // 上面第一次已占用 SPRING 草稿（失败不落库），这里复用同季节应仍可建
        assertStop(badTemp2, "下限不能高于上限");

        // 缓冲为负
        JsonNode badBuffer = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_SUMMER, Arrays.asList(
                        segment(0, 50, 2, 8, -5, "甲"), segment(50, 100, 2, 8, 30, "乙")), null, null, null, null));
        assertStop(badBuffer, "缓冲时长不能为负");

        // 责任人为空
        Map<String, Object> noOwner = segment(0, 50, 2, 8, 30, null);
        JsonNode badOwner = post("/routes/" + routeId + "/versions/draft",
                draft(CcConstants.SEASON_AUTUMN, Arrays.asList(noOwner, segment(50, 100, 2, 8, 30, "乙")),
                        null, null, null, null));
        // @NotBlank 在入口校验层拦截
        assertEquals(false, badOwner.path("success").asBoolean());
    }

    // ====================================================================
    // 3) 新版本只影响未发运批次；已经发运执行的批次继续引用原快照
    // ====================================================================
    @Test
    void newVersion_onlyAffectsUnshippedBatches() {
        long routeId = createRouteOf100km();
        // GENERAL V1：段1 责任人 甲、温区 2~8
        publishDraft(routeId, CcConstants.SEASON_GENERAL, Arrays.asList(
                segment(0, 50, 2, 8, 30, "甲"), segment(50, 100, 0, 5, 60, "乙")), "V1");
        RouteVersion v1 = routeService.listVersions(routeId).stream()
                .filter(v -> CcConstants.SEASON_GENERAL.equals(v.getSeason()) && v.getVersionNo() == 1)
                .findFirst().orElseThrow(() -> new IllegalStateException("not found"));

        // 批次A 先派车（此时 V1 生效），但暂不发运——属“未发运批次”
        Long batchA = dispatchViaServiceWithRoute(routeId);
        assertNull(batchService.requireBatch(batchA).getRouteVersionId(), "派车未发运不应固化路线版本");

        // 在 A 发运前发布 V2：段1 温区收窄 3~7、缓冲 45、责任人改 丙；段2 不变
        long v2Id = saveDraft(routeId, CcConstants.SEASON_GENERAL, Arrays.asList(
                segment(0, 50, 3, 7, 45, "丙"), segment(50, 100, 0, 5, 60, "乙")), "V2 收窄");
        JsonNode diff = routeDiff(v2Id);
        assertEquals(1, countChange(diff, "MODIFIED"));
        assertEquals(1, countChange(diff, "UNCHANGED"));
        assertEquals(0, countChange(diff, "ADDED"));
        publishVersion(v2Id);
        assertEquals(CcConstants.SUPERSEDED, versionMapper.selectById(v1.getId()).getStatus(),
                "热替换：旧版本置 SUPERSEDED");

        // A 直到此刻才发运：未发运批次取发运当时的新版本 V2（而不是派车时的 V1）
        batchService.ship(batchA);
        List<RouteSegmentSnap> snapsA = routeService.listSnapshotsByBatch(batchA);
        assertEquals(2, snapsA.size());
        assertSnap(snapsA.get(0), 0, 50, 3, 7, 45, "丙");
        assertEquals(2, batchService.requireBatch(batchA).getRouteVersionNo());

        // 批次B 派车并发运（V2 生效中）-> 同样固化 V2
        Long batchB = dispatchViaServiceWithRoute(routeId);
        batchService.ship(batchB);
        assertSnap(routeService.listSnapshotsByBatch(batchB).get(0), 0, 50, 3, 7, 45, "丙");

        // A、B 均已发运执行；再发布 V3
        long v3Id = saveDraft(routeId, CcConstants.SEASON_GENERAL, Arrays.asList(
                segment(0, 50, 1, 6, 20, "丁"), segment(50, 100, 0, 5, 60, "乙")), "V3 再调整");
        publishVersion(v3Id);

        // 已经发运的 A、B 继续引用原 V2 快照，不受 V3 影响
        assertSnap(routeService.listSnapshotsByBatch(batchA).get(0), 0, 50, 3, 7, 45, "丙");
        assertSnap(routeService.listSnapshotsByBatch(batchB).get(0), 0, 50, 3, 7, 45, "丙");
        assertEquals(2, batchService.requireBatch(batchA).getRouteVersionNo());
        assertEquals(2, batchService.requireBatch(batchB).getRouteVersionNo());
        // V1/V2 历史分段行原值不被修改
        assertSeg(routeService.listSegments(v1.getId()).get(0), 0, 50, 2, 8, 30, "甲");
        assertSeg(routeService.listSegments(v2Id).get(0), 0, 50, 3, 7, 45, "丙");

        // V3 之后才派车并发运的批次C 取 V3
        Long batchC = dispatchViaServiceWithRoute(routeId);
        batchService.ship(batchC);
        assertSnap(routeService.listSnapshotsByBatch(batchC).get(0), 0, 50, 1, 6, 20, "丁");
        assertEquals(3, batchService.requireBatch(batchC).getRouteVersionNo());
    }

    // ====================================================================
    // 4) 撤回：只能撤销尚未生效、未被引用的版本
    // ====================================================================
    @Test
    void withdraw_onlyNotYetEffectiveVersions() {
        long routeId = createRouteOf100km();

        // 草稿可直接撤回
        long draftId = saveDraft(routeId, CcConstants.SEASON_SPRING, twoSegments(), null);
        JsonNode wDraft = post("/routes/versions/withdraw", map("versionId", draftId, "reason", "录错了"));
        assertTrue(wDraft.path("success").asBoolean(), wDraft.toString());
        assertEquals(CcConstants.REVOKED, versionMapper.selectById(draftId).getStatus());

        // 即时生效版本：已生效，拒绝撤回
        long effId = saveDraft(routeId, CcConstants.SEASON_SUMMER, twoSegments(), null);
        publishVersion(effId);
        JsonNode wEff = post("/routes/versions/withdraw", map("versionId", effId, "reason", "x"));
        assertStop(wEff, "已生效");

        // 已被热替换的版本也不能撤回
        long v2Id = saveDraft(routeId, CcConstants.SEASON_SUMMER, twoSegments(), "替换");
        publishVersion(v2Id);
        JsonNode wSuperseded = post("/routes/versions/withdraw", map("versionId", effId, "reason", "x"));
        assertStop(wSuperseded, "替换");

        // 预约未来生效、且尚无批次引用的版本：尚未生效，允许撤回
        long futureId = saveDraft(routeId, CcConstants.SEASON_WINTER, twoSegments(),
                "预约", LocalDateTime.of(2099, 1, 1, 0, 0), LocalDateTime.of(2099, 2, 1, 0, 0));
        publishVersion(futureId);
        assertEquals(CcConstants.EFFECTIVE, versionMapper.selectById(futureId).getStatus());
        JsonNode wFuture = post("/routes/versions/withdraw", map("versionId", futureId, "reason", "计划取消"));
        assertTrue(wFuture.path("success").asBoolean(), wFuture.toString());
        assertEquals(CcConstants.REVOKED, versionMapper.selectById(futureId).getStatus());

        // 已撤回不可重复撤回
        JsonNode wAgain = post("/routes/versions/withdraw", map("versionId", futureId, "reason", "x"));
        assertStop(wAgain, "撤回");
    }

    // ====================================================================
    // 5) 季节选取：当季优先于 GENERAL；季节映射；无生效版本的停点
    // ====================================================================
    @Test
    void seasonalSelection_prefersCurrentSeason_thenGeneral() {
        // 月份 -> 季节
        assertEquals(CcConstants.SEASON_SPRING, SeasonResolver.seasonOf(LocalDateTime.of(2026, 3, 15, 0, 0)));
        assertEquals(CcConstants.SEASON_SUMMER, SeasonResolver.seasonOf(LocalDateTime.of(2026, 7, 15, 0, 0)));
        assertEquals(CcConstants.SEASON_AUTUMN, SeasonResolver.seasonOf(LocalDateTime.of(2026, 10, 15, 0, 0)));
        assertEquals(CcConstants.SEASON_WINTER, SeasonResolver.seasonOf(LocalDateTime.of(2026, 1, 15, 0, 0)));

        long routeId = createRouteOf100km();
        long autumnId = saveDraft(routeId, CcConstants.SEASON_AUTUMN, Arrays.asList(
                segment(0, 50, 2, 8, 30, "秋甲"), segment(50, 100, 0, 5, 60, "秋乙")), null);
        publishVersion(autumnId);
        long generalId = saveDraft(routeId, CcConstants.SEASON_GENERAL, Arrays.asList(
                segment(0, 50, 2, 8, 30, "通甲"), segment(50, 100, 0, 5, 60, "通乙")), null);
        publishVersion(generalId);

        // 秋季时点：命中当季 AUTUMN，而非 GENERAL。
        // 两版本均为“发布即长期生效”（生效起点为运行当天），故查询点取未来的秋季日期，
        // 避免使用早于运行当天的固定日期（如 2026-10-01）导致在 10 月运行时命中不到。
        assertEquals(autumnId,
                routeService.selectEffectiveAt(routeId, LocalDateTime.of(2027, 10, 15, 8, 0)).getId());
        // 冬季/夏季（未配置）：回退 GENERAL
        assertEquals(generalId,
                routeService.selectEffectiveAt(routeId, LocalDateTime.of(2027, 1, 20, 8, 0)).getId());
        assertEquals(generalId,
                routeService.selectEffectiveAt(routeId, LocalDateTime.of(2027, 7, 20, 8, 0)).getId());

        // 只有 SUMMER、运行时点又非夏季、且无 GENERAL：停点说明缺少生效版本
        long summerOnlyRoute = createRouteOf100km();
        long summerId = saveDraft(summerOnlyRoute, CcConstants.SEASON_SUMMER, twoSegments(), null);
        publishVersion(summerId);
        LocalDateTime now = LocalDateTime.now();
        if (!CcConstants.SEASON_SUMMER.equals(SeasonResolver.seasonOf(now))) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> routeService.selectEffectiveAt(summerOnlyRoute, now));
            assertTrue(ex.getMessage().contains("没有生效的温区分段版本"), ex.getMessage());
        }
    }

    // ====================================================================
    // 6) 预约生效时间窗不得重叠
    // ====================================================================
    @Test
    void scheduledWindows_overlap_rejected() {
        long routeId = createRouteOf100km();
        long aId = saveDraft(routeId, CcConstants.SEASON_WINTER, twoSegments(), "A",
                LocalDateTime.of(2099, 1, 1, 0, 0), LocalDateTime.of(2099, 2, 1, 0, 0));
        JsonNode pubA = post("/routes/versions/publish", map("versionId", aId));
        assertTrue(pubA.path("success").asBoolean(), pubA.toString());

        long bId = saveDraft(routeId, CcConstants.SEASON_WINTER, twoSegments(), "B",
                LocalDateTime.of(2099, 1, 15, 0, 0), LocalDateTime.of(2099, 3, 1, 0, 0));
        JsonNode pubB = post("/routes/versions/publish", map("versionId", bId));
        assertStop(pubB, "重叠");
    }

    // ---------------- 辅助方法 ----------------

    /** Java 8 兼容的简易 Map.of 替代：key/value 成对传入 */
    private static Map<String, Object> map(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("key/value 必须成对");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private long createRouteOf100km() {
        JsonNode r = post("/routes", map(
                "routeName", "验收线-" + System.nanoTime(),
                "origin", "O", "destination", "D", "totalKm", 100.00));
        assertTrue(r.path("success").asBoolean(), r.toString());
        return r.path("data").path("id").asLong();
    }

    private List<Map<String, Object>> twoSegments() {
        return Arrays.asList(segment(0, 50, 2, 8, 30, "甲"), segment(50, 100, 0, 5, 60, "乙"));
    }

    private long saveDraft(long routeId, String season, List<Map<String, Object>> segs, String reason) {
        return saveDraft(routeId, season, segs, reason, null, null);
    }

    private long saveDraft(long routeId, String season, List<Map<String, Object>> segs, String reason,
                           LocalDateTime from, LocalDateTime to) {
        JsonNode d = post("/routes/" + routeId + "/versions/draft", draft(season, segs, reason, from, to, null));
        assertTrue(d.path("success").asBoolean(), d.toString());
        return d.path("data").path("id").asLong();
    }

    private void publishDraft(long routeId, String season, List<Map<String, Object>> segs, String reason) {
        publishVersion(saveDraft(routeId, season, segs, reason));
    }

    private void publishVersion(long versionId) {
        JsonNode p = post("/routes/versions/publish", map("versionId", versionId));
        assertTrue(p.path("success").asBoolean(), p.toString());
    }

    private JsonNode routeDiff(long versionId) {
        JsonNode d = get("/routes/versions/" + versionId + "/diff");
        assertTrue(d.path("success").asBoolean(), d.toString());
        return d.path("data");
    }

    private int countChange(JsonNode diff, String type) {
        int n = 0;
        for (JsonNode c : diff.path("changes")) {
            if (type.equals(c.path("changeType").asText())) {
                n++;
            }
        }
        return n;
    }

    /** 经 HTTP 派车（药品/车辆/发货单用服务准备），返回批次ID */
    private Long dispatchViaHttpWithRoute(long routeId) {
        Long orderId = prepareOrder();
        DispatchRequest req = new DispatchRequest();
        req.setOrderId(orderId);
        req.setVehicleId(globalVehicleId());
        req.setRouteId(routeId);
        JsonNode resp = post("/batches/dispatch", req);
        assertTrue(resp.path("success").asBoolean(), resp.toString());
        return resp.path("data").path("id").asLong();
    }

    private Long dispatchViaServiceWithRoute(long routeId) {
        DispatchRequest req = new DispatchRequest();
        req.setOrderId(prepareOrder());
        req.setVehicleId(globalVehicleId());
        req.setRouteId(routeId);
        return batchService.dispatch(req).getId();
    }

    private Long vehicleId;

    private Long globalVehicleId() {
        if (vehicleId == null) {
            VehicleCreateRequest vr = new VehicleCreateRequest();
            vr.setPlateNo("验A·冷" + System.nanoTime() % 100000);
            vr.setCarrierName("验收物流");
            vehicleId = vehicleService.create(vr).getId();
            // 发运卡口：车辆必须先绑定持有效校准凭证的探头
            bindValidProbe(vehicleId, "req-route-global-" + System.nanoTime(),
                    LocalDateTime.now().plusYears(1));
        }
        return vehicleId;
    }

    /** 建档一支持证探头并关联到车辆（有效期由调用方指定，便于构造过期场景） */
    private Long bindValidProbe(Long vehicleId, String requestId, LocalDateTime validUntil) {
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-ROUTE-" + System.nanoTime());
        pc.setModel("TP-100");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(validUntil);
        pc.setCertNo("CERT-" + System.nanoTime());
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName("cert-" + System.nanoTime() + ".txt");
        pc.setCertFileContent("calibration certificate body " + System.nanoTime());
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId(requestId);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);
        return probeId;
    }

    private Long prepareOrder() {
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("验收药品-" + System.nanoTime());
        dc.setApprovalNo("国药准字Y" + System.nanoTime() % 100000);
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Drug drug = drugService.create(dc);

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drug.getId());
        oc.setReceiverOrg("验收医院");
        oc.setShipQty(10);
        return orderService.create(oc).getId();
    }

    private JsonNode postTemp(long batchId, Object mileage, String time, double temp) {
        JsonNode r = post("/batches/temperatures", tempBody(batchId, mileage, time, temp));
        assertTrue(r.path("success").asBoolean(),
                "正常温度上报应成功: " + r.path("message").asText());
        return r;
    }

    private Map<String, Object> tempBody(long batchId, Object mileage, String time, double temp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("recordTime", time);
        m.put("temperature", temp);
        m.put("recordedBy", "车载温控仪");
        if (mileage != null) {
            m.put("mileageKm", mileage);
        }
        return m;
    }

    private long countExceptions(long batchId) {
        return exceptionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.coldchain.entity.ExceptionEvent>()
                        .eq("batch_id", batchId));
    }

    private Map<String, Object> segment(double from, double to, double min, double max,
                                        int buffer, String owner) {
        return segment(from, to, min, max, buffer, owner, null);
    }

    private Map<String, Object> segment(double from, double to, double min, double max,
                                        int buffer, String owner, String phone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fromKm", from);
        m.put("toKm", to);
        m.put("tempMin", min);
        m.put("tempMax", max);
        m.put("bufferMinutes", buffer);
        m.put("ownerName", owner);
        if (phone != null) {
            m.put("ownerPhone", phone);
        }
        return m;
    }

    private Map<String, Object> draft(String season, List<Map<String, Object>> segs, String reason) {
        return draft(season, segs, reason, null, null, null);
    }

    private Map<String, Object> draft(String season, List<Map<String, Object>> segs, String reason,
                                      LocalDateTime from, LocalDateTime to, Object ignored) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (season != null) {
            m.put("season", season);
        }
        m.put("segments", segs);
        if (reason != null) {
            m.put("changeReason", reason);
        }
        if (from != null) {
            m.put("effectiveFrom", from.toString());
        }
        if (to != null) {
            m.put("effectiveTo", to.toString());
        }
        return m;
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertEquals(false, resp.path("success").asBoolean(), "应被拒绝: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private void assertSeg(RouteSegment s, double from, double to, double min, double max,
                           int buffer, String owner) {
        assertEquals(0, s.getFromKm().compareTo(BigDecimal.valueOf(from)));
        assertEquals(0, s.getToKm().compareTo(BigDecimal.valueOf(to)));
        assertEquals(0, s.getTempMin().compareTo(BigDecimal.valueOf(min)));
        assertEquals(0, s.getTempMax().compareTo(BigDecimal.valueOf(max)));
        assertEquals(buffer, s.getBufferMinutes());
        assertEquals(owner, s.getOwnerName());
    }

    private void assertSnap(RouteSegmentSnap s, double from, double to, double min, double max,
                            int buffer, String owner) {
        assertEquals(0, s.getFromKm().compareTo(BigDecimal.valueOf(from)));
        assertEquals(0, s.getToKm().compareTo(BigDecimal.valueOf(to)));
        assertEquals(0, s.getTempMin().compareTo(BigDecimal.valueOf(min)));
        assertEquals(0, s.getTempMax().compareTo(BigDecimal.valueOf(max)));
        assertEquals(buffer, s.getBufferMinutes());
        assertEquals(owner, s.getOwnerName());
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

    private JsonNode get(String path) {
        try {
            String url = "http://localhost:" + port + BASE + path;
            String resp = http.withBasicAuth("dispatcher", "dispatcher123").getForObject(url, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
