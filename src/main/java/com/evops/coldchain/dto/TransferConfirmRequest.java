package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 样本移交接收确认（双人第二人）：接收人账号必须与移交人不同。
 */
public class TransferConfirmRequest {
    /** 接收备注（可空） */
    private String receiverNote;
    /** 实际接收责任主体（不传取发起单上的拟接收机构） */
    private String toCustodianOrg;

    public String getReceiverNote() { return receiverNote; }
    public void setReceiverNote(String receiverNote) { this.receiverNote = receiverNote; }
    public String getToCustodianOrg() { return toCustodianOrg; }
    public void setToCustodianOrg(String toCustodianOrg) { this.toCustodianOrg = toCustodianOrg; }
}
