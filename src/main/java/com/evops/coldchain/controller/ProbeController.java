package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCertUploadRequest;
import com.evops.coldchain.dto.ProbeCertVoidRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.entity.ProbeCert;
import com.evops.coldchain.entity.TempProbe;
import com.evops.coldchain.entity.VehicleProbe;
import com.evops.coldchain.service.ProbeService;
import com.evops.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车载温度探头与校准凭证：
 * 建档 -> 证书上传（生成 SHA-256 摘要） -> 车辆关联（requestId 幂等） ->
 * 证书下载/作废（写审计流水） -> 发运前有效期卡口（见批次发运接口）。
 */
@RestController
@RequestMapping("/api/cold-chain/probes")
public class ProbeController {

    private final ProbeService probeService;

    public ProbeController(ProbeService probeService) {
        this.probeService = probeService;
    }

    /** 探头建档（序列号、量程、校准机构、有效期；可选随附首份凭证） */
    @PostMapping
    public ApiResponse<TempProbe> create(@Valid @RequestBody ProbeCreateRequest req) {
        return ApiResponse.ok(probeService.createProbe(req));
    }

    /** 探头列表 */
    @GetMapping
    public ApiResponse<List<TempProbe>> list() {
        return ApiResponse.ok(probeService.listProbes());
    }

    /** 探头详情（含当前凭证与有效期状态） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        TempProbe probe = probeService.requireProbe(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("probe", probe);
        data.put("certs", probeService.listCerts(id));
        return ApiResponse.ok(data);
    }

    /** 校准历史（只追加，含已作废凭证） */
    @GetMapping("/{id}/certs")
    public ApiResponse<List<ProbeCert>> certs(@PathVariable("id") Long id) {
        return ApiResponse.ok(probeService.listCerts(id));
    }

    /** 上传新校准凭证（服务端生成 SHA-256 摘要并切换为当前凭证） */
    @PostMapping("/certs/upload")
    public ApiResponse<ProbeCert> uploadCert(@Valid @RequestBody ProbeCertUploadRequest req) {
        return ApiResponse.ok(probeService.uploadCert(req));
    }

    /**
     * 下载证书：以附件流返回文件原文，同时写下载审计流水（含摘要）。
     * 已作废凭证仍可下载追溯，同样留痕。
     */
    @GetMapping("/certs/{certId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("certId") Long certId) {
        ProbeCert cert = probeService.downloadCert(certId);
        byte[] body = cert.getFileContent() == null
                ? new byte[0] : cert.getFileContent().getBytes(StandardCharsets.UTF_8);
        String encodedName;
        try {
            encodedName = URLEncoder.encode(cert.getFileName(), StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
        headers.add("X-Cert-No", cert.getCertNo());
        headers.add("X-Cert-SHA256", cert.getSha256());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** 作废凭证（不删除文件/原行，写审计流水） */
    @PostMapping("/certs/void")
    public ApiResponse<ProbeCert> voidCert(@Valid @RequestBody ProbeCertVoidRequest req) {
        return ApiResponse.ok(probeService.voidCert(req.getCertId(), req.getReason()));
    }

    /**
     * 车载探头校准凭证关联（绑定/替换探头），按 requestId 幂等：
     * 同一 requestId 重复请求返回第一次的绑定结果，不产生第二条绑定、不改变替换链。
     */
    @PostMapping("/bind")
    public ApiResponse<Map<String, Object>> bind(@Valid @RequestBody ProbeBindRequest req) {
        ProbeService.ProbeBindOutcome outcome = probeService.bindIdempotent(req);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bind", outcome.getBind());
        data.put("previous", outcome.getPrevious());
        data.put("replay", outcome.isReplay());
        data.put("firstTime", !outcome.isReplay());
        return ApiResponse.ok(data);
    }

    /** 车辆当前绑定 */
    @GetMapping("/vehicles/{vehicleId}/current")
    public ApiResponse<VehicleProbe> currentBinding(@PathVariable("vehicleId") Long vehicleId) {
        return ApiResponse.ok(probeService.currentBinding(vehicleId));
    }

    /** 车辆绑定/替换历史（旧绑定保留，前后关系经 replacedByBindId 串联） */
    @GetMapping("/vehicles/{vehicleId}/history")
    public ApiResponse<List<VehicleProbe>> history(@PathVariable("vehicleId") Long vehicleId) {
        return ApiResponse.ok(probeService.bindHistory(vehicleId));
    }
}
