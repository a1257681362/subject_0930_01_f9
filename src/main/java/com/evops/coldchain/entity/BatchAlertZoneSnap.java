package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 批次预警模板快照：派车（批次创建）时按药品类别把解析后的生效模板逐温区固化。
 * 继承上级阈值的温区以 inherited=TRUE + sourceCategory* 标明来源。
 * 此后类别树/模板变更与本批次脱钩，连续超限判定与预警追溯一律以快照为准。
 */
@TableName("t_cc_batch_alert_zone_snap")
public class BatchAlertZoneSnap extends CreateOnlyEntity {
    private Long batchId;
    private Long categoryId;
    private Integer templateVersionNo;
    private String zoneCode;
    private String zoneName;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private Integer tolerateMinutes;
    private Integer jitterGapMinutes;
    /** TRUE=继承上级类别阈值；FALSE=本类别在该温区显式覆盖 */
    private Boolean inherited;
    private Long sourceCategoryId;
    private String sourceCategoryNo;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Integer getTemplateVersionNo() { return templateVersionNo; }
    public void setTemplateVersionNo(Integer templateVersionNo) { this.templateVersionNo = templateVersionNo; }
    public String getZoneCode() { return zoneCode; }
    public void setZoneCode(String zoneCode) { this.zoneCode = zoneCode; }
    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public Integer getTolerateMinutes() { return tolerateMinutes; }
    public void setTolerateMinutes(Integer tolerateMinutes) { this.tolerateMinutes = tolerateMinutes; }
    public Integer getJitterGapMinutes() { return jitterGapMinutes; }
    public void setJitterGapMinutes(Integer jitterGapMinutes) { this.jitterGapMinutes = jitterGapMinutes; }
    public Boolean getInherited() { return inherited; }
    public void setInherited(Boolean inherited) { this.inherited = inherited; }
    public Long getSourceCategoryId() { return sourceCategoryId; }
    public void setSourceCategoryId(Long sourceCategoryId) { this.sourceCategoryId = sourceCategoryId; }
    public String getSourceCategoryNo() { return sourceCategoryNo; }
    public void setSourceCategoryNo(String sourceCategoryNo) { this.sourceCategoryNo = sourceCategoryNo; }
}
