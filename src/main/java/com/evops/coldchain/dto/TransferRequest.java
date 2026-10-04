package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 样本移交发起请求：移交人（当前登录账号）发起即确认移交，
 * 单据停留 PENDING，等待“另一个账号”的接收人双人确认。
 */
public class TransferRequest {
    @NotNull(message = "样本ID不能为空")
    private Long sampleId;
    @NotBlank(message = "拟接收人不能为空")
    private String toCustodianName;
    private String toCustodianOrg;
    @NotBlank(message = "移交地点不能为空")
    private String handoverLocation;
    private String transferNote;

    public Long getSampleId() { return sampleId; }
    public void setSampleId(Long sampleId) { this.sampleId = sampleId; }
    public String getToCustodianName() { return toCustodianName; }
    public void setToCustodianName(String toCustodianName) { this.toCustodianName = toCustodianName; }
    public String getToCustodianOrg() { return toCustodianOrg; }
    public void setToCustodianOrg(String toCustodianOrg) { this.toCustodianOrg = toCustodianOrg; }
    public String getHandoverLocation() { return handoverLocation; }
    public void setHandoverLocation(String handoverLocation) { this.handoverLocation = handoverLocation; }
    public String getTransferNote() { return transferNote; }
    public void setTransferNote(String transferNote) { this.transferNote = transferNote; }
}
