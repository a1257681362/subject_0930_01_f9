package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

/**
 * 运输路线主档：一条路线一行，route_no 稳定不变。
 * 同一路线按季节（SPRING/SUMMER/AUTUMN/WINTER/GENERAL）各维护一套版本序列。
 */
@TableName("t_cc_route")
public class Route extends BaseEntity {
    private String routeNo;
    private String routeName;
    private String origin;
    private String destination;
    /** 路线总里程（公里），各季节版本的分段必须连续覆盖 0 ~ totalKm */
    private BigDecimal totalKm;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getRouteNo() { return routeNo; }
    public void setRouteNo(String routeNo) { this.routeNo = routeNo; }
    public String getRouteName() { return routeName; }
    public void setRouteName(String routeName) { this.routeName = routeName; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public BigDecimal getTotalKm() { return totalKm; }
    public void setTotalKm(BigDecimal totalKm) { this.totalKm = totalKm; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
