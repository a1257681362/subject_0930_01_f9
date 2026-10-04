package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分仓交接单（一车货到中转仓后可拆成多个交接单）。
 * 每单记录箱数、封签数、交接温度、接收人；提交（SUBMITTED）后箱数/封签/温度/接收人冻结，
 * 责任主体由原承运批次随单转移给接收方（responsibleOrg/responsibleParty）。
 * 作废仅置 VOIDED 状态，不删除封签核验与操作日志任何原始行。
 */
@TableName("t_cc_handover_order")
public class HandoverOrder extends BaseEntity {
    private String handoverNo;
    private Long batchId;
    private String warehouseName;
    private Integer boxQty;
    private Integer sealQty;
    private BigDecimal handoverTemp;
    private String receiverName;
    private String receiverOrg;
    private LocalDateTime handoverTime;
    /** DRAFT / SUBMITTED / VOIDED */
    private String status;
    /** 责任主体：提交前为原承运责任主体，提交后随单转移为接收方 */
    private String responsibleOrg;
    private String responsibleParty;
    private LocalDateTime submitTime;
    private String voidReason;
    private LocalDateTime voidTime;
    private String remark;

    public String getHandoverNo() { return handoverNo; }
    public void setHandoverNo(String handoverNo) { this.handoverNo = handoverNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public Integer getBoxQty() { return boxQty; }
    public void setBoxQty(Integer boxQty) { this.boxQty = boxQty; }
    public Integer getSealQty() { return sealQty; }
    public void setSealQty(Integer sealQty) { this.sealQty = sealQty; }
    public BigDecimal getHandoverTemp() { return handoverTemp; }
    public void setHandoverTemp(BigDecimal handoverTemp) { this.handoverTemp = handoverTemp; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverOrg() { return receiverOrg; }
    public void setReceiverOrg(String receiverOrg) { this.receiverOrg = receiverOrg; }
    public LocalDateTime getHandoverTime() { return handoverTime; }
    public void setHandoverTime(LocalDateTime handoverTime) { this.handoverTime = handoverTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResponsibleOrg() { return responsibleOrg; }
    public void setResponsibleOrg(String responsibleOrg) { this.responsibleOrg = responsibleOrg; }
    public String getResponsibleParty() { return responsibleParty; }
    public void setResponsibleParty(String responsibleParty) { this.responsibleParty = responsibleParty; }
    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public LocalDateTime getVoidTime() { return voidTime; }
    public void setVoidTime(LocalDateTime voidTime) { this.voidTime = voidTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
