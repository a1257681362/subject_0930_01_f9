package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 统一操作日志：所有关键操作（建档/变更/派车/发运/温度上报/异常/签收/撤回）
 * 均追加一行，留存操作者账号与操作时间。
 */
@TableName("t_cc_op_log")
public class OpLog extends CreateOnlyEntity {
    /** DRUG / VEHICLE / ORDER / BATCH / TEMPERATURE / EXCEPTION */
    private String entityType;
    private Long entityId;
    private Long batchId;
    private String action;
    private String summary;
    private String operator;

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
