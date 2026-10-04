package com.evops.coldchain.controller;

import com.evops.coldchain.dto.QuoteBatchOpenRequest;
import com.evops.coldchain.dto.QuoteSubmitRequest;
import com.evops.coldchain.entity.CarrierQuote;
import com.evops.coldchain.entity.QuoteBatch;
import com.evops.coldchain.service.QuoteService;
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
 * 承运商报价比选（V8）：发起报价批次 -> 多家承运商并行报价/截止前修订 ->
 * 过期失效 -> 比选视图。锁价见 {@link LockedPriceController}。
 */
@RestController
@RequestMapping("/api/cold-chain/quotes")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /** 发起报价批次（询价：路线/温区/箱型/服务等级/截止时间） */
    @PostMapping("/batches/open")
    public ApiResponse<QuoteBatch> openBatch(@Valid @RequestBody QuoteBatchOpenRequest req) {
        return ApiResponse.ok(quoteService.openBatch(req));
    }

    /** 人工提前截止收报价（已报方案仍可锁价，不能再提交/修订） */
    @PostMapping("/batches/{id}/close")
    public ApiResponse<QuoteBatch> closeBatch(@PathVariable("id") Long id) {
        return ApiResponse.ok(quoteService.closeBatch(id));
    }

    /** 手动触发一次过期扫描（到点 OPEN 批次置 EXPIRED） */
    @PostMapping("/expire-scan")
    public ApiResponse<Map<String, Object>> expireScan() {
        return ApiResponse.ok(quoteService.scanExpired());
    }

    /** 承运商并行提交报价（重复提交=修订，只追加新版本行） */
    @PostMapping("/submit")
    public ApiResponse<CarrierQuote> submit(@Valid @RequestBody QuoteSubmitRequest req) {
        return ApiResponse.ok(quoteService.submitQuote(req));
    }

    /** 报价批次列表（可按状态过滤） */
    @GetMapping("/batches")
    public ApiResponse<List<QuoteBatch>> batches(@RequestParam(required = false) String status) {
        return ApiResponse.ok(quoteService.listBatches(status));
    }

    /** 报价批次详情（比选视图 + 全部修订版本） */
    @GetMapping("/batches/{id}")
    public ApiResponse<Map<String, Object>> batchDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(quoteService.batchDetail(id));
    }

    /** 比选：当前有效方案按含税总价升序 */
    @GetMapping("/batches/{id}/compare")
    public ApiResponse<List<Map<String, Object>>> compare(@PathVariable("id") Long id) {
        return ApiResponse.ok(quoteService.compare(id));
    }
}
