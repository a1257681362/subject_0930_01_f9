package com.evops.coldchain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.CategoryCreateRequest;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.dto.TemplateSaveRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.dto.ZoneDraftRequest;
import com.evops.coldchain.entity.AlertScanCursor;
import com.evops.coldchain.entity.BatchAlertZoneSnap;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.AlertScanCursorMapper;
import com.evops.coldchain.mapper.BatchAlertZoneSnapMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.service.AlertScanService;
import com.evops.coldchain.service.DrugCategoryService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链 V5 验收：按药品类别的温控预警模板。
 *
 * 覆盖：
 * 1) 类别阈值继承上级 / 子类别在指定温区覆盖；派车（批次创建）即逐温区快照，标明继承来源；
 * 2) 修改模板只影响新建批次，旧批次快照与判定口径不变；
 * 3) 按采样点计算连续超限时长，短暂抖动（≤合并窗）合并不断开，超过合并窗的正常段才打断重计；
 * 4) 一次连续超限只产一条预警，模板变更后旧预警不消失/不被改写；
 * 5) 定时任务（含重启/游标从头重放）不多写预警/异常/流水，操作者记 system 不丢；
 * 6) 预警查看/导出/闭环/扫描、类别模板维护均经 Shiro 权限点校验（401/403/200），导出 CSV 留痕。
 *
 * 业务建链走服务直调（无 Shiro 主体→操作者 system），权限用真实 HTTP Basic 入口验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CategoryAlertTemplateIntegrationTest {

    private static final String BASE = "/api/cold-chain";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 3, 8, 0);

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;

    @Autowired private DrugCategoryService categoryService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;
    @Autowired private AlertScanService scanService;

    @Autowired private BatchAlertZoneSnapMapper snapMapper;
    @Autowired private TempAlertMapper alertMapper;
    @Autowired private TemperatureLogMapper temperatureMapper;
    @Autowired private ExceptionEventMapper exceptionMapper;
    @Autowired private OpLogMapper opLogMapper;
    @Autowired private AlertScanCursorMapper cursorMapper;

    // ====================================================================
    // 1) 继承 / 指定温区覆盖 / 创建时快照 / 模板修改只影响新批次 / 预警不消失
    // ====================================================================
    @Test
    void inheritOverride_snapshotIsolation_templateChangeOnlyAffectsNewBatches() {
        long nano = System.nanoTime();
        // 根类别：冷藏温区 2~8℃，容忍 30 分，抖动合并 10 分
        Long rootId = createCategory("冷藏药品根类-" + nano, null);
        saveTemplate(rootId, zone(CcConstants.ZONE_REFRIGERATE, "冷藏温区",
                "2.00", "8.00", 30, 10), true, "根类基线模板");

        // 子类别不配置任何温区 → 完全继承根类
        Long childId = createCategory("疫苗子类别-" + nano, rootId);

        // 派车第一个批次（挂子类，阈值应继承根类）
        long batchOld = createShippedBatch(childId, "继承疫苗-" + nano);
        List<BatchAlertZoneSnap> oldSnaps = snaps(batchOld);
        assertEquals(1, oldSnaps.size());
        BatchAlertZoneSnap oldSnap = oldSnaps.get(0);
        assertEquals(CcConstants.ZONE_REFRIGERATE, oldSnap.getZoneCode());
        assertTrue(oldSnap.getInherited(), "子类别未显式配置，应标记为继承");
        assertEquals(rootId, oldSnap.getSourceCategoryId(), "阈值来源应为根类别");
        assertEquals(30, oldSnap.getTolerateMinutes());
        assertEquals(10, oldSnap.getJitterGapMinutes());
        assertEquals(CcConstants.ZONE_REFRIGERATE,
                batchService.requireBatch(batchOld).getAlertZoneCode());

        // 让旧批次先产生一条连续超限预警（T0 起 40 分钟 > 容忍 30）
        report(batchOld, T0, "9.0");
        report(batchOld, T0.plusMinutes(20), "9.2");
        report(batchOld, T0.plusMinutes(40), "9.1");
        List<TempAlert> oldAlerts = alerts(batchOld);
        assertEquals(1, oldAlerts.size());
        assertEquals(30, oldAlerts.get(0).getTolerateMinutes(), "旧批次预警按创建时快照（容忍30）");
        assertEquals(0, new BigDecimal("9.2").compareTo(oldAlerts.get(0).getPeakTemperature()));
        assertEquals(1, exceptions(batchOld).size());

        // —— 修改根类模板：容忍收紧为 60 分（升一版，旧温区行置 SUPERSEDED）——
        saveTemplate(rootId, zone(CcConstants.ZONE_REFRIGERATE, "冷藏温区",
                "2.00", "8.00", 60, 10), true, "夏季放宽容忍");

        // 旧批次快照、已产生预警均不被回写或删除
        BatchAlertZoneSnap oldSnapReload = snaps(batchOld).get(0);
        assertEquals(30, oldSnapReload.getTolerateMinutes(), "旧批次快照不随后续模板变更改变");
        List<TempAlert> oldAlertsAfter = alerts(batchOld);
        assertEquals(1, oldAlertsAfter.size(), "预警产生后不因模板变更消失");
        assertEquals(30, oldAlertsAfter.get(0).getTolerateMinutes());
        assertEquals(1, exceptions(batchOld).size());

        // 新建批次：取新模板（容忍 60）
        long batchNew = createShippedBatch(childId, "继承疫苗新批-" + nano);
        BatchAlertZoneSnap newSnap = snaps(batchNew).get(0);
        assertTrue(newSnap.getInherited());
        assertEquals(60, newSnap.getTolerateMinutes(), "新批次应按修改后的模板快照（容忍60）");
        assertEquals(rootId, newSnap.getSourceCategoryId());
    }

    // ====================================================================
    // 2) 子类别在指定温区覆盖上级阈值
    // ====================================================================
    @Test
    void childOverridesZone_explicitZoneWins_othersInherited() {
        long nano = System.nanoTime() + 1;
        Long rootId = createCategory("生物制品根-" + nano, null);
        saveTemplate(rootId, zone(CcConstants.ZONE_REFRIGERATE, "冷藏温区",
                "2.00", "8.00", 30, 5), true, "根类");
        Long childId = createCategory("特殊疫苗-" + nano, rootId);
        // 子类别只覆盖冷藏温区为 2~6℃、容忍 15 分
        saveTemplate(childId, zone(CcConstants.ZONE_REFRIGERATE, "疫苗严限温区",
                "2.00", "6.00", 15, 5), true, "本类别严限");

        long batchId = createShippedBatch(childId, "严限疫苗-" + nano);
        BatchAlertZoneSnap snap = snaps(batchId).get(0);
        assertFalse(snap.getInherited(), "子类别显式覆盖的温区不应标记继承");
        assertEquals(childId, snap.getSourceCategoryId());
        assertEquals(0, new BigDecimal("6.00").compareTo(snap.getTempMax()));
        assertEquals(15, snap.getTolerateMinutes());
        assertEquals("疫苗严限温区", snap.getZoneName());
    }

    // ====================================================================
    // 3) 按采样点连续超限：短暂抖动合并不打断；超过合并窗的正常段打断重计
    // ====================================================================
    @Test
    void continuousDurationBySample_shortJitterMerged_longNormalGapBreaks() {
        long nano = System.nanoTime() + 2;
        Long catId = createCategory("抖动根类-" + nano, null);
        saveTemplate(catId, zone(CcConstants.ZONE_REFRIGERATE, "冷藏温区",
                "2.00", "8.00", 30, 10), true, "抖动合并10分钟");

        // —— 批次A：中间一次正常仅 10 分钟（≤合并窗），连续起点保持，40 分钟时预警 ——
        long a = createShippedBatch(catId, "抖动合并批A-" + nano);
        report(a, T0, "9.0");
        report(a, T0.plusMinutes(5), "9.1");
        report(a, T0.plusMinutes(10), "5.0");            // 短暂回正常（间隔5分，≤10，合并）
        report(a, T0.plusMinutes(15), "9.2");           // 距上次越限10分（≤10），合并，起点仍T0
        report(a, T0.plusMinutes(35), "9.0");           // 起点 T0→T0+35 = 35 分 > 30 → 预警
        List<TempAlert> alertsA = alerts(a);
        assertEquals(1, alertsA.size(), "短暂抖动合并后整段只应产生一条预警");
        TempAlert alert = alertsA.get(0);
        assertEquals(T0, alert.getStartTime(), "合并抖动后连续起点应为首个越限点");
        assertEquals(T0.plusMinutes(35), alert.getTriggerTime());
        assertEquals(35L, alert.getDurationMinutes());
        assertEquals(0, new BigDecimal("9.2").compareTo(alert.getPeakTemperature()));
        assertNotNull(alert.getDescription());
        assertTrue(alert.getDescription().contains("连续超限"));

        // 后续越限点不再开第二条（一次连续超限一条）
        report(a, T0.plusMinutes(36), "9.0");
        report(a, T0.plusMinutes(37), "9.0");
        assertEquals(1, alerts(a).size());
        assertEquals(CcConstants.BATCH_EXCEPTION, batchService.requireBatch(a).getStatus());
        assertEquals(1, exceptions(a).size());

        // —— 批次B：回正常 20 分钟（>合并窗10），打断；新越限段重新计时，预警起点为新段 ——
        long b = createShippedBatch(catId, "抖动打断批B-" + nano);
        report(b, T0, "9.0");
        report(b, T0.plusMinutes(5), "9.0");
        report(b, T0.plusMinutes(25), "5.0");           // 正常间隔20分 > 10，断开
        report(b, T0.plusMinutes(45), "9.0");           // 新连续段起点 T0+45
        report(b, T0.plusMinutes(50), "9.0");           // 新段5分，不预警
        report(b, T0.plusMinutes(80), "9.0");           // 新段35分 > 30 → 预警
        List<TempAlert> alertsB = alerts(b);
        assertEquals(1, alertsB.size());
        assertEquals(T0.plusMinutes(45), alertsB.get(0).getStartTime(),
                "超过合并窗的正常段必须打断连续超限，新段重新计时");
        assertEquals(T0.plusMinutes(80), alertsB.get(0).getTriggerTime());
    }

    // ====================================================================
    // 4) 定时任务重启 / 游标从头重放：不多写记录、操作者不丢（system）
    // ====================================================================
    @Test
    void scheduledScan_restartAndCursorReplay_noDuplicates_operatorKept() {
        long nano = System.nanoTime() + 3;
        // 根类维护模板，子类别不配置任何温区 → 子类别批次的“预警阈值按药品类别继承”自根类
        Long rootId = createCategory("扫描根类-" + nano, null);
        saveTemplate(rootId, zone(CcConstants.ZONE_REFRIGERATE, "冷藏温区",
                "2.00", "8.00", 30, 10), true, "扫描用");
        Long catId = createCategory("扫描子类(继承)-" + nano, rootId);
        String rootCategoryNo = categoryService.requireCategory(rootId).getCategoryNo();

        // 批次D：挂子类别，派车即按继承关系固化快照；采样点由采集侧直接落库，交给定时扫描计算
        long d = createShippedBatch(catId, "定时扫描批D-" + nano);
        BatchAlertZoneSnap inheritedSnap = snaps(d).get(0);
        assertTrue(inheritedSnap.getInherited());
        assertEquals(rootId, inheritedSnap.getSourceCategoryId());
        Long probeId = batchService.requireBatch(d).getProbeId();
        insertRawPoint(d, probeId, T0, "9.0");
        insertRawPoint(d, probeId, T0.plusMinutes(20), "9.4");
        insertRawPoint(d, probeId, T0.plusMinutes(40), "9.1");

        // 扫描前无预警/异常
        assertEquals(0, alerts(d).size());
        assertEquals(0, exceptions(d).size());

        // 第一次定时扫描：全量重放（cursor 从 0 起），D 产生 1 条预警
        Map<String, Object> first = scanService.scan();
        assertTrue(((Number) first.get("newAlerts")).intValue() >= 1);
        List<TempAlert> alertsD = alerts(d);
        assertEquals(1, alertsD.size());
        assertEquals(0, new BigDecimal("9.4").compareTo(alertsD.get(0).getPeakTemperature()));
        // 预警阈值确为“按药品类别继承”：来源类别是上级根类，阈值/容忍/抖动窗取自继承快照
        assertEquals(rootCategoryNo, alertsD.get(0).getSourceCategoryNo());
        assertEquals(30, alertsD.get(0).getTolerateMinutes());
        assertEquals(10, alertsD.get(0).getJitterGapMinutes());
        assertEquals(1, exceptions(d).size());

        // 操作者不丢：定时线程无登录主体，统一记 system（不得为 null）
        List<OpLog> raiseLogs = opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("batch_id", d).eq("action", CcConstants.ACT_ALERT_RAISE));
        assertEquals(1, raiseLogs.size());
        assertNotNull(raiseLogs.get(0).getOperator());
        assertEquals("system", raiseLogs.get(0).getOperator());

        // 无新采样点再扫描：不产生新预警、不新增产生流水，甚至不重放任何批次
        long raiseBefore = countAction(CcConstants.ACT_ALERT_RAISE);
        Map<String, Object> idle = scanService.scan();
        assertEquals(0, ((Number) idle.get("scannedBatches")).intValue());
        assertEquals(1, alerts(d).size());
        assertEquals(1, exceptions(d).size());
        assertEquals(raiseBefore, countAction(CcConstants.ACT_ALERT_RAISE));

        // 模拟“重启定时任务且扫描游标丢失/从头重放”：预警仍只有一条，不多写任何记录
        AlertScanCursor cursor = cursorMapper.selectById(1);
        assertNotNull(cursor);
        cursor.setLastLogId(0L);
        cursorMapper.updateById(cursor);
        scanService.scan();
        scanService.scan();
        assertEquals(1, alerts(d).size(), "重启/游标重放不得多写预警");
        assertEquals(1, exceptions(d).size(), "重启/游标重放不得多写温度异常");
        assertEquals(1, opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("batch_id", d).eq("action", CcConstants.ACT_ALERT_RAISE)).intValue(),
                "重启/游标重放不得多写产生流水");

        // 出现新一段越限（超窗断开后的新起点）后再扫描：只新增新段的一条
        insertRawPoint(d, probeId, T0.plusMinutes(90), "5.0");   // 正常，与上段间隔50分>10，断开
        insertRawPoint(d, probeId, T0.plusMinutes(100), "9.0");
        insertRawPoint(d, probeId, T0.plusMinutes(140), "9.0");  // 新段40分>30 → 第二条
        scanService.scan();
        assertEquals(2, alerts(d).size(), "新连续段应再产生一条，旧预警保留");
        List<OpLog> raise2 = opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("batch_id", d).eq("action", CcConstants.ACT_ALERT_RAISE));
        assertEquals(2, raise2.size());
        for (OpLog l : raise2) {
            assertEquals("system", l.getOperator(), "扫描产生的每条预警都必须带操作者");
        }
    }

    // ====================================================================
    // 5) Shiro 权限点：查看 / 导出 / 闭环 / 扫描 / 模板维护（401/403/200）+ CSV
    // ====================================================================
    @Test
    void shiroGuards_viewExportResolveScanManage_andCsvExport() {
        long nano = System.nanoTime() + 4;

        // 未认证 → 401
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("/alerts", HttpMethod.GET, null, null, String.class).getStatusCode());

        // carrier 无任何预警权限：查看/导出/建类别 → 403
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/alerts", HttpMethod.GET, null, "carrier", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/alerts/export", HttpMethod.GET, null, "carrier", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/categories", HttpMethod.POST,
                        Collections.singletonMap("categoryName", "无权类别-" + nano), "carrier", String.class)
                        .getStatusCode());

        // receiver 只有查看权：预警列表 / 类别树 200；导出/扫描/闭环 403
        assertEquals(HttpStatus.OK,
                exchange("/alerts", HttpMethod.GET, null, "receiver", String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                exchange("/categories/tree", HttpMethod.GET, null, "receiver", String.class).getStatusCode());
        // carrier 无查看权：类别树同样 403
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/categories/tree", HttpMethod.GET, null, "carrier", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/alerts/export", HttpMethod.GET, null, "receiver", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/alerts/scan", HttpMethod.POST, Collections.emptyMap(), "receiver", String.class)
                        .getStatusCode());

        // dispatcher 有模板维护权：建类别 200；但无导出权 → 403
        ResponseEntity<String> created = exchange("/categories", HttpMethod.POST,
                mapOf("categoryName", "HTTP类别-" + nano, "remark", "dispatcher建"), "dispatcher", String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertTrue(created.getBody().contains("\"success\":true"));
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/alerts/export", HttpMethod.GET, null, "dispatcher", String.class).getStatusCode());

        // 造一条 OPEN 预警供闭环权限验证（服务直调，操作者 system）
        Long catId = createCategory("闭环权限类-" + nano, null);
        saveTemplate(catId, zone(CcConstants.ZONE_REFRIGERATE, "冷藏温区",
                "2.00", "8.00", 30, 10), true, "闭环用");
        long batchId = createShippedBatch(catId, "闭环批-" + nano);
        report(batchId, T0, "9.0");
        report(batchId, T0.plusMinutes(40), "9.0");
        Long alertId = alerts(batchId).get(0).getId();

        // receiver 无闭环权 → 403
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/alerts/" + alertId + "/resolve", HttpMethod.POST,
                        Collections.singletonMap("resolveNote", "越权闭环"), "receiver", String.class)
                        .getStatusCode());

        // admin：手动扫描 200、闭环 200、导出 CSV 200
        assertEquals(HttpStatus.OK,
                exchange("/alerts/scan", HttpMethod.POST, Collections.emptyMap(), "admin", String.class)
                        .getStatusCode());
        ResponseEntity<String> resolved = exchange("/alerts/" + alertId + "/resolve", HttpMethod.POST,
                Collections.singletonMap("resolveNote", "回温复核合格"), "admin", String.class);
        assertEquals(HttpStatus.OK, resolved.getStatusCode());
        assertEquals(CcConstants.ALERT_RESOLVED, alertMapper.selectById(alertId).getStatus());
        // V6：预警闭环不再越级关闭联动温度异常——异常按分级机制保持已确认(CONFIRMED)待处置，
        // 且温度分级与处置时钟按临界时间同一口径：firstFoundTime == 异常 foundTime/confirmTime == 预警触发时刻
        for (ExceptionEvent e : exceptions(batchId)) {
            assertEquals(CcConstants.EXC_CONFIRMED, e.getStatus(),
                    "预警闭环不得越级关闭分级温度异常，须引用证据并经质量复核关闭");
            assertEquals(CcConstants.GRADE_L1, e.getGrade());
            assertEquals(0, alertMapper.selectById(alertId).getFirstFoundTime().compareTo(e.getFoundTime()));
            assertEquals(0, alertMapper.selectById(alertId).getFirstFoundTime().compareTo(e.getConfirmTime()));
            assertEquals(0, e.getConfirmTime().compareTo(e.getFoundTime()),
                    "系统判定异常时钟起点=首次发现临界时刻");
        }

        ResponseEntity<byte[]> csv = exchange("/alerts/export", HttpMethod.GET, null, "admin", byte[].class);
        assertEquals(HttpStatus.OK, csv.getStatusCode());
        MediaType contentType = csv.getHeaders().getContentType();
        assertNotNull(contentType);
        assertTrue(contentType.toString().startsWith("text/csv"));
        byte[] body = csv.getBody();
        assertNotNull(body);
        // UTF-8 BOM
        assertEquals(0xEF, body[0] & 0xFF);
        assertEquals(0xBB, body[1] & 0xFF);
        String csvText = new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csvText.contains("预警ID"));
        assertTrue(csvText.contains(CcConstants.ZONE_REFRIGERATE));
        // 导出逐次留痕，操作者=admin
        long exportLogs = opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("action", CcConstants.ACT_ALERT_EXPORT));
        assertTrue(exportLogs >= 1);
        OpLog lastExport = opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("action", CcConstants.ACT_ALERT_EXPORT).orderByDesc("id").last("LIMIT 1")).get(0);
        assertEquals("admin", lastExport.getOperator());

        // 管理页面同样要过查看权限：receiver 可看，carrier 403
        ResponseEntity<String> page = http.withBasicAuth("receiver", "receiver123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/alerts", String.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchangeUi("/ui/cold-chain/alerts", "carrier"));
    }

    // ---------------- 建链 / 上报 / 断言辅助 ----------------

    private Long createCategory(String name, Long parentId) {
        CategoryCreateRequest req = new CategoryCreateRequest();
        req.setCategoryName(name);
        req.setParentId(parentId);
        return categoryService.createCategory(req).getId();
    }

    private void saveTemplate(Long categoryId, ZoneDraftRequest zone, boolean inherit, String reason) {
        TemplateSaveRequest req = new TemplateSaveRequest();
        req.setInheritParent(inherit);
        req.setChangeReason(reason);
        List<ZoneDraftRequest> zones = new ArrayList<>();
        zones.add(zone);
        req.setZones(zones);
        categoryService.saveTemplate(categoryId, req);
    }

    private ZoneDraftRequest zone(String code, String name, String min, String max,
                                  int tolerate, int jitter) {
        ZoneDraftRequest z = new ZoneDraftRequest();
        z.setZoneCode(code);
        z.setZoneName(name);
        z.setTempMin(new BigDecimal(min));
        z.setTempMax(new BigDecimal(max));
        z.setTolerateMinutes(tolerate);
        z.setJitterGapMinutes(jitter);
        return z;
    }

    /** 建档（挂类别）→订单→车辆→持证探头绑定→派车（固化模板快照）→发运 */
    private long createShippedBatch(Long categoryId, String uniq) {
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName(uniq);
        dc.setApprovalNo("国药准字V5-" + Math.abs(uniq.hashCode() % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        dc.setCategoryId(categoryId);
        Long realDrugId = createDrug(dc);

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(realDrugId);
        oc.setReceiverOrg("V5验收医院");
        oc.setShipQty(10);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测5·冷" + Math.abs(uniq.hashCode() % 1000000));
        vr.setCarrierName("V5物流");
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        String serial = "SN-V5-" + Math.abs(uniq.hashCode());
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo(serial);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(T0.plusYears(1));
        pc.setCertNo("CERT-" + serial);
        pc.setCalibrateDate(T0.minusDays(1));
        pc.setCertFileName(serial + ".txt");
        pc.setCertFileContent("cert " + serial);
        Long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("bind-" + uniq);
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

    @Autowired
    private com.evops.coldchain.service.DrugService drugService;

    private Long createDrug(DrugCreateRequest dc) {
        return drugService.create(dc).getId();
    }

    private void report(long batchId, LocalDateTime time, String temp) {
        TempReportRequest req = new TempReportRequest();
        req.setBatchId(batchId);
        req.setRecordTime(time);
        req.setTemperature(new BigDecimal(temp));
        req.setRecordedBy("车载温控仪");
        batchService.reportTemperature(req);
    }

    /** 采集侧直接落一条温度留痕（不经过实时评估），用于定时扫描测试 */
    private void insertRawPoint(long batchId, Long probeId, LocalDateTime time, String temp) {
        BigDecimal value = new BigDecimal(temp);
        TemperatureLog l = new TemperatureLog();
        l.setBatchId(batchId);
        l.setRecordTime(time);
        l.setTemperature(value);
        l.setInRange(value.compareTo(new BigDecimal("2.00")) >= 0
                && value.compareTo(new BigDecimal("8.00")) <= 0);
        l.setRecordedBy("采集直传");
        l.setProbeId(probeId);
        temperatureMapper.insert(l);
    }

    private List<BatchAlertZoneSnap> snaps(long batchId) {
        return snapMapper.selectList(new QueryWrapper<BatchAlertZoneSnap>()
                .eq("batch_id", batchId).orderByAsc("zone_code"));
    }

    private List<TempAlert> alerts(long batchId) {
        return alertMapper.selectList(new QueryWrapper<TempAlert>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    private List<ExceptionEvent> exceptions(long batchId) {
        return exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    private long countAction(String action) {
        return opLogMapper.selectCount(new QueryWrapper<OpLog>().eq("action", action));
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body,
                                           String user, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        TestRestTemplate template = user == null ? http : http.withBasicAuth(user, user + "123");
        Class<T> type = responseType != null ? responseType : (Class<T>) String.class;
        return template.exchange("http://localhost:" + port + BASE + path, method, entity, type);
    }

    private HttpStatus exchangeUi(String path, String user) {
        return http.withBasicAuth(user, user + "123")
                .getForEntity("http://localhost:" + port + path, String.class).getStatusCode();
    }
}
