package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运输批次（冷链台账主表）。
 * 派车时把药品档案的批准文号/温区/包装规格+版本号快照到本表，
 * 此后档案即便变更，本批次核对、签收一律以快照为准。
 */
@TableName("t_cc_transport_batch")
public class TransportBatch extends BaseEntity {
    private String batchNo;
    private Long orderId;
    private Long vehicleId;
    private Long drugId;

    // —— 药品档案快照（派车时固化，不可变）——
    private Integer drugVersionNo;
    private String approvalNoSnap;
    private BigDecimal tempMinSnap;
    private BigDecimal tempMaxSnap;
    private String packageSpecSnap;

    // —— 运输信息 ——
    private String receiverOrg;
    private Integer shipQty;
    private LocalDateTime dispatchTime;
    private LocalDateTime shipTime;
    private LocalDateTime signTime;
    private String signBy;
    private String signRemark;
    private Boolean hasTempExcursion;
    /** DISPATCHED / SHIPPED / EXCEPTION / SIGNED / REVOKED */
    private String status;

    // —— 路线温区分段引用（派车登记路线、发运时选定季节版本；其全部分段固化在 t_cc_route_segment_snap）——
    private Long routeId;
    private Long routeVersionId;
    private String routeSeason;
    private Integer routeVersionNo;

    // —— 车载温度探头校准凭证快照（发运时逐字段固化；替换探头/作废凭证不影响在途批次）——
    private Long probeId;
    private Long probeBindId;
    private Long probeCertId;
    private String probeSerialNoSnap;
    private BigDecimal probeRangeMinSnap;
    private BigDecimal probeRangeMaxSnap;
    private String probeCalibrateOrgSnap;
    private String probeCertNoSnap;
    private LocalDateTime probeValidUntilSnap;
    private String probeSha256Snap;

    // —— V4 分仓交接：到仓总箱数（分箱平衡基准）与当前责任主体（随交接提交转移）——
    /** 一车货到达中转仓时登记的到仓总箱数；全部交接单箱数合计必须与之平衡 */
    private Integer arrivedBoxQty;
    /** 当前责任主体机构：发运时为承运商，交接提交后随单转移为接收方 */
    private String responsibleOrg;
    /** 当前责任主体（驾驶员/接收人等具体责任人） */
    private String responsibleParty;

    // —— V5 温控预警：派车时按药品储存温区从类别模板快照命中的温区代码（连续超限/预警按此温区）——
    private String alertZoneCode;

    // —— V6：结算记账（settleTime 为空=尚未结算；未关闭异常未清零前不可结算）——
    private LocalDateTime settleTime;
    private Long settleBy;
    private String settleRemark;

    // —— V7 运输抽检：监管区域与抽检结论回写列（只写这些独立列，不覆盖任何原始运输记录）——
    /** 批次归属监管区域（抽检查询按区域隔离） */
    private String superviseRegion;
    /** 最近一次回写结论的抽检任务ID */
    private Long inspectionTaskId;
    /** 抽检结论 PASS 合格 / FAIL 不合格 */
    private String inspectionResult;
    private String inspectionConclusion;
    private Long inspectionBy;
    private LocalDateTime inspectionTime;

    // —— V8 承运商报价比选与锁价：派车必须引用一张有效锁价单（反查锚点）——
    private Long lockedPriceId;

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public Integer getDrugVersionNo() { return drugVersionNo; }
    public void setDrugVersionNo(Integer drugVersionNo) { this.drugVersionNo = drugVersionNo; }
    public String getApprovalNoSnap() { return approvalNoSnap; }
    public void setApprovalNoSnap(String approvalNoSnap) { this.approvalNoSnap = approvalNoSnap; }
    public BigDecimal getTempMinSnap() { return tempMinSnap; }
    public void setTempMinSnap(BigDecimal tempMinSnap) { this.tempMinSnap = tempMinSnap; }
    public BigDecimal getTempMaxSnap() { return tempMaxSnap; }
    public void setTempMaxSnap(BigDecimal tempMaxSnap) { this.tempMaxSnap = tempMaxSnap; }
    public String getPackageSpecSnap() { return packageSpecSnap; }
    public void setPackageSpecSnap(String packageSpecSnap) { this.packageSpecSnap = packageSpecSnap; }
    public String getReceiverOrg() { return receiverOrg; }
    public void setReceiverOrg(String receiverOrg) { this.receiverOrg = receiverOrg; }
    public Integer getShipQty() { return shipQty; }
    public void setShipQty(Integer shipQty) { this.shipQty = shipQty; }
    public LocalDateTime getDispatchTime() { return dispatchTime; }
    public void setDispatchTime(LocalDateTime dispatchTime) { this.dispatchTime = dispatchTime; }
    public LocalDateTime getShipTime() { return shipTime; }
    public void setShipTime(LocalDateTime shipTime) { this.shipTime = shipTime; }
    public LocalDateTime getSignTime() { return signTime; }
    public void setSignTime(LocalDateTime signTime) { this.signTime = signTime; }
    public String getSignBy() { return signBy; }
    public void setSignBy(String signBy) { this.signBy = signBy; }
    public String getSignRemark() { return signRemark; }
    public void setSignRemark(String signRemark) { this.signRemark = signRemark; }
    public Boolean getHasTempExcursion() { return hasTempExcursion; }
    public void setHasTempExcursion(Boolean hasTempExcursion) { this.hasTempExcursion = hasTempExcursion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public Long getRouteVersionId() { return routeVersionId; }
    public void setRouteVersionId(Long routeVersionId) { this.routeVersionId = routeVersionId; }
    public String getRouteSeason() { return routeSeason; }
    public void setRouteSeason(String routeSeason) { this.routeSeason = routeSeason; }
    public Integer getRouteVersionNo() { return routeVersionNo; }
    public void setRouteVersionNo(Integer routeVersionNo) { this.routeVersionNo = routeVersionNo; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public Long getProbeBindId() { return probeBindId; }
    public void setProbeBindId(Long probeBindId) { this.probeBindId = probeBindId; }
    public Long getProbeCertId() { return probeCertId; }
    public void setProbeCertId(Long probeCertId) { this.probeCertId = probeCertId; }
    public String getProbeSerialNoSnap() { return probeSerialNoSnap; }
    public void setProbeSerialNoSnap(String probeSerialNoSnap) { this.probeSerialNoSnap = probeSerialNoSnap; }
    public BigDecimal getProbeRangeMinSnap() { return probeRangeMinSnap; }
    public void setProbeRangeMinSnap(BigDecimal probeRangeMinSnap) { this.probeRangeMinSnap = probeRangeMinSnap; }
    public BigDecimal getProbeRangeMaxSnap() { return probeRangeMaxSnap; }
    public void setProbeRangeMaxSnap(BigDecimal probeRangeMaxSnap) { this.probeRangeMaxSnap = probeRangeMaxSnap; }
    public String getProbeCalibrateOrgSnap() { return probeCalibrateOrgSnap; }
    public void setProbeCalibrateOrgSnap(String probeCalibrateOrgSnap) { this.probeCalibrateOrgSnap = probeCalibrateOrgSnap; }
    public String getProbeCertNoSnap() { return probeCertNoSnap; }
    public void setProbeCertNoSnap(String probeCertNoSnap) { this.probeCertNoSnap = probeCertNoSnap; }
    public LocalDateTime getProbeValidUntilSnap() { return probeValidUntilSnap; }
    public void setProbeValidUntilSnap(LocalDateTime probeValidUntilSnap) { this.probeValidUntilSnap = probeValidUntilSnap; }
    public String getProbeSha256Snap() { return probeSha256Snap; }
    public void setProbeSha256Snap(String probeSha256Snap) { this.probeSha256Snap = probeSha256Snap; }
    public Integer getArrivedBoxQty() { return arrivedBoxQty; }
    public void setArrivedBoxQty(Integer arrivedBoxQty) { this.arrivedBoxQty = arrivedBoxQty; }
    public String getResponsibleOrg() { return responsibleOrg; }
    public void setResponsibleOrg(String responsibleOrg) { this.responsibleOrg = responsibleOrg; }
    public String getResponsibleParty() { return responsibleParty; }
    public void setResponsibleParty(String responsibleParty) { this.responsibleParty = responsibleParty; }
    public String getAlertZoneCode() { return alertZoneCode; }
    public void setAlertZoneCode(String alertZoneCode) { this.alertZoneCode = alertZoneCode; }

    // —— V6：结算记账 ——
    public LocalDateTime getSettleTime() { return settleTime; }
    public void setSettleTime(LocalDateTime settleTime) { this.settleTime = settleTime; }
    public Long getSettleBy() { return settleBy; }
    public void setSettleBy(Long settleBy) { this.settleBy = settleBy; }
    public String getSettleRemark() { return settleRemark; }
    public void setSettleRemark(String settleRemark) { this.settleRemark = settleRemark; }

    // —— V7：监管区域 + 抽检结论回写 ——
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
    public Long getInspectionTaskId() { return inspectionTaskId; }
    public void setInspectionTaskId(Long inspectionTaskId) { this.inspectionTaskId = inspectionTaskId; }
    public String getInspectionResult() { return inspectionResult; }
    public void setInspectionResult(String inspectionResult) { this.inspectionResult = inspectionResult; }
    public String getInspectionConclusion() { return inspectionConclusion; }
    public void setInspectionConclusion(String inspectionConclusion) { this.inspectionConclusion = inspectionConclusion; }
    public Long getInspectionBy() { return inspectionBy; }
    public void setInspectionBy(Long inspectionBy) { this.inspectionBy = inspectionBy; }
    public LocalDateTime getInspectionTime() { return inspectionTime; }
    public void setInspectionTime(LocalDateTime inspectionTime) { this.inspectionTime = inspectionTime; }
    public Long getLockedPriceId() { return lockedPriceId; }
    public void setLockedPriceId(Long lockedPriceId) { this.lockedPriceId = lockedPriceId; }
}
