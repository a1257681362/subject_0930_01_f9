package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 派车请求：发货单 + 承运车辆，生成运输批次并固化档案快照 */
public class DispatchRequest {
    @NotNull(message = "发货单ID不能为空")
    private Long orderId;
    @NotNull(message = "承运车辆ID不能为空")
    private Long vehicleId;
    /**
     * 可选运输路线ID：传入则按派车时点的季节选取该路线生效版本，逐段固化温区分段快照。
     * 不传则保持 V1 全程单一档案温区口径。
     */
    private Long routeId;
    /**
     * V7 可选监管区域：传入则随批次固化，作为抽检查询区域隔离依据；
     * 不传也可在首次对该批次立项抽检时补登，补登后不再改变。
     */
    private String superviseRegion;
    /**
     * V8 锁价单ID：冷链承运商报价比选完成后，派车必须引用一张有效（未作废、非改价审批中）
     * 的锁价单；批次以此作为锁价单反查锚点。
     */
    private Long lockedPriceId;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
    public Long getLockedPriceId() { return lockedPriceId; }
    public void setLockedPriceId(Long lockedPriceId) { this.lockedPriceId = lockedPriceId; }
}
