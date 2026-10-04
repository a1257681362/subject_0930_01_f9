package com.evops.coldchain.controller;

import com.evops.coldchain.dto.PriceAdjustApplyRequest;
import com.evops.coldchain.dto.PriceAdjustReviewRequest;
import com.evops.coldchain.dto.PriceLockRequest;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.QuoteAdjustApproval;
import com.evops.coldchain.service.LockedPriceService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 锁价与人工改价审批（V8）。
 * 一个报价批次只能锁一个有效方案；锁价保存全要素快照；人工改价必须审批且原始报价保留；
 * 支持从锁价单/运输批次反查原报价单、处理人与异常原因。
 */
@RestController
@RequestMapping("/api/cold-chain/locked-prices")
public class LockedPriceController {

    private final LockedPriceService lockedPriceService;

    public LockedPriceController(LockedPriceService lockedPriceService) {
        this.lockedPriceService = lockedPriceService;
    }

    /** 比选后锁价（选定唯一中选方案，过期报价拒绝） */
    @PostMapping("/lock")
    public ApiResponse<LockedPrice> lock(@Valid @RequestBody PriceLockRequest req) {
        return ApiResponse.ok(lockedPriceService.lock(req));
    }

    /** 锁价单详情（快照 + 生效金额 + 附加费 + 审批链） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(lockedPriceService.detail(id));
    }

    /** 锁价单反查：原单、处理人、异常原因 */
    @GetMapping("/{id}/trace")
    public ApiResponse<Map<String, Object>> trace(@PathVariable("id") Long id) {
        return ApiResponse.ok(lockedPriceService.trace(id));
    }

    /** 从冷链药品运输锁价单（按运输批次）反查承运商报价 */
    @GetMapping("/by-batch/{batchId}")
    public ApiResponse<Map<String, Object>> traceByBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(lockedPriceService.traceByBatch(batchId));
    }

    /** 作废锁价单（快照保留；已被批次引用的拒绝） */
    @PostMapping("/{id}/void")
    public ApiResponse<LockedPrice> voidLocked(@PathVariable("id") Long id,
                                               @RequestBody Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.ok(lockedPriceService.voidLockedPrice(id, reason));
    }

    /** 发起人工改价申请（审批中原始报价保留） */
    @PostMapping("/adjustments")
    public ApiResponse<QuoteAdjustApproval> applyAdjust(@Valid @RequestBody PriceAdjustApplyRequest req) {
        return ApiResponse.ok(lockedPriceService.applyAdjust(req));
    }

    /** 改价审批同意（新金额生效） */
    @PostMapping("/adjustments/{id}/approve")
    public ApiResponse<QuoteAdjustApproval> approveAdjust(@PathVariable("id") Long id,
                                                          @Valid @RequestBody PriceAdjustReviewRequest req) {
        return ApiResponse.ok(lockedPriceService.approveAdjust(id, req.getReviewNote()));
    }

    /** 改价审批驳回（继续按原始报价执行） */
    @PostMapping("/adjustments/{id}/reject")
    public ApiResponse<QuoteAdjustApproval> rejectAdjust(@PathVariable("id") Long id,
                                                         @Valid @RequestBody PriceAdjustReviewRequest req) {
        return ApiResponse.ok(lockedPriceService.rejectAdjust(id, req.getReviewNote()));
    }

    /** 待审批改价申请列表 */
    @GetMapping("/adjustments/pending")
    public ApiResponse<List<QuoteAdjustApproval>> pendingAdjustments() {
        return ApiResponse.ok(lockedPriceService.listPendingAdjustments());
    }

    /** 锁价单列表（可按承运商过滤） */
    @GetMapping
    public ApiResponse<List<LockedPrice>> list(@RequestParam(required = false) Long carrierId) {
        return ApiResponse.ok(lockedPriceService.listByCarrier(carrierId));
    }
}
