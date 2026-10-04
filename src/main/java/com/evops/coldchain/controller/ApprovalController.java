package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ApprovalApplyRequest;
import com.evops.coldchain.dto.ApprovalReviewRequest;
import com.evops.coldchain.entity.InspectionApproval;
import com.evops.coldchain.service.ApprovalService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 冷链药品运输审批（V7，两步）：申请（PENDING 待处理）-> 同意/驳回。
 * 只完成一半时审批停留待处理、批次不得发运；审批期间抽检与样本封存照常可办、可追溯。
 */
@RestController
@RequestMapping("/api/cold-chain/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /** 第一步：发起运输审批申请（停留待处理） */
    @PostMapping
    public ApiResponse<InspectionApproval> apply(@Valid @RequestBody ApprovalApplyRequest req) {
        return ApiResponse.ok(approvalService.apply(req));
    }

    /** 第二步：同意审批（必须留意见），同意后批次方可发运 */
    @PostMapping("/{id}/approve")
    public ApiResponse<InspectionApproval> approve(@PathVariable("id") Long id,
                                                   @Valid @RequestBody ApprovalReviewRequest req) {
        return ApiResponse.ok(approvalService.approve(id, req.getReviewNote()));
    }

    /** 第二步：驳回审批（必须留意见），停留驳回可重新申请 */
    @PostMapping("/{id}/reject")
    public ApiResponse<InspectionApproval> reject(@PathVariable("id") Long id,
                                                  @Valid @RequestBody ApprovalReviewRequest req) {
        return ApiResponse.ok(approvalService.reject(id, req.getReviewNote()));
    }

    /** 审批单详情（区域隔离） */
    @GetMapping("/{id}")
    public ApiResponse<InspectionApproval> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(approvalService.requireApproval(id));
    }

    /** 某批次的审批链（按时间顺序） */
    @GetMapping("/batch/{batchId}")
    public ApiResponse<List<InspectionApproval>> listByBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(approvalService.listByBatch(batchId));
    }

    /** 待处理审批列表（只完成一半的申请） */
    @GetMapping("/pending")
    public ApiResponse<List<InspectionApproval>> pending() {
        return ApiResponse.ok(approvalService.listPending());
    }
}
