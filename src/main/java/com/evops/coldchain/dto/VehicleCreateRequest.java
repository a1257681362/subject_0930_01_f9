package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 承运车辆登记请求 */
public class VehicleCreateRequest {
    @NotBlank(message = "车牌号不能为空")
    private String plateNo;
    @NotBlank(message = "承运商不能为空")
    private String carrierName;
    private String driverName;
    private String driverPhone;
    private String fridgeModel;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getCarrierName() { return carrierName; }
    public void setCarrierName(String carrierName) { this.carrierName = carrierName; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getDriverPhone() { return driverPhone; }
    public void setDriverPhone(String driverPhone) { this.driverPhone = driverPhone; }
    public String getFridgeModel() { return fridgeModel; }
    public void setFridgeModel(String fridgeModel) { this.fridgeModel = fridgeModel; }
}
