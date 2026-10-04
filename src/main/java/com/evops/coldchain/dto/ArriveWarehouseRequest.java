package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

/**
 * 一车货到达中转仓登记：在分仓交接前登记到仓总箱数，作为后续拆单“箱数平衡”的基准。
 */
public class ArriveWarehouseRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** 中转仓名称（不传用默认中转仓） */
    private String warehouseName;
    /** 到仓总箱数：全部交接单箱数合计必须正好等于该值 */
    @NotNull(message = "到仓总箱数不能为空")
    @Positive(message = "到仓总箱数必须大于0")
    private Integer arrivedBoxQty;
    /** 到仓时间（不传取当前时间） */
    private LocalDateTime arriveTime;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public Integer getArrivedBoxQty() { return arrivedBoxQty; }
    public void setArrivedBoxQty(Integer arrivedBoxQty) { this.arrivedBoxQty = arrivedBoxQty; }
    public LocalDateTime getArriveTime() { return arriveTime; }
    public void setArriveTime(LocalDateTime arriveTime) { this.arriveTime = arriveTime; }
}
