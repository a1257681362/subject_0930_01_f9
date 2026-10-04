package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 抽检样本（V7）。
 * 封存当刻生成库内唯一、永不复用的封存码 sealCode，并一并固化采样人、采样位置、
 * 数量与封存照片（名称/地址/摘要）。封存后状态只沿 SEALED -> TRANSFERRED 前进，
 * 当前持有方随双人转移更新；照片与样本行均不可更新、不可删除。
 */
@TableName("t_cc_inspection_sample")
public class InspectionSample extends BaseEntity {
    private Long taskId;
    private Long batchId;
    private Integer sampleSeq;
    private String sealCode;
    private Integer quantity;
    private String unit;
    private String sampleLocation;
    private String samplerName;
    private String sealPhotoName;
    private String sealPhotoUrl;
    private String sealPhotoSha256;
    /** SEALED / TRANSFERRED */
    private String status;
    private String custodianName;
    private String custodianOrg;
    private Integer transferCount;
    private LocalDateTime sealTime;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getSampleSeq() { return sampleSeq; }
    public void setSampleSeq(Integer sampleSeq) { this.sampleSeq = sampleSeq; }
    public String getSealCode() { return sealCode; }
    public void setSealCode(String sealCode) { this.sealCode = sealCode; }
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
    public String getSealPhotoSha256() { return sealPhotoSha256; }
    public void setSealPhotoSha256(String sealPhotoSha256) { this.sealPhotoSha256 = sealPhotoSha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCustodianName() { return custodianName; }
    public void setCustodianName(String custodianName) { this.custodianName = custodianName; }
    public String getCustodianOrg() { return custodianOrg; }
    public void setCustodianOrg(String custodianOrg) { this.custodianOrg = custodianOrg; }
    public Integer getTransferCount() { return transferCount; }
    public void setTransferCount(Integer transferCount) { this.transferCount = transferCount; }
    public LocalDateTime getSealTime() { return sealTime; }
    public void setSealTime(LocalDateTime sealTime) { this.sealTime = sealTime; }
}
