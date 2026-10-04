package com.evops.coldchain.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * H2 序列取值：稳定单号/批次号由数据库序列生成，
 * 保证多实例、重启后编号稳定、单调、不复用。
 */
@Mapper
public interface SequenceMapper {

    @Select("SELECT NEXT VALUE FOR seq_cc_drug_no")
    Long nextDrugNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_order_no")
    Long nextOrderNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_batch_no")
    Long nextBatchNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_route_no")
    Long nextRouteNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_probe_no")
    Long nextProbeNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_handover_no")
    Long nextHandoverNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_category_no")
    Long nextCategoryNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_inspection_no")
    Long nextInspectionNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_approval_no")
    Long nextApprovalNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_seal_no")
    Long nextSealNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_carrier_no")
    Long nextCarrierNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_quote_batch_no")
    Long nextQuoteBatchNo();

    @Select("SELECT NEXT VALUE FOR seq_cc_locked_price_no")
    Long nextLockedPriceNo();
}
