package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 药品运输抽检任务（V7）。
 * 监管人员按运输批次发起，任务归属监管区域（superviseRegion），查询按区域隔离。
 * 状态 PLANNED（立项封存中）-> CONCLUDED（已出结论）；任务不提供删除路径。
 * 结论回写运输批次的独立抽检列，不覆盖任何原始运输记录。
 */
@TableName("t_cc_inspection_task")
public class InspectionTask extends BaseEntity {
    private String taskNo;
    private Long batchId;
    private String superviseRegion;
    private String taskName;
    private String samplingLocation;
    private String samplerName;
    /** PLANNED / CONCLUDED */
    private String status;
    /** 抽检结论：PASS 合格 / FAIL 不合格（结论只追加一次） */
    private String result;
    private String conclusion;
    private Long concludeBy;
    private String concludeName;
    private LocalDateTime concludeTime;
    private String remark;

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getSamplingLocation() { return samplingLocation; }
    public void setSamplingLocation(String samplingLocation) { this.samplingLocation = samplingLocation; }
    public String getSamplerName() { return samplerName; }
    public void setSamplerName(String samplerName) { this.samplerName = samplerName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public Long getConcludeBy() { return concludeBy; }
    public void setConcludeBy(Long concludeBy) { this.concludeBy = concludeBy; }
    public String getConcludeName() { return concludeName; }
    public void setConcludeName(String concludeName) { this.concludeName = concludeName; }
    public LocalDateTime getConcludeTime() { return concludeTime; }
    public void setConcludeTime(LocalDateTime concludeTime) { this.concludeTime = concludeTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
