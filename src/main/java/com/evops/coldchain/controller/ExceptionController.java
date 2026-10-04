package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ExceptionConcludeRequest;
import com.evops.coldchain.dto.ExceptionReviewRequest;
import com.evops.coldchain.dto.GradeRuleChangeRequest;
import com.evops.coldchain.dto.NotifyRetryRequest;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionGradeRule;
import com.evops.coldchain.entity.ExceptionNotifyLog;
import com.evops.coldchain.entity.ExceptionReviewLog;
import com.evops.coldchain.service.ExceptionGradeService;
import com.evops.coldchain.service.ExceptionLifecycleService;
import com.evops.coldchain.service.ExceptionNotifyService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运输异常分级与处置时钟（V6）。
 *
 * 路径总览（Shiro 权限点见 ShiroConfig）：
 * - 分级规则查看/调整：/exceptions/grade-rules
 * - 确认启钟：        /exceptions/{id}/confirm        （coldchain:exception:confirm）
 * - 处置结论：        /exceptions/{id}/conclude       （coldchain:exception:conclude，必须引用证据）
 * - 关闭前复核：      /exceptions/{id}/review         （coldchain:exception:review + 指定角色）
 * - 通知手动重试：    /exceptions/{id}/notify-retry   （coldchain:exception:notify:retry）
 * - 处置时钟手动扫描：/exceptions/clock/scan          （分级规则管理权限）
 */
@RestController
@RequestMapping("/api/cold-chain/exceptions")
public class ExceptionController {

    private final ExceptionGradeService gradeService;
    private final ExceptionLifecycleService lifecycleService;
    private final ExceptionNotifyService notifyService;

    public ExceptionController(ExceptionGradeService gradeService,
                               ExceptionLifecycleService lifecycleService,
                               ExceptionNotifyService notifyService) {
        this.gradeService = gradeService;
        this.lifecycleService = lifecycleService;
        this.notifyService = notifyService;
    }

    /** 分级规则列表（四类异常各一行） */
    @GetMapping("/grade-rules")
    public ApiResponse<List<ExceptionGradeRule>> rules() {
        return ApiResponse.ok(gradeService.listRules());
    }

    /** 调整分级规则（只影响此后确认的异常） */
    @PostMapping("/grade-rules")
    public ApiResponse<ExceptionGradeRule> changeRule(@Valid @RequestBody GradeRuleChangeRequest req) {
        return ApiResponse.ok(gradeService.changeRule(req));
    }

    /** 确认人工补录的异常：定级快照 + 启动处置时钟 + 首响通知处置岗位 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<ExceptionEvent> confirm(@PathVariable("id") Long id) {
        return ApiResponse.ok(lifecycleService.confirm(id));
    }

    /** 提交处置结论（必须引用证据），提交后进入待复核 */
    @PostMapping("/{id}/conclude")
    public ApiResponse<ExceptionEvent> conclude(@PathVariable("id") Long id,
                                                @Valid @RequestBody ExceptionConcludeRequest req) {
        return ApiResponse.ok(lifecycleService.conclude(id, req.getConclusion(), req.getEvidenceIds()));
    }

    /** 指定复核岗位复核：通过关闭 / 驳回重处 */
    @PostMapping("/{id}/review")
    public ApiResponse<ExceptionEvent> review(@PathVariable("id") Long id,
                                              @Valid @RequestBody ExceptionReviewRequest req) {
        return ApiResponse.ok(lifecycleService.review(id, req.getApproved(), req.getReviewNote()));
    }

    /** 升级/响应通知手动重试（必须填重试原因） */
    @PostMapping("/{id}/notify-retry")
    public ApiResponse<ExceptionNotifyLog> retryNotify(@PathVariable("id") Long id,
                                                       @Valid @RequestBody NotifyRetryRequest req) {
        return ApiResponse.ok(notifyService.retry(id, req.getRetryReason()));
    }

    /** 异常处置详情：异常本体 + 通知发送记录 + 复核记录 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        ExceptionEvent event = lifecycleService.requireEvent(id);
        List<ExceptionNotifyLog> notifyLogs = lifecycleService.listNotifications(id);
        List<ExceptionReviewLog> reviewLogs = lifecycleService.listReviews(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exception", event);
        data.put("notifyLogs", notifyLogs);
        data.put("reviewLogs", reviewLogs);
        data.put("closed", com.evops.coldchain.CcConstants.EXC_RESOLVED.equals(event.getStatus()));
        return ApiResponse.ok(data);
    }

    /** 手动触发一次处置时钟扫描（逾期升级 + 失败通知自动重试，运维入口） */
    @PostMapping("/clock/scan")
    public ApiResponse<Map<String, Object>> scanClock() {
        return ApiResponse.ok(lifecycleService.scanClock(LocalDateTime.now()));
    }
}
