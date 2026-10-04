package com.evops.coldchain.controller;

import com.evops.coldchain.dto.DeviceManualIngestRequest;
import com.evops.coldchain.dto.DeviceTempIngestRequest;
import com.evops.coldchain.entity.DeviceReplayAudit;
import com.evops.coldchain.entity.TempBatchQuarantine;
import com.evops.coldchain.service.DeviceTempIngestService;
import com.evops.coldchain.service.DeviceTempIngestService.IngestResult;
import com.evops.coldchain.service.DeviceTempIngestService.ReplayOutcome;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车载设备温度数据接入（V9）。
 *
 * - POST /device-temperatures：设备侧签名上报入口（免登录，报文自带签名+证书），
 *   验签失败整批入隔离队列，响应回带可读原因码/说明与隔离单号；
 * - POST /device-temperatures/manual：人工补录（coldchain:device:manual），同一套去重/对账；
 * - GET  /quarantine、/quarantine/{id}：隔离队列查看（coldchain:device:view）；
 * - POST /quarantine/{id}/replay：仅可重放未入账数据（coldchain:device:replay），全程审计。
 */
@RestController
@RequestMapping("/api/cold-chain")
public class DeviceTempController {

    private final DeviceTempIngestService ingestService;

    public DeviceTempController(DeviceTempIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /** 设备签名温度上报（断网批量补传同入口）；免登录，身份=设备证书 */
    @PostMapping("/device-temperatures")
    public ApiResponse<IngestResult> receive(@Valid @RequestBody DeviceTempIngestRequest req) {
        IngestResult result = ingestService.receiveSigned(req);
        if (result.isQuarantined()) {
            return ApiResponse.status(false,
                    "报文验签失败，已整批进入隔离队列 " + result.getQuarantineNo()
                            + "；原因码=" + result.getReasonCode() + "；" + result.getReasonDetail(),
                    result);
        }
        String message = String.format(
                "设备温度批次 %s 处理完成：测点 %d，新入账 %d，去重 %d，不可归属 %d%s",
                result.getClientBatchNo(), result.getPointCount(),
                result.getInsertedCount(), result.getDuplicateCount(), result.getUnmatchedCount(),
                result.isReplay() ? "（同批次重复提交，回第一次结果 replay=true）" : "");
        return ApiResponse.status(true, message, result);
    }

    /** 人工补录设备温度（无签名，需补录权限；与设备上报同一套设备序列+采样时间去重） */
    @PostMapping("/device-temperatures/manual")
    public ApiResponse<IngestResult> manual(@Valid @RequestBody DeviceManualIngestRequest req) {
        IngestResult result = ingestService.receiveManual(req);
        String message = String.format(
                "人工补录批次 %s 处理完成：测点 %d，新入账 %d，去重 %d，不可归属 %d",
                result.getClientBatchNo(), result.getPointCount(),
                result.getInsertedCount(), result.getDuplicateCount(), result.getUnmatchedCount());
        return ApiResponse.status(true, message, result);
    }

    /** 隔离队列列表（可按状态过滤：QUARANTINED 未入账 / RELEASED 已释放） */
    @GetMapping("/quarantine")
    public ApiResponse<List<TempBatchQuarantine>> list(@RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(ingestService.listQuarantine(status));
    }

    /** 隔离单详情（含全部重放审计行） */
    @GetMapping("/quarantine/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        TempBatchQuarantine q = ingestService.requireQuarantine(id);
        List<DeviceReplayAudit> audits = ingestService.listReplayAudits(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quarantine", q);
        data.put("replayAudits", audits);
        return ApiResponse.ok(data);
    }

    /** 重放未入账隔离数据：成功释放、失败留痕、已入账拒绝，均写审计 */
    @PostMapping("/quarantine/{id}/replay")
    public ApiResponse<ReplayOutcome> replay(@PathVariable("id") Long id) {
        return ApiResponse.ok(ingestService.replay(id));
    }
}
