package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.service.AlertEvaluationService;
import com.evops.coldchain.service.AlertScanService;
import com.evops.coldchain.service.OpLogService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 温控预警查看 / 闭环 / 导出 / 手动扫描（V5）。
 * 全部经过 Shiro 权限点校验（见 ShiroConfig）：
 * 查看 coldchain:alert:view、导出 coldchain:alert:export、闭环 coldchain:alert:resolve、
 * 手动扫描 coldchain:alert:scan。预警只追加，查看/导出绝不改写、删除预警。
 */
@RestController
@RequestMapping("/api/cold-chain/alerts")
public class TempAlertController {

    private final TempAlertMapper alertMapper;
    private final AlertEvaluationService evaluationService;
    private final AlertScanService scanService;
    private final OpLogService opLogService;

    public TempAlertController(TempAlertMapper alertMapper,
                               AlertEvaluationService evaluationService,
                               AlertScanService scanService,
                               OpLogService opLogService) {
        this.alertMapper = alertMapper;
        this.evaluationService = evaluationService;
        this.scanService = scanService;
        this.opLogService = opLogService;
    }

    /** 预警列表（可按批次/状态过滤），时间升序便于审计核对 */
    @GetMapping
    public ApiResponse<List<TempAlert>> list(@RequestParam(value = "batchId", required = false) Long batchId,
                                             @RequestParam(value = "status", required = false) String status) {
        QueryWrapper<TempAlert> qw = new QueryWrapper<TempAlert>()
                .orderByAsc("trigger_time").orderByAsc("id");
        if (batchId != null) {
            qw.eq("batch_id", batchId);
        }
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        return ApiResponse.ok(alertMapper.selectList(qw));
    }

    /** 预警详情（查看权限守卫；只读） */
    @GetMapping("/{id}")
    public ApiResponse<TempAlert> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(requireAlert(id));
    }

    /** 预警处理闭环（只追加 resolve*；不改原描述、不删除、不因模板变更消失） */
    @PostMapping("/{id}/resolve")
    public ApiResponse<TempAlert> resolve(@PathVariable("id") Long id,
                                          @RequestBody Map<String, String> body) {
        String note = body == null ? null : body.get("resolveNote");
        return ApiResponse.ok(evaluationService.resolveAlert(id, note));
    }

    /**
     * 导出预警 CSV（需 coldchain:alert:export）。只读导出，逐次写一条导出审计流水；
     * 不对预警做任何修改/删除。
     */
    @GetMapping("/export")
    public void export(@RequestParam(value = "batchId", required = false) Long batchId,
                       @RequestParam(value = "status", required = false) String status,
                       HttpServletResponse response) throws IOException {
        QueryWrapper<TempAlert> qw = new QueryWrapper<TempAlert>()
                .orderByAsc("trigger_time").orderByAsc("id");
        if (batchId != null) {
            qw.eq("batch_id", batchId);
        }
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        List<TempAlert> alerts = alertMapper.selectList(qw);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"temp_alerts.csv\"");
        // UTF-8 BOM，便于 Excel 正确识别中文；输出流显式按 UTF-8 编码
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        writer.println("预警ID,批次ID,采样探头ID,温区代码,温区名称,阈值下限,阈值上限,容忍分钟,抖动合并窗分钟,"
                + "连续超限起点,预警触发时间,最近越限时间,持续分钟,峰值温度,阈值来源类别,状态,处理说明,处理时间");
        for (TempAlert a : alerts) {
            writer.println(String.join(",",
                    csv(a.getId()), csv(a.getBatchId()), csv(a.getProbeId()), csv(a.getZoneCode()),
                    csv(a.getZoneName()), csv(a.getTempMinSnap()), csv(a.getTempMaxSnap()),
                    csv(a.getTolerateMinutes()), csv(a.getJitterGapMinutes()),
                    csv(a.getStartTime()), csv(a.getTriggerTime()), csv(a.getLastExceedTime()),
                    csv(a.getDurationMinutes()), csv(a.getPeakTemperature()),
                    csv(a.getSourceCategoryNo()), csv(a.getStatus()),
                    csv(a.getResolveNote()), csv(a.getResolveTime())));
        }
        writer.flush();

        // 导出审计（操作者由 Shiro 主体解析，见 OpLogService/UserContext）
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("batchId", batchId);
        filter.put("status", status);
        filter.put("rows", alerts.size());
        opLogService.log(CcConstants.ENT_TEMP_ALERT, null, batchId, CcConstants.ACT_ALERT_EXPORT,
                "导出温控预警 CSV：" + filter + "，共 " + alerts.size() + " 条（只读导出，不改写/删除预警）");
    }

    /** 手动触发一次定时扫描（运维入口，需 coldchain:alert:scan；与定时任务同一幂等逻辑） */
    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan() {
        return ApiResponse.ok(scanService.scan());
    }

    private TempAlert requireAlert(Long id) {
        TempAlert alert = alertMapper.selectById(id);
        if (alert == null) {
            throw new com.evops.common.BusinessException("温控预警不存在: " + id);
        }
        return alert;
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
