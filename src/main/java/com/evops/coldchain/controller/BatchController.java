package com.evops.coldchain.controller;

import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.BatchRejectRequest;
import com.evops.coldchain.dto.BatchSettleRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.ExceptionResolveRequest;
import com.evops.coldchain.dto.RevokeRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.service.LedgerService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 运输批次全链路：派车 -> 发运 -> 温度留痕 -> 异常标记/追加凭证/处理 -> 签收闭环 / 撤回。
 */
@RestController
@RequestMapping("/api/cold-chain/batches")
public class BatchController {

    private final TransportBatchService batchService;
    private final LedgerService ledgerService;

    public BatchController(TransportBatchService batchService, LedgerService ledgerService) {
        this.batchService = batchService;
        this.ledgerService = ledgerService;
    }

    /** 派车：生成批次号 + 固化档案快照 */
    @PostMapping("/dispatch")
    public ApiResponse<TransportBatch> dispatch(@Valid @RequestBody DispatchRequest req) {
        return ApiResponse.ok(batchService.dispatch(req));
    }

    /** 发运 */
    @PostMapping("/{id}/ship")
    public ApiResponse<TransportBatch> ship(@PathVariable("id") Long id) {
        return ApiResponse.ok(batchService.ship(id));
    }

    /** 途中温度上报（按时间留痕，越限自动追加异常） */
    @PostMapping("/temperatures")
    public ApiResponse<TemperatureLog> reportTemperature(@Valid @RequestBody TempReportRequest req) {
        return ApiResponse.ok(batchService.reportTemperature(req));
    }

    /** 人工异常标记（追加） */
    @PostMapping("/exceptions")
    public ApiResponse<ExceptionEvent> markException(@Valid @RequestBody ExceptionMarkRequest req) {
        return ApiResponse.ok(batchService.markException(req));
    }

    /** 追加异常凭证（不覆盖已有凭证） */
    @PostMapping("/exceptions/evidence")
    public ApiResponse<ExceptionEvidence> appendEvidence(@Valid @RequestBody EvidenceAppendRequest req) {
        return ApiResponse.ok(batchService.appendEvidence(req));
    }

    /** 异常处理闭环（只追加处理说明） */
    @PostMapping("/exceptions/{id}/resolve")
    public ApiResponse<ExceptionEvent> resolveException(@PathVariable("id") Long id,
                                                        @Valid @RequestBody ExceptionResolveRequest req) {
        return ApiResponse.ok(batchService.resolveException(id, req.getResolveNote()));
    }

    /** 签收闭环：按批次快照（原档案版本）核对 */
    @PostMapping("/sign")
    public ApiResponse<TransportBatch> sign(@Valid @RequestBody SignRequest req) {
        return ApiResponse.ok(batchService.sign(req));
    }

    /** 收货拒签（V6）：登记即按收货拒签分级确认启钟，未关闭前不得签收/结算 */
    @PostMapping("/reject")
    public ApiResponse<ExceptionEvent> reject(@Valid @RequestBody BatchRejectRequest req) {
        return ApiResponse.ok(batchService.reject(req));
    }

    /** 批次结算（V6）：已签收且无未关闭异常才放行 */
    @PostMapping("/settle")
    public ApiResponse<TransportBatch> settle(@Valid @RequestBody BatchSettleRequest req) {
        return ApiResponse.ok(batchService.settle(req));
    }

    /** 撤回：作废标记，不删除原始记录 */
    @PostMapping("/revoke")
    public ApiResponse<TransportBatch> revoke(@Valid @RequestBody RevokeRequest req) {
        return ApiResponse.ok(batchService.revoke(req));
    }

    /** 批次完整台账 */
    @GetMapping("/{id}/ledger")
    public ApiResponse<Map<String, Object>> ledger(@PathVariable("id") Long id) {
        return ApiResponse.ok(ledgerService.batchLedger(id));
    }

    /** 批次温度留痕 */
    @GetMapping("/{id}/temperatures")
    public ApiResponse<List<TemperatureLog>> temperatures(@PathVariable("id") Long id) {
        return ApiResponse.ok(batchService.listTemperatures(id));
    }

    /** 批次异常列表 */
    @GetMapping("/{id}/exceptions")
    public ApiResponse<List<ExceptionEvent>> exceptions(@PathVariable("id") Long id) {
        return ApiResponse.ok(batchService.listExceptions(id));
    }
}
