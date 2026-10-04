package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 抽检任务立项请求：监管人员按运输批次发起抽检。
 * superviseRegion 不传时取批次已固化区域，或当前账号唯一授权区域；
 * 必须落在当前监管账号被授权的监管区域内。
 */
public class InspectionTaskCreateRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** 监管区域（不传取批次区域/账号唯一授权区域） */
    private String superviseRegion;
    @NotBlank(message = "抽检任务名称/事由不能为空")
    private String taskName;
    /** 默认采样位置（封存样本时可逐条细化） */
    private String samplingLocation;
    /** 默认采样人（封存样本时可逐条细化） */
    private String samplerName;
    private String remark;

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
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
