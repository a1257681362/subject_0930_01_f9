package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ArriveWarehouseRequest;
import com.evops.coldchain.dto.HandoverCreateRequest;
import com.evops.coldchain.dto.HandoverVoidRequest;
import com.evops.coldchain.dto.SealVerifyRequest;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSeal;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.service.HandoverService;
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
 * 冷链批次分仓交接：
 * 到仓登记 -> 拆成多个交接单（箱数/封签/交接温度/接收人） -> 逐封签核验 ->
 * 提交（责任主体随单转移） -> 全部核验平衡后批次方可继续派送/签收。
 * 冷链运输权限被收紧时，以上推进动作全部停止，已确认封签事实保留。
 */
@RestController
@RequestMapping("/api/cold-chain/handovers")
public class HandoverController {

    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    /** 一车货到达中转仓：登记到仓总箱数（箱数平衡基准） */
    @PostMapping("/arrive")
    public ApiResponse<TransportBatch> arrive(@Valid @RequestBody ArriveWarehouseRequest req) {
        return ApiResponse.ok(handoverService.arriveWarehouse(req));
    }

    /** 拆分交接单（一车货可连续拆多个单） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody HandoverCreateRequest req) {
        return ApiResponse.ok(handoverService.createHandover(req));
    }

    /** 逐封签核验（PENDING -> CONFIRMED，已确认事实不可改写） */
    @PostMapping("/seals/verify")
    public ApiResponse<HandoverSeal> verifySeal(@Valid @RequestBody SealVerifyRequest req) {
        return ApiResponse.ok(handoverService.verifySeal(req));
    }

    /** 提交交接单：全部封签核验 + 全批箱数平衡，责任主体随单转移 */
    @PostMapping("/{id}/submit")
    public ApiResponse<HandoverOrder> submit(@PathVariable("id") Long id) {
        return ApiResponse.ok(handoverService.submit(id));
    }

    /** 作废草稿交接单（仅置状态，封签/核验/日志保留，释放箱数占用） */
    @PostMapping("/void")
    public ApiResponse<HandoverOrder> voidHandover(@Valid @RequestBody HandoverVoidRequest req) {
        return ApiResponse.ok(handoverService.voidHandover(req.getHandoverId(), req.getReason()));
    }

    /** 交接单详情（含封签核验明细） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(handoverService.detail(id));
    }

    /** 某批次的全部交接单（原运输批次可追溯全部分仓节点） */
    @GetMapping("/batch/{batchId}")
    public ApiResponse<List<HandoverOrder>> listByBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(handoverService.listByBatch(batchId));
    }

    /** 批次分仓交接进度（箱数平衡/封签核验/是否可继续派送） */
    @GetMapping("/batch/{batchId}/progress")
    public ApiResponse<Map<String, Object>> progress(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(handoverService.progress(batchId));
    }
}
