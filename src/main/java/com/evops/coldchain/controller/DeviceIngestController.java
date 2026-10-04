package com.evops.coldchain.controller;

import com.evops.coldchain.dto.DeviceCertRegisterRequest;
import com.evops.coldchain.dto.DeviceIngestRequest;
import com.evops.coldchain.dto.DeviceReplayRequest;
import com.evops.coldchain.entity.DeviceCert;
import com.evops.coldchain.entity.DeviceIngestBatch;
import com.evops.coldchain.entity.DeviceQuarantine;
import com.evops.coldchain.entity.DeviceReplayLog;
import com.evops.coldchain.service.DeviceCertService;
import com.evops.coldchain.service.DeviceIngestService;
import com.evops.coldchain.service.DeviceReplayService;
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
 * 车载设备温度数据接入（V9）。
 * 设备证书登记、批量温度上报（签名验证+去重）、隔离队列查看、重放。
 */
@RestController
@RequestMapping("/api/cold-chain/device")
public class DeviceIngestController {

    private final DeviceCertService certService;
    private final DeviceIngestService ingestService;
    private final DeviceReplayService replayService;

    public DeviceIngestController(DeviceCertService certService,
                                  DeviceIngestService ingestService,
                                  DeviceReplayService replayService) {
        this.certService = certService;
        this.ingestService = ingestService;
        this.replayService = replayService;
    }

    // ---------------- 设备证书 ----------------

    /** 设备证书登记 */
    @PostMapping("/certs")
    public ApiResponse<DeviceCert> registerCert(@Valid @RequestBody DeviceCertRegisterRequest req) {
        return ApiResponse.ok(certService.register(req));
    }

    /** 设备证书列表 */
    @GetMapping("/certs")
    public ApiResponse<List<DeviceCert>> listCerts() {
        return ApiResponse.ok(certService.listCerts());
    }

    // ---------------- 温度数据上报 ----------------

    /** 设备温度批量上报（签名验证 + 去重 + 补传保护） */
    @PostMapping("/temperatures")
    public ApiResponse<Map<String, Object>> ingestTemperatures(@Valid @RequestBody DeviceIngestRequest req) {
        return ApiResponse.ok(ingestService.ingest(req));
    }

    // ---------------- 数据批次 ----------------

    /** 数据批次列表 */
    @GetMapping("/ingest-batches")
    public ApiResponse<List<DeviceIngestBatch>> listIngestBatches(
            @RequestParam(value = "batchId", required = false) Long batchId) {
        return ApiResponse.ok(ingestService.listIngestBatches(batchId));
    }

    /** 数据批次详情 */
    @GetMapping("/ingest-batches/{id}")
    public ApiResponse<DeviceIngestBatch> ingestBatchDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(ingestService.requireIngestBatch(id));
    }

    // ---------------- 隔离队列 ----------------

    /** 隔离队列列表 */
    @GetMapping("/quarantine")
    public ApiResponse<List<DeviceQuarantine>> listQuarantine(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(ingestService.listQuarantine(status));
    }

    /** 隔离队列详情 */
    @GetMapping("/quarantine/{id}")
    public ApiResponse<DeviceQuarantine> quarantineDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(ingestService.requireQuarantine(id));
    }

    /** 重放隔离数据（仅限未入账 PENDING 状态） */
    @PostMapping("/quarantine/{id}/replay")
    public ApiResponse<Map<String, Object>> replay(@PathVariable("id") Long id,
                                                   @Valid @RequestBody DeviceReplayRequest req) {
        return ApiResponse.ok(replayService.replay(id, req));
    }

    /** 重放审计记录 */
    @GetMapping("/quarantine/{id}/replay-logs")
    public ApiResponse<List<DeviceReplayLog>> replayLogs(@PathVariable("id") Long id) {
        return ApiResponse.ok(replayService.listReplayLogs(id));
    }
}
