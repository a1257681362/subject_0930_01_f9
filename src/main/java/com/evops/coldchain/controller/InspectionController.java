package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.InspectionConcludeRequest;
import com.evops.coldchain.dto.InspectionTaskCreateRequest;
import com.evops.coldchain.dto.SampleSealRequest;
import com.evops.coldchain.dto.TransferConfirmRequest;
import com.evops.coldchain.dto.TransferRequest;
import com.evops.coldchain.entity.InspectionSample;
import com.evops.coldchain.entity.InspectionTask;
import com.evops.coldchain.entity.SampleTransfer;
import com.evops.coldchain.service.InspectionService;
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
import javax.validation.Valid;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品运输抽检（V7）：
 * 立项抽检 -> 封存样本（唯一封存码+采样人/位置/数量/封存照片）-> 样本转移双人确认
 * -> 抽检结论回写批次（不覆盖原始运输记录）。
 * 查询/详情按监管区域隔离；导出 CSV 对承运人联系方式脱敏。
 * 已封存样本不提供任何修改/删除接口。
 */
@RestController
@RequestMapping("/api/cold-chain/inspections")
public class InspectionController {

    private final InspectionService inspectionService;
    private final OpLogService opLogService;

    public InspectionController(InspectionService inspectionService, OpLogService opLogService) {
        this.inspectionService = inspectionService;
        this.opLogService = opLogService;
    }

    /** 立项抽检任务（按批次，归属监管区域） */
    @PostMapping
    public ApiResponse<InspectionTask> create(@Valid @RequestBody InspectionTaskCreateRequest req) {
        return ApiResponse.ok(inspectionService.createTask(req));
    }

    /** 封存样本：生成唯一封存码，固化采样人/位置/数量/封存照片（封存后不可改删） */
    @PostMapping("/samples/seal")
    public ApiResponse<InspectionSample> seal(@Valid @RequestBody SampleSealRequest req) {
        return ApiResponse.ok(inspectionService.sealSample(req));
    }

    /** 样本移交发起（移交人确认，停留待接收人第二账号确认） */
    @PostMapping("/samples/transfers/request")
    public ApiResponse<SampleTransfer> requestTransfer(@Valid @RequestBody TransferRequest req) {
        return ApiResponse.ok(inspectionService.requestTransfer(req));
    }

    /** 样本移交接收确认（必须不同于移交人的第二账号，双人确认完成） */
    @PostMapping("/samples/transfers/{id}/confirm")
    public ApiResponse<SampleTransfer> confirmTransfer(@PathVariable("id") Long id,
                                                       @Valid @RequestBody TransferConfirmRequest req) {
        return ApiResponse.ok(inspectionService.confirmTransfer(id, req));
    }

    /** 出具抽检结论：回写批次独立抽检列，不覆盖原始运输记录；结论一次定稿 */
    @PostMapping("/{id}/conclude")
    public ApiResponse<InspectionTask> conclude(@PathVariable("id") Long taskId,
                                                @Valid @RequestBody InspectionConcludeRequest req) {
        req.setTaskId(taskId);
        return ApiResponse.ok(inspectionService.conclude(req));
    }

    /** 抽检任务列表（按当前账号监管区域隔离；可按区域/状态过滤） */
    @GetMapping
    public ApiResponse<List<InspectionTask>> list(@RequestParam(value = "region", required = false) String region,
                                                  @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(inspectionService.listTasks(region, status));
    }

    /** 抽检任务详情（任务 + 样本 + 各自双人转移链；区域隔离） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(inspectionService.detail(id));
    }

    /** 某运输批次的全部抽检任务（区域隔离） */
    @GetMapping("/batch/{batchId}")
    public ApiResponse<List<InspectionTask>> listByBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(inspectionService.listByBatch(batchId));
    }

    /**
     * 导出抽检样本 CSV：按监管区域隔离，承运人联系方式脱敏；
     * 逐次写一条导出审计流水，只导出不改写/删除任何抽检数据。
     */
    @GetMapping("/export")
    public void export(@RequestParam(value = "region", required = false) String region,
                       @RequestParam(value = "status", required = false) String status,
                       HttpServletResponse response) throws IOException {
        List<String[]> rows = inspectionService.exportRows(region, status);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"inspection_samples.csv\"");
        // UTF-8 BOM，便于 Excel 正确识别中文
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        writer.println(String.join(",", inspectionService.exportHeader()));
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    line.append(",");
                }
                line.append(csv(row[i]));
            }
            writer.println(line.toString());
        }
        writer.flush();

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("region", region);
        filter.put("status", status);
        filter.put("rows", rows.size());
        opLogService.log(CcConstants.ENT_INSPECTION, null, null, CcConstants.ACT_INSPECTION_EXPORT,
                "导出抽检样本 CSV（按监管区域隔离，承运人联系方式脱敏）：" + filter
                        + "，共 " + rows.size() + " 条；只读导出，不改写/删除抽检数据");
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
