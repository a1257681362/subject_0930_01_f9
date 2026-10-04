package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分仓交接建单请求：把一车货（一个在途批次）在中转仓拆成一个交接单（可连续建多个单）。
 * 每单记录箱数、封签号清单、交接温度与接收人；封签数必须与箱数一致（逐箱封签）。
 * 同批次全部交接单箱数合计必须正好等于到仓总箱数（箱数平衡），封签号在批次范围内不得重复。
 */
public class HandoverCreateRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotBlank(message = "中转仓名称不能为空")
    private String warehouseName;
    /** 本单箱数（必须 > 0，且等于封签数） */
    @NotNull(message = "本单箱数不能为空")
    @Positive(message = "本单箱数必须大于0")
    private Integer boxQty;
    /** 交接温度 ℃（缺失禁止建单/提交） */
    @NotNull(message = "交接温度不能为空")
    private BigDecimal handoverTemp;
    @NotBlank(message = "接收人不能为空")
    private String receiverName;
    @NotBlank(message = "接收责任主体（机构/班组）不能为空")
    private String receiverOrg;
    /** 交接时刻（不传取当前时间） */
    private LocalDateTime handoverTime;
    /** 封签号清单：逐箱封签，数量必须等于箱数；同批次范围内唯一 */
    @NotEmpty(message = "至少登记一个封签号")
    private List<@NotBlank(message = "封签号不能为空") String> sealNos;
    private String remark;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public Integer getBoxQty() { return boxQty; }
    public void setBoxQty(Integer boxQty) { this.boxQty = boxQty; }
    public BigDecimal getHandoverTemp() { return handoverTemp; }
    public void setHandoverTemp(BigDecimal handoverTemp) { this.handoverTemp = handoverTemp; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverOrg() { return receiverOrg; }
    public void setReceiverOrg(String receiverOrg) { this.receiverOrg = receiverOrg; }
    public LocalDateTime getHandoverTime() { return handoverTime; }
    public void setHandoverTime(LocalDateTime handoverTime) { this.handoverTime = handoverTime; }
    public List<String> getSealNos() { return sealNos; }
    public void setSealNos(List<String> sealNos) { this.sealNos = sealNos; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
