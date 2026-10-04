package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 承运商建档请求（V8） */
public class CarrierCreateRequest {
    @NotBlank(message = "承运商名称不能为空")
    private String carrierName;
    private String contactName;
    private String contactPhone;
    private String qualification;
    private String remark;

    public String getCarrierName() { return carrierName; }
    public void setCarrierName(String carrierName) { this.carrierName = carrierName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getQualification() { return qualification; }
    public void setQualification(String qualification) { this.qualification = qualification; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
