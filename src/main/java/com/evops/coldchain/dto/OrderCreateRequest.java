package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

/**
 * 发货单创建请求。
 * drugVersionNo 不传则锁定档案当前版本；一旦创建即固化，档案后续变更不影响本单。
 */
public class OrderCreateRequest {
    @NotNull(message = "药品档案ID不能为空")
    private Long drugId;
    private Integer drugVersionNo;
    @NotBlank(message = "收货机构不能为空")
    private String receiverOrg;
    @NotNull(message = "发货数量不能为空")
    @Positive(message = "发货数量必须大于0")
    private Integer shipQty;
    private String senderOrg;
    private LocalDateTime planShipTime;

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
}
