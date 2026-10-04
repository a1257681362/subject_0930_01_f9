package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/** 承运车辆 */
@TableName("t_cc_vehicle")
public class Vehicle extends BaseEntity {
    private String plateNo;
    private String carrierName;
    /** V8：承运商主档引用（可空；报价/锁价以承运商主档为准） */
    private Long carrierId;
    private String driverName;
    private String driverPhone;
    private String fridgeModel;
    /** ACTIVE / REVOKED */
    private String status;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getCarrierName() { return carrierName; }
    public void setCarrierName(String carrierName) { this.carrierName = carrierName; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getDriverPhone() { return driverPhone; }
    public void setDriverPhone(String driverPhone) { this.driverPhone = driverPhone; }
    public String getFridgeModel() { return fridgeModel; }
    public void setFridgeModel(String fridgeModel) { this.fridgeModel = fridgeModel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
