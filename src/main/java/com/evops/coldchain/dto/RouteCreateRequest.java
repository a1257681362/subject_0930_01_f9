package com.evops.coldchain.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 运输路线建档请求（只建主档；温区分段通过季节版本草稿维护） */
public class RouteCreateRequest {
    @NotBlank(message = "路线名称不能为空")
    private String routeName;
    private String origin;
    private String destination;
    @NotNull(message = "路线总里程不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "路线总里程必须大于0")
    private BigDecimal totalKm;
    private String remark;

    public String getRouteName() { return routeName; }
    public void setRouteName(String routeName) { this.routeName = routeName; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public BigDecimal getTotalKm() { return totalKm; }
    public void setTotalKm(BigDecimal totalKm) { this.totalKm = totalKm; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
