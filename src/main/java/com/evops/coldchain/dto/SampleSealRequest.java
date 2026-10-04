package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/**
 * 样本封存请求：封存当刻生成唯一封存码，并固化采样人、采样位置、数量与封存照片。
 * 封存后样本与照片均不可修改、不可删除。
 */
public class SampleSealRequest {
    @NotNull(message = "抽检任务ID不能为空")
    private Long taskId;
    /** 抽样数量（必须 > 0） */
    @NotNull(message = "抽样数量不能为空")
    @Positive(message = "抽样数量必须大于0")
    private Integer quantity;
    /** 数量单位，默认“盒” */
    private String unit;
    /** 采样位置（不传取任务默认采样位置） */
    private String sampleLocation;
    /** 采样人（不传取任务默认采样人） */
    private String samplerName;
    @NotBlank(message = "封存照片名称不能为空")
    private String sealPhotoName;
    @NotBlank(message = "封存照片地址不能为空")
    private String sealPhotoUrl;
    /** 封存照片内容（演示环境可传文本用于计算 SHA-256 摘要；生产应由对象存储回算） */
    private String sealPhotoContent;
    /** 封存时初始保管责任主体 */
    private String custodianOrg;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getSampleLocation() { return sampleLocation; }
    public void setSampleLocation(String sampleLocation) { this.sampleLocation = sampleLocation; }
    public String getSamplerName() { return samplerName; }
    public void setSamplerName(String samplerName) { this.samplerName = samplerName; }
    public String getSealPhotoName() { return sealPhotoName; }
    public void setSealPhotoName(String sealPhotoName) { this.sealPhotoName = sealPhotoName; }
    public String getSealPhotoUrl() { return sealPhotoUrl; }
    public void setSealPhotoUrl(String sealPhotoUrl) { this.sealPhotoUrl = sealPhotoUrl; }
    public String getSealPhotoContent() { return sealPhotoContent; }
    public void setSealPhotoContent(String sealPhotoContent) { this.sealPhotoContent = sealPhotoContent; }
    public String getCustodianOrg() { return custodianOrg; }
    public void setCustodianOrg(String custodianOrg) { this.custodianOrg = custodianOrg; }
}
