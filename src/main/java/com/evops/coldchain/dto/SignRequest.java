package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 签收请求：闭环前必须按派车时固化的档案快照逐项核对——
 * 实际批准文号、包装规格、数量与快照一致，且无未处理（OPEN）异常，方可签收闭环。
 */
public class SignRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotBlank(message = "签收人不能为空")
    private String signBy;
    /** 实物批准文号（与快照核对） */
    @NotBlank(message = "实物批准文号不能为空")
    private String receivedApprovalNo;
    /** 实物包装规格（与快照核对） */
    @NotBlank(message = "实物包装规格不能为空")
    private String receivedPackageSpec;
    /** 实收数量（与发运数量核对） */
    @NotNull(message = "实收数量不能为空")
    @Positive(message = "实收数量必须大于0")
    private Integer confirmQty;
    private String signRemark;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSignBy() { return signBy; }
    public void setSignBy(String signBy) { this.signBy = signBy; }
    public String getReceivedApprovalNo() { return receivedApprovalNo; }
    public void setReceivedApprovalNo(String receivedApprovalNo) { this.receivedApprovalNo = receivedApprovalNo; }
    public String getReceivedPackageSpec() { return receivedPackageSpec; }
    public void setReceivedPackageSpec(String receivedPackageSpec) { this.receivedPackageSpec = receivedPackageSpec; }
    public Integer getConfirmQty() { return confirmQty; }
    public void setConfirmQty(Integer confirmQty) { this.confirmQty = confirmQty; }
    public String getSignRemark() { return signRemark; }
    public void setSignRemark(String signRemark) { this.signRemark = signRemark; }
}
