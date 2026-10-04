package com.evops.coldchain;

import com.evops.coldchain.dto.BatchRejectRequest;
import com.evops.coldchain.dto.BatchSettleRequest;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.GradeRuleChangeRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionNotifyLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.service.ExceptionGradeService;
import com.evops.coldchain.service.ExceptionLifecycleService;
import com.evops.coldchain.service.ExceptionNotifyTransport;
import com.evops.coldchain.service.HandoverService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.coldchain.support.TestSubjects;
import com.evops.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V6 验收：异常分级与处置时钟。
 *
 * 覆盖：
 * 1) 四类异常分级规则（等级/响应时限/处置时限/处置岗/升级岗/复核岗），确认瞬间快照，
 *    规则调整只影响此后确认的异常；
 * 2) 异常确认才启动处置时钟；首次发现时间只写一次，补录/确认都不覆盖；
 * 3) 温度超限在达到临界时刻即确认，foundTime/confirmTime 同口径，截止=临界+快照时限；
 * 4) 升级通知发送结果与失败原因逐次落库，手动/定时重试必须记录重试原因，attempt 递增；
 * 5) 处置结论必须引用本异常证据；关闭前必须由 QA 岗位复核，驳回可重处、通过才关闭；
 * 6) 未关闭异常（含待确认）拦截签收与结算；收货拒签成立即重大异常。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ExceptionGradingLifecycleIntegrationTest.ScriptedTransportConfig.class)
class ExceptionGradingLifecycleIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 8, 0);

    @LocalServerPort
    private int port;
    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate http;

    @Autowired private TransportBatchService batchService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private ExceptionLifecycleService lifecycle;
    @Autowired private ExceptionGradeService gradeService;
    @Autowired private HandoverService handoverService;
    @Autowired private TestSubjects testSubjects;
    @Autowired private ScriptedTransport transport;

    /**
     * 用例后清理：恢复默认分级规则，并关闭本测试上下文内所有遗留未关闭异常。
     * 内存库在用例间共享，处置时钟扫描是全局口径，必须保证下一用例扫描时没有外来异常干扰计数。
     */
    @AfterEach
    void tearDown() {
        // 先保证通知通道恢复成功，避免清理时确认异常的首响通知产生新的失败行
        transport.failuresRemaining = 0;
        transport.endpoints.clear();
        // 只清理本测试类产生的批次，绝不触碰其它测试类在共享内存库中的数据
        if (!ownedBatchIds.isEmpty()) {
            List<ExceptionEvent> owned = exceptionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExceptionEvent>()
                            .in("batch_id", ownedBatchIds));
            for (ExceptionEvent e : owned) {
                Long id = e.getId();
                if (CcConstants.EXC_OPEN.equals(e.getStatus())) {
                    lifecycle.confirm(id);
                }
                ExceptionEvent cur = lifecycle.requireEvent(id);
                if (CcConstants.EXC_CONFIRMED.equals(cur.getStatus())) {
                    appendEvidence(id, "cleanup-" + id + ".txt", "/v/cleanup/" + id, "用例清理补充证据");
                    Long evId = batchService.listEvidence(id).get(0).getId();
                    lifecycle.conclude(id, "用例清理：批量关闭遗留异常",
                            java.util.Collections.singletonList(evId));
                }
                if (CcConstants.EXC_REVIEWING.equals(lifecycle.requireEvent(id).getStatus())) {
                    testSubjects.runAs(6L, "qa", () -> lifecycle.review(id, true, "用例清理：QA 复核关闭"));
                }
            }
        }
        ownedBatchIds.clear();
        changeRule(CcConstants.EXC_TEMP, CcConstants.GRADE_L1, "重大", 30, 120,
                CcConstants.ROLE_CARRIER, CcConstants.ROLE_DISPATCHER, CcConstants.ROLE_QA, null);
        changeRule(CcConstants.EXC_DELAY, CcConstants.GRADE_L3, "一般", 120, 480,
                CcConstants.ROLE_CARRIER, CcConstants.ROLE_DISPATCHER, CcConstants.ROLE_QA, null);
        transport.failuresRemaining = 0;
        transport.endpoints.clear();
    }

    /** 可控通知通道：前 failuresRemaining 次发送失败并给固定原因，之后成功 */
    @org.springframework.boot.test.context.TestConfiguration
    static class ScriptedTransportConfig {
        @Bean
        @Primary
        public ExceptionNotifyTransport scriptedNotifyTransport() {
            return new ScriptedTransport();
        }
    }

    static class ScriptedTransport implements ExceptionNotifyTransport {
        int failuresRemaining = 0;
        final List<String> endpoints = new ArrayList<>();

        @Override
        public synchronized SendResult send(String endpoint, String payload) {
            endpoints.add(endpoint);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return SendResult.fail("模拟投递失败：对端未响应");
            }
            return SendResult.ok();
        }
    }

    // ====================================================================
    // 1) 分级快照 + 确认启钟 + 首次发现时间补录不覆盖 + 温度临界同口径
    // ====================================================================
    @Test
    void gradeSnapshot_clockStartsOnConfirm_foundTimeNeverOverwritten_tempCriticalSameBasis() {
        long batchId = shipNewBatch();

        // 温度超限（无路线批次，越限即越过临界）：系统判定成立即确认，时钟起点=临界采样时刻
        reportTemp(batchId, T0.plusHours(1), new BigDecimal("11.50"));
        ExceptionEvent temp = onlyOpenException(batchId);
        assertEquals(CcConstants.EXC_TEMP, temp.getExceptionType());
        assertEquals(CcConstants.EXC_CONFIRMED, temp.getStatus());
        assertEquals("AUTO", temp.getFoundSource());
        assertEquals(CcConstants.GRADE_L1, temp.getGrade());
        assertEquals(0, temp.getFoundTime().compareTo(T0.plusHours(1)), "首次发现时间=临界时刻");
        assertEquals(0, temp.getConfirmTime().compareTo(temp.getFoundTime()),
                "温度分级与处置时钟必须按同一口径归属（临界时刻）");
        assertEquals(0, temp.getResponseDueTime().compareTo(T0.plusHours(1).plusMinutes(30)));
        assertEquals(0, temp.getResolveDueTime().compareTo(T0.plusHours(1).plusMinutes(120)));
        assertEquals(CcConstants.ROLE_CARRIER, temp.getOwnerRoleSnap());
        assertEquals(CcConstants.ROLE_DISPATCHER, temp.getEscalateRoleSnap());
        assertEquals(CcConstants.ROLE_QA, temp.getReviewerRoleSnap());
        // 默认无 webhook：站内信首响一次成功
        List<ExceptionNotifyLog> logs = lifecycle.listNotifications(temp.getId());
        assertEquals(1, logs.size());
        assertEquals(CcConstants.NOTIFY_RESPONSE, logs.get(0).getNotifyStage());
        assertEquals(CcConstants.NOTIFY_SUCCESS, logs.get(0).getSendStatus());
        assertEquals(CcConstants.NOTIFY_CHANNEL_SITE, logs.get(0).getChannel());

        // 人工补录延误：OPEN 待确认，时钟不启动
        ExceptionEvent delay = markDelay(batchId, T0.plusHours(2), "高速管制延误2小时");
        assertEquals(CcConstants.EXC_OPEN, delay.getStatus());
        assertEquals("MANUAL", delay.getFoundSource());
        assertEquals(0, delay.getFoundTime().compareTo(T0.plusHours(2)));
        // 补录一个更早/更晚的“发现时间”，也只是新行，绝不覆盖既有首次发现时间
        markDelay(batchId, T0, "另一条补录，时间更早");
        delay = lifecycle.requireEvent(delay.getId());
        assertEquals(0, delay.getFoundTime().compareTo(T0.plusHours(2)), "补录不得覆盖首次发现时间");

        // 显式确认：定级快照，时钟自确认时刻起算，但 foundTime 保持首次发现时间不变
        LocalDateTime beforeConfirm = LocalDateTime.now().minusSeconds(1);
        ExceptionEvent confirmed = lifecycle.confirm(delay.getId());
        LocalDateTime afterConfirm = LocalDateTime.now().plusSeconds(1);
        assertEquals(CcConstants.EXC_CONFIRMED, confirmed.getStatus());
        assertEquals(CcConstants.GRADE_L3, confirmed.getGrade());
        assertEquals(0, confirmed.getFoundTime().compareTo(T0.plusHours(2)),
                "确认启钟不得改写首次发现时间");
        assertNotNull(confirmed.getConfirmTime());
        assertFalse(confirmed.getConfirmTime().isBefore(beforeConfirm));
        assertFalse(confirmed.getConfirmTime().isAfter(afterConfirm));
        assertEquals(0, confirmed.getResponseDueTime().compareTo(confirmed.getConfirmTime().plusMinutes(120)));
        assertEquals(0, confirmed.getResolveDueTime().compareTo(confirmed.getConfirmTime().plusMinutes(480)));
        // 重复确认/回拨时钟一律拒绝
        assertThrows(BusinessException.class, () -> lifecycle.confirm(confirmed.getId()));

        // 调整 DELAY 规则（60/600）：只影响此后确认的异常，旧异常快照不变
        changeRule(CcConstants.EXC_DELAY, CcConstants.GRADE_L3, "一般", 60, 600,
                CcConstants.ROLE_CARRIER, CcConstants.ROLE_DISPATCHER, CcConstants.ROLE_QA, null);
        ExceptionEvent delay2 = markDelay(batchId, T0.plusHours(3), "二次延误");
        lifecycle.confirm(delay2.getId());
        ExceptionEvent delay2Done = lifecycle.requireEvent(delay2.getId());
        assertEquals(60, delay2Done.getResponseMinutesSnap());
        assertEquals(600, delay2Done.getDeadlineMinutesSnap());
        ExceptionEvent delay1Reload = lifecycle.requireEvent(delay.getId());
        assertEquals(120, delay1Reload.getResponseMinutesSnap(), "已确认异常快照不随规则调整变动");
        assertEquals(480, delay1Reload.getDeadlineMinutesSnap());
        ExceptionEvent tempReload = lifecycle.requireEvent(temp.getId());
        assertEquals(30, tempReload.getResponseMinutesSnap());
        assertEquals(120, tempReload.getDeadlineMinutesSnap());
    }

    // ====================================================================
    // 2) 结论必须引用证据 + QA 复核（驳回重处/通过关闭，非 QA 禁止复核）
    // ====================================================================
    @Test
    void conclusionMustReferenceEvidence_onlyQaReviews_rejectThenApprove() {
        long batchId = shipNewBatch();
        reportTemp(batchId, T0.plusHours(1), new BigDecimal("11.50"));
        ExceptionEvent exc = onlyOpenException(batchId);

        // 无结论/无证据引用/引用不存在的证据，全部拒绝
        assertThrows(BusinessException.class,
                () -> lifecycle.conclude(exc.getId(), " ", Collections.singletonList(1L)));
        BusinessException noRef = assertThrows(BusinessException.class,
                () -> lifecycle.conclude(exc.getId(), "药品评估合格", Collections.emptyList()));
        assertTrue(noRef.getMessage().contains("引用证据"));
        BusinessException badRef = assertThrows(BusinessException.class,
                () -> lifecycle.conclude(exc.getId(), "药品评估合格", Collections.singletonList(999999L)));
        assertTrue(badRef.getMessage().contains("不存在或不属于"));

        // 追加证据并引用后进入待复核
        appendEvidence(exc.getId(), "curve.csv", "/v/curve", "越限温控曲线");
        Long evidenceId = batchService.listEvidence(exc.getId()).get(0).getId();
        ExceptionEvent reviewing = lifecycle.conclude(exc.getId(),
                "回温30分钟恢复5.6℃，评估药品合格", Collections.singletonList(evidenceId));
        assertEquals(CcConstants.EXC_REVIEWING, reviewing.getStatus());
        assertEquals(CcConstants.REVIEW_PENDING, reviewing.getReviewStatus());
        assertEquals(String.valueOf(evidenceId), reviewing.getEvidenceRefs());
        // 待复核中不能重复提交结论
        assertThrows(BusinessException.class,
                () -> lifecycle.conclude(exc.getId(), "再次提交", Collections.singletonList(evidenceId)));

        // 非 QA 角色不能复核
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> testSubjects.runAs(2L, "dispatcher",
                        () -> lifecycle.review(exc.getId(), true, "调度越权复核")));
        assertTrue(forbidden.getMessage().contains("QA"));

        // QA 驳回：退回 CONFIRMED，时钟截止不变，复核意见追加一行，结论保留
        testSubjects.runAs(6L, "qa", () -> lifecycle.review(exc.getId(), false, "证据不足，请补充到货温度照片"));
        ExceptionEvent rejected = lifecycle.requireEvent(exc.getId());
        assertEquals(CcConstants.EXC_CONFIRMED, rejected.getStatus());
        assertEquals(CcConstants.REVIEW_REJECTED, rejected.getReviewStatus());
        assertNotNull(rejected.getConclusion());
        assertEquals(1, lifecycle.listReviews(exc.getId()).size());
        // 时钟没有重启：截止时间仍是首次确认时算出的时间
        assertEquals(0, rejected.getResolveDueTime().compareTo(T0.plusHours(1).plusMinutes(120)));

        // 补充证据后重新提交，QA 复核通过关闭
        appendEvidence(exc.getId(), "arrival.jpg", "/v/arrival", "到货温度照片");
        List<Long> allEvidence = new ArrayList<>();
        batchService.listEvidence(exc.getId()).forEach(e -> allEvidence.add(e.getId()));
        lifecycle.conclude(exc.getId(), "补充照片后复核：药品合格，可放行", allEvidence);
        testSubjects.runAs(6L, "qa", () -> lifecycle.review(exc.getId(), true, "证据链完整，同意关闭"));
        ExceptionEvent closed = lifecycle.requireEvent(exc.getId());
        assertEquals(CcConstants.EXC_RESOLVED, closed.getStatus());
        assertEquals(CcConstants.REVIEW_APPROVED, closed.getReviewStatus());
        assertNotNull(closed.getReviewTime());
        assertEquals(2, lifecycle.listReviews(exc.getId()).size(), "两次复核意见都必须留痕");
        assertEquals(0, lifecycle.unclosedCount(batchId));
    }

    // ====================================================================
    // 3) 通知发送结果/失败原因/重试 + 逾期升级（每异常只升级一次）
    // ====================================================================
    @Test
    void notifyFailureRecordsReason_retryWithReason_andOverdueEscalatesOnce() {
        // 给温度规则配 webhook，前若干次发送走脚本失败
        changeRule(CcConstants.EXC_TEMP, CcConstants.GRADE_L1, "重大", 30, 120,
                CcConstants.ROLE_CARRIER, CcConstants.ROLE_DISPATCHER, CcConstants.ROLE_QA,
                "http://notify.test/cold-chain");
        long batchId = shipNewBatch();
        // 临界时刻取 35 分钟前：响应截止已过但处置截止（+120分钟）未到：扫描只重试通知、不升级。
        // 失败次数给足：全局扫描可能同时重试共享库中其它遗留异常，本用例只按目标异常自身的通知行断言。
        transport.failuresRemaining = 100;
        reportTemp(batchId, LocalDateTime.now().minusMinutes(35), new BigDecimal("11.50"));
        ExceptionEvent exc = onlyOpenException(batchId);

        List<ExceptionNotifyLog> logs = lifecycle.listNotifications(exc.getId());
        assertEquals(1, logs.size());
        assertEquals(CcConstants.NOTIFY_FAILED, logs.get(0).getSendStatus());
        assertEquals(CcConstants.NOTIFY_CHANNEL_WEBHOOK, logs.get(0).getChannel());
        assertTrue(logs.get(0).getFailReason().contains("模拟投递失败"));
        assertEquals("http://notify.test/cold-chain", transport.endpoints.get(0));

        // 手动重试必须填写重试原因
        assertThrows(BusinessException.class, () -> lifecycle.retryNotify(exc.getId(), "  "));
        // 重试仍失败：独立新行，记录重试原因，attempt 递增，历史失败行保留
        ExceptionNotifyLog retry1 = lifecycle.retryNotify(exc.getId(), "承运岗反馈未收到升级通知");
        assertEquals(CcConstants.NOTIFY_RETRY, retry1.getNotifyStage());
        assertEquals(1, retry1.getAttemptNo());
        assertEquals(CcConstants.NOTIFY_FAILED, retry1.getSendStatus());
        assertEquals("承运岗反馈未收到升级通知", retry1.getRetryReason());
        assertEquals(2, lifecycle.listNotifications(exc.getId()).size());

        // 定时扫描：最近一次仍失败 → 自动重试一行，重试原因取上一次失败原因
        Map<String, Object> summary = lifecycle.scanClock(LocalDateTime.now());
        // 该异常响应虽已逾期但处置截止（+120分钟）未到：不升级，仅自动重试失败通知
        assertFalse(lifecycle.requireEvent(exc.getId()).getEscalated());
        ExceptionNotifyLog retry2 = lifecycle.listNotifications(exc.getId()).get(2);
        assertEquals(CcConstants.NOTIFY_RETRY, retry2.getNotifyStage());
        assertEquals(2, retry2.getAttemptNo());
        assertTrue(retry2.getRetryReason().contains("模拟投递失败"));
        assertEquals(3, lifecycle.listNotifications(exc.getId()).size());
        assertNotNull(summary.get("escalated"));

        // 通道恢复后补发成功，之后不得再无意义重试
        transport.failuresRemaining = 0;
        lifecycle.retryNotify(exc.getId(), "继续补发直至送达");
        BusinessException again = assertThrows(BusinessException.class,
                () -> lifecycle.retryNotify(exc.getId(), "再发一次"));
        assertTrue(again.getMessage().contains("已成功送达"));

        // 逾期升级：用 T0（2026-10-01）的临界时刻再造一条温度异常，处置截止早已逾期
        long overdueBatch = shipNewBatch();
        // 让该异常首响即成功（脚本失败次数已耗尽）
        reportTemp(overdueBatch, T0.plusHours(1), new BigDecimal("12.00"));
        ExceptionEvent overdue = onlyOpenException(overdueBatch);
        lifecycle.scanClock(LocalDateTime.now());
        ExceptionEvent escalated = lifecycle.requireEvent(overdue.getId());
        assertTrue(escalated.getEscalated());
        assertNotNull(escalated.getEscalateTime());
        List<ExceptionNotifyLog> escalateLogs = lifecycle.listNotifications(overdue.getId());
        // 首响 + 一次升级
        assertEquals(2, escalateLogs.size());
        assertEquals(CcConstants.NOTIFY_ESCALATE, escalateLogs.get(1).getNotifyStage());
        assertEquals(CcConstants.ROLE_DISPATCHER, escalateLogs.get(1).getTargetRole());
        assertEquals(CcConstants.NOTIFY_SUCCESS, escalateLogs.get(1).getSendStatus());
        // 再次扫描：升级事实只进不退，不重复升级
        lifecycle.scanClock(LocalDateTime.now());
        assertTrue(lifecycle.requireEvent(overdue.getId()).getEscalated());
        assertEquals(2, lifecycle.listNotifications(overdue.getId()).size());
    }

    // ====================================================================
    // 4) 未关闭异常拦截签收/结算；收货拒签即重大异常；关闭+签收后才能结算
    // ====================================================================
    @Test
    void unclosedBlocksSignAndSettle_rejectionIsMajor_settleAfterClosure() {
        // 批次A：温度异常关闭后签收、结算
        long batchA = shipNewBatch();
        reportTemp(batchA, T0.plusHours(1), new BigDecimal("11.50"));
        SignRequest signA = signReq(batchA);
        BusinessException blockedSign = assertThrows(BusinessException.class, () -> batchService.sign(signA));
        assertTrue(blockedSign.getMessage().contains("未关闭异常"));
        BusinessException blockedSettle = assertThrows(BusinessException.class,
                () -> batchService.settle(settleReq(batchA)));
        assertTrue(blockedSettle.getMessage().contains("签收闭环"), "未签收批次结算应先被状态卡口拦截");

        closeTempExceptionWithQa(batchA);
        batchService.sign(signReq(batchA));
        TransportBatch settled = batchService.settle(settleReq(batchA));
        assertNotNull(settled.getSettleTime());
        assertThrows(BusinessException.class, () -> batchService.settle(settleReq(batchA)));

        // 批次B：收货拒签——成立即 REJECTION/L1 确认启钟，签收被拦截
        long batchB = shipNewBatch();
        BatchRejectRequest rejectReq = new BatchRejectRequest();
        rejectReq.setBatchId(batchB);
        rejectReq.setRejectBy("院药库张老师");
        rejectReq.setReason("到货温度超标且外箱破损，整批拒收");
        ExceptionEvent rejection = batchService.reject(rejectReq);
        assertEquals(CcConstants.EXC_REJECTION, rejection.getExceptionType());
        assertEquals(CcConstants.EXC_CONFIRMED, rejection.getStatus());
        assertEquals(CcConstants.GRADE_L1, rejection.getGrade());
        assertEquals(CcConstants.ROLE_DISPATCHER, rejection.getOwnerRoleSnap());
        assertEquals(CcConstants.ROLE_ADMIN, rejection.getEscalateRoleSnap());
        assertEquals(0, rejection.getFoundTime().compareTo(rejection.getConfirmTime()));
        assertThrows(BusinessException.class, () -> batchService.sign(signReq(batchB)));

        // 关闭拒签异常后签收、结算放行
        appendEvidence(rejection.getId(), "reject.jpg", "/v/reject", "拒收现场照片");
        Long evId = batchService.listEvidence(rejection.getId()).get(0).getId();
        lifecycle.conclude(rejection.getId(), "调度介入：换货补发，本批退回并隔离", Collections.singletonList(evId));
        testSubjects.runAs(6L, "qa", () -> lifecycle.review(rejection.getId(), true, "换货方案与证据齐备，同意关闭"));
        batchService.sign(signReq(batchB));
        assertNotNull(batchService.settle(settleReq(batchB)).getSettleTime());

        // 批次C：只补录不确认的异常（待确认）同样拦截签收
        long batchC = shipNewBatch();
        markDelay(batchC, T0.plusHours(1), "司机报备路况延误");
        BusinessException blockedC = assertThrows(BusinessException.class,
                () -> batchService.sign(signReq(batchC)));
        assertTrue(blockedC.getMessage().contains("待确认"));
    }

    // ====================================================================
    // 5) Shiro 权限点：未认证 401、无权限 403、有权限 200；复核接口仅 QA 角色可过业务校验
    // ====================================================================
    @Test
    void shiroPermissionPoints_enforcedOverHttp() {
        // 规则管理：未认证 401；carrier/qa 无管理权限 403；admin 200
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity(url("/exceptions/grade-rules"), String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                http.withBasicAuth("carrier", "carrier123")
                        .getForEntity(url("/exceptions/grade-rules"), String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                http.withBasicAuth("qa", "qa123")
                        .getForEntity(url("/exceptions/grade-rules"), String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                http.withBasicAuth("admin", "admin123")
                        .getForEntity(url("/exceptions/grade-rules"), String.class).getStatusCode());

        long batchId = shipNewBatch();
        ExceptionEvent delay = markDelay(batchId, T0.plusHours(1), "HTTP 权限验证用延误");

        // 确认启钟：qa 无确认权限 403；dispatcher 有权限 200
        HttpStatus qaConfirm = postStatus("/exceptions/" + delay.getId() + "/confirm", "qa",
                Collections.emptyMap());
        assertEquals(HttpStatus.FORBIDDEN, qaConfirm);
        HttpStatus dispatcherConfirm = postStatus("/exceptions/" + delay.getId() + "/confirm", "dispatcher",
                Collections.emptyMap());
        assertEquals(HttpStatus.OK, dispatcherConfirm);

        // 追加证据（走登录即可的批次接口）后提交结论：carrier 有处置权限可提交，qa 没有 conclude 权限 403
        appendEvidence(delay.getId(), "http-evidence.txt", "/v/http-evidence", "HTTP 用证据");
        Long evidenceId = batchService.listEvidence(delay.getId()).get(0).getId();
        java.util.Map<String, Object> concludeBody = new java.util.LinkedHashMap<>();
        concludeBody.put("conclusion", "调度处置完成，道路疏通后送达，药品质量无影响");
        concludeBody.put("evidenceIds", Collections.singletonList(evidenceId));
        assertEquals(HttpStatus.FORBIDDEN,
                postStatus("/exceptions/" + delay.getId() + "/conclude", "qa", concludeBody));
        assertEquals(HttpStatus.OK,
                postStatus("/exceptions/" + delay.getId() + "/conclude", "dispatcher", concludeBody));

        // 复核：dispatcher 已登录但无复核权限点 → 403；qa 有权限点且角色匹配 → 200
        java.util.Map<String, Object> reviewBody = new java.util.LinkedHashMap<>();
        reviewBody.put("approved", true);
        reviewBody.put("reviewNote", "QA HTTP 复核：证据与结论齐备，同意关闭");
        assertEquals(HttpStatus.FORBIDDEN,
                postStatus("/exceptions/" + delay.getId() + "/review", "dispatcher", reviewBody));
        assertEquals(HttpStatus.OK,
                postStatus("/exceptions/" + delay.getId() + "/review", "qa", reviewBody));
        assertEquals(CcConstants.EXC_RESOLVED, lifecycle.requireEvent(delay.getId()).getStatus());

        // 结算：carrier 无结算权限 403；admin 有权限（先签收）
        batchService.sign(signReq(batchId));
        assertEquals(HttpStatus.FORBIDDEN,
                postStatus("/batches/settle", "carrier",
                        Collections.singletonMap("batchId", batchId)));
        assertEquals(HttpStatus.OK,
                postStatus("/batches/settle", "admin",
                        java.util.Collections.singletonMap("batchId", batchId)));

        // 收货拒签：qa 无确认权限 403
        long batchReject = shipNewBatch();
        java.util.Map<String, Object> rejectBody = new java.util.LinkedHashMap<>();
        rejectBody.put("batchId", batchReject);
        rejectBody.put("rejectBy", "张老师");
        rejectBody.put("reason", "整批破损拒收");
        assertEquals(HttpStatus.FORBIDDEN, postStatus("/batches/reject", "qa", rejectBody));
    }

    // ====================================================================
    // 6) 封签核验发现破损：核验确认即 SEAL_BROKEN 异常成立，L2 自动确认启钟
    // ====================================================================
    @Test
    void brokenSealVerification_autoConfirmsSealBrokenGradeL2() {
        long batchId = shipNewBatch();

        com.evops.coldchain.dto.ArriveWarehouseRequest arrive =
                new com.evops.coldchain.dto.ArriveWarehouseRequest();
        arrive.setBatchId(batchId);
        arrive.setWarehouseName("华东中转仓");
        arrive.setArrivedBoxQty(1);
        handoverService.arriveWarehouse(arrive);

        com.evops.coldchain.dto.HandoverCreateRequest ho =
                new com.evops.coldchain.dto.HandoverCreateRequest();
        ho.setBatchId(batchId);
        ho.setWarehouseName("华东中转仓");
        ho.setBoxQty(1);
        ho.setHandoverTemp(new java.math.BigDecimal("5.10"));
        ho.setReceiverName("张仓");
        ho.setReceiverOrg("华东中转仓收货组");
        ho.setSealNos(Collections.singletonList("SEAL-V6-0001"));
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) handoverService.createHandover(ho);
        Long handoverId = ((com.evops.coldchain.entity.HandoverOrder) created.get("order")).getId();

        Long sealId = handoverService.listSeals(handoverId).get(0).getId();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        com.evops.coldchain.dto.SealVerifyRequest verify =
                new com.evops.coldchain.dto.SealVerifyRequest();
        verify.setSealId(sealId);
        verify.setVerifiedBy("仓管员");
        verify.setVerifyNote("封签断裂，箱门有撬动痕迹");
        verify.setBroken(true);
        handoverService.verifySeal(verify);
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        ExceptionEvent broken = onlyOpenException(batchId);
        assertEquals(CcConstants.EXC_SEAL_BROKEN, broken.getExceptionType());
        assertEquals(CcConstants.EXC_CONFIRMED, broken.getStatus());
        assertEquals(CcConstants.GRADE_L2, broken.getGrade());
        assertEquals("AUTO", broken.getFoundSource());
        assertFalse(broken.getFoundTime().isBefore(before));
        assertFalse(broken.getFoundTime().isAfter(after));
        assertEquals(0, broken.getConfirmTime().compareTo(broken.getFoundTime()),
                "封签破损客观成立，时钟起点=核验时刻=首次发现时间");
        assertEquals(CcConstants.ROLE_RECEIVER, broken.getOwnerRoleSnap());
        assertEquals(CcConstants.ROLE_DISPATCHER, broken.getEscalateRoleSnap());
        assertEquals(CcConstants.ROLE_QA, broken.getReviewerRoleSnap());
        assertEquals(60, broken.getResponseMinutesSnap());
        assertEquals(240, broken.getDeadlineMinutesSnap());
        // 封签核验事实仍只进不退：封签保持 CONFIRMED 且说明留痕
        assertEquals(CcConstants.SEAL_CONFIRMED,
                handoverService.listSeals(handoverId).get(0).getVerifyStatus());
        assertTrue(handoverService.listSeals(handoverId).get(0).getVerifyNote().contains("封签破损"));
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/cold-chain" + path;
    }

    private HttpStatus postStatus(String path, String user, Object body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.http.HttpEntity<Object> entity = new org.springframework.http.HttpEntity<>(body, headers);
        return http.withBasicAuth(user, user + "123")
                .postForEntity(url(path), entity, String.class).getStatusCode();
    }

    // ---------------- 辅助 ----------------

    private void closeTempExceptionWithQa(long batchId) {
        ExceptionEvent exc = onlyOpenException(batchId);
        appendEvidence(exc.getId(), "curve.csv", "/v/curve", "温控曲线");
        Long evId = batchService.listEvidence(exc.getId()).get(0).getId();
        lifecycle.conclude(exc.getId(), "越限短暂且已回温，药品评估合格", Collections.singletonList(evId));
        testSubjects.runAs(6L, "qa", () -> lifecycle.review(exc.getId(), true, "QA 复核证据充分，同意关闭"));
    }

    private ExceptionEvent onlyOpenException(long batchId) {
        List<ExceptionEvent> rows = batchService.listExceptions(batchId);
        assertEquals(1, rows.size(), "预期恰好一条异常");
        return rows.get(0);
    }

    private ExceptionEvent markDelay(long batchId, LocalDateTime found, String desc) {
        ExceptionMarkRequest req = new ExceptionMarkRequest();
        req.setBatchId(batchId);
        req.setExceptionType(CcConstants.EXC_DELAY);
        req.setDescription(desc);
        req.setOccurredTime(found);
        return batchService.markException(req);
    }

    private void appendEvidence(Long exceptionId, String name, String url, String note) {
        EvidenceAppendRequest req = new EvidenceAppendRequest();
        req.setExceptionId(exceptionId);
        req.setFileName(name);
        req.setFileUrl(url);
        req.setNote(note);
        batchService.appendEvidence(req);
    }

    private void reportTemp(long batchId, LocalDateTime time, BigDecimal temp) {
        TempReportRequest req = new TempReportRequest();
        req.setBatchId(batchId);
        req.setRecordTime(time);
        req.setTemperature(temp);
        req.setRecordedBy("车载温控仪");
        batchService.reportTemperature(req);
    }

    private SignRequest signReq(long batchId) {
        TransportBatch b = batchService.requireBatch(batchId);
        SignRequest req = new SignRequest();
        req.setBatchId(batchId);
        req.setSignBy("院药库");
        req.setReceivedApprovalNo(b.getApprovalNoSnap());
        req.setReceivedPackageSpec(b.getPackageSpecSnap());
        req.setConfirmQty(b.getShipQty());
        return req;
    }

    private BatchSettleRequest settleReq(long batchId) {
        BatchSettleRequest req = new BatchSettleRequest();
        req.setBatchId(batchId);
        req.setSettleRemark("V6验收结算");
        return req;
    }

    private void changeRule(String type, String grade, String gradeName, int response, int deadline,
                            String owner, String escalate, String reviewer, String endpoint) {
        GradeRuleChangeRequest req = new GradeRuleChangeRequest();
        req.setExceptionType(type);
        req.setGrade(grade);
        req.setGradeName(gradeName);
        req.setResponseMinutes(response);
        req.setDeadlineMinutes(deadline);
        req.setOwnerRole(owner);
        req.setEscalateRole(escalate);
        req.setReviewerRole(reviewer);
        req.setNotifyEndpoint(endpoint);
        gradeService.changeRule(req);
    }

    private long shipNewBatch() {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V6验收药品-" + nano);
        dc.setApprovalNo("国药准字V6-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugCreate(dc);

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V6验收医院");
        oc.setShipQty(10);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测6·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName("V6验收物流");
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V6-" + nano);
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V6-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V6校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v6bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        ownedBatchIds.add(batchId);
        return batchId;
    }

    @Autowired private com.evops.coldchain.service.DrugService drugService;
    @Autowired private com.evops.coldchain.mapper.ExceptionEventMapper exceptionMapper;

    /** 本类各用例产生的批次，@AfterEach 只清理这些批次的遗留异常 */
    private final List<Long> ownedBatchIds = new ArrayList<>();

    private Long drugCreate(DrugCreateRequest dc) {
        return drugService.create(dc).getId();
    }
}
