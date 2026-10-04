package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 发货单：创建即锁定档案版本 drug_version_no，
 * 后续药品档案变更不影响本单及由其生成的运输批次。
 */
@TableName("t_cc_shipment_order")
public class ShipmentOrder extends BaseEntity {
    private String orderNo;
    private Long drugId;
    private Integer drugVersionNo;
    private String receiverOrg;
    private Integer shipQty;
    private String senderOrg;
    private LocalDateTime planShipTime;
    /** CREATED / DISPATCHED / SHIPPED / SIGNED / REVOKED */
    private String status;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public Integer getDrugVersionNo() { return drugVersionNo; }
    public void setDrugVersionNo(Integer drugVersionNo) { this.drugVersionNo = drugVersionNo; }
    public String getReceiverOrg() { return receiverOrg; }
    public void setReceiverOrg(String receiverOrg) { this.receiverOrg = receiverOrg; }
    public Integer getShipQty() { return shipQty; }
    public void setShipQty(Integer shipQty) { this.shipQty = shipQty; }
    public String getSenderOrg() { return senderOrg; }
    public void setSenderOrg(String senderOrg) { this.senderOrg = senderOrg; }
    public LocalDateTime getPlanShipTime() { return planShipTime; }
    public void setPlanShipTime(LocalDateTime planShipTime) { this.planShipTime = planShipTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
