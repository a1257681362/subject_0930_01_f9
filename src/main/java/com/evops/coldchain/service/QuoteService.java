package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.QuoteBatchOpenRequest;
import com.evops.coldchain.dto.QuoteSubmitRequest;
import com.evops.coldchain.dto.SurchargeItem;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.entity.CarrierQuote;
import com.evops.coldchain.entity.QuoteBatch;
import com.evops.coldchain.entity.QuoteSurcharge;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.mapper.CarrierQuoteMapper;
import com.evops.coldchain.mapper.QuoteBatchMapper;
import com.evops.coldchain.mapper.QuoteSurchargeMapper;
import com.evops.coldchain.mapper.RouteMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 承运商报价比选服务（V8）。
 *
 * - 报价批次固化 路线/温区/箱型/服务等级 四维与报价有效期，OPEN 期间多家承运商并行报价；
 * - 同一承运商可在截止前修订（只追加新版本行，旧版 SUPERSEDED 原值保留）；
 * - 超过截止时间懒标记 + 定时扫描双保险置 EXPIRED，过期批次的报价不能再锁价/被引用；
 * - 比选视图按含税总价升序给出各承运商当前有效方案及金额拆项。
 */
@Service
public class QuoteService {

    @Resource
    private QuoteBatchMapper batchMapper;
    @Resource
    private CarrierQuoteMapper quoteMapper;
    @Resource
    private QuoteSurchargeMapper surchargeMapper;
    @Resource
    private CarrierService carrierService;
    @Resource
    private RouteMapper routeMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 报价批次 ----------------

    /** 发起询价（报价批次） */
    @Transactional
    public QuoteBatch openBatch(QuoteBatchOpenRequest req) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = req.getQuoteStartTime() == null ? now : req.getQuoteStartTime();
        LocalDateTime expire = req.getQuoteExpireTime();
        if (!expire.isAfter(now)) {
            throw new BusinessException("报价截止时间必须晚于当前时间，不能发起一个即过期的报价批次");
        }
        if (!expire.isAfter(start)) {
            throw new BusinessException("报价截止时间必须晚于报价开始时间");
        }

        Long routeId = req.getRouteId();
        String routeName = null;
        String origin = trimToNull(req.getOrigin());
        String destination = trimToNull(req.getDestination());
        if (routeId != null) {
            Route route = routeMapper.selectById(routeId);
            if (route == null) {
                throw new BusinessException("报价路线不存在: " + routeId);
            }
            routeName = route.getRouteName();
            if (origin == null) {
                origin = route.getOrigin();
            }
            if (destination == null) {
                destination = route.getDestination();
            }
        } else {
            if (origin == null || destination == null) {
                throw new BusinessException("不指定路线时，起点 origin 与终点 destination 必须同时给出");
            }
        }

        String zoneCode = req.getTempZoneCode().trim().toUpperCase();
        String boxCode = req.getBoxType().trim().toUpperCase();
        String levelCode = req.getServiceLevel().trim().toUpperCase();
        String zoneName = QuoteDimensions.zoneName(zoneCode);
        String boxName = QuoteDimensions.boxTypeName(boxCode);
        String levelName = QuoteDimensions.serviceLevelName(levelCode);

        QuoteBatch batch = new QuoteBatch();
        batch.setQuoteBatchNo(generateQuoteBatchNo());
        batch.setRouteId(routeId);
        batch.setRouteNameSnap(routeName);
        batch.setOrigin(origin);
        batch.setDestination(destination);
        batch.setTempZoneCode(zoneCode);
        batch.setTempZoneName(zoneName);
        batch.setBoxType(boxCode);
        batch.setBoxTypeName(boxName);
        batch.setServiceLevel(levelCode);
        batch.setServiceLevelName(levelName);
        batch.setBoxQty(req.getBoxQty());
        batch.setPlanShipTime(req.getPlanShipTime());
        batch.setQuoteStartTime(start);
        batch.setQuoteExpireTime(expire);
        batch.setStatus(CcConstants.QB_OPEN);
        batch.setRemark(req.getRemark());
        batchMapper.insert(batch);

        opLogService.log(CcConstants.ENT_QUOTE_BATCH, batch.getId(), null, CcConstants.ACT_QUOTE_BATCH_OPEN,
                String.format("发起报价批次 %s：%s -> %s，温区=%s，箱型=%s，服务等级=%s，箱量=%d，报价截止=%s",
                        batch.getQuoteBatchNo(), origin, destination, zoneName, boxName, levelName,
                        req.getBoxQty(), expire));
        return batch;
    }

    /** 人工提前截止收报价：截止后不能再提交/修订，但已报有效方案仍可锁价比选 */
    @Transactional
    public QuoteBatch closeBatch(Long quoteBatchId) {
        QuoteBatch batch = requireBatch(quoteBatchId);
        expireIfDue(batch, LocalDateTime.now());
        if (!CcConstants.QB_OPEN.equals(batch.getStatus())) {
            throw new BusinessException(String.format("报价批次 %s 当前状态=%s，仅报价中(OPEN)批次可人工截止",
                    batch.getQuoteBatchNo(), batch.getStatus()));
        }
        batch.setStatus(CcConstants.QB_CLOSED);
        batch.setCloseTime(LocalDateTime.now());
        batchMapper.updateById(batch);
        opLogService.log(CcConstants.ENT_QUOTE_BATCH, batch.getId(), null, CcConstants.ACT_QUOTE_BATCH_CLOSE,
                String.format("报价批次 %s 人工截止收报价：已提交报价仍可参与比选锁价，不能再提交/修订",
                        batch.getQuoteBatchNo()));
        return batch;
    }

    /**
     * 懒过期：批次 OPEN 且已过截止时间 -> EXPIRED，其全部当前有效报价置 EXPIRED。
     * 过期后报价不能再被新批次引用/锁价。
     */
    @Transactional
    public QuoteBatch expireIfDue(Long quoteBatchId) {
        return expireIfDue(requireBatch(quoteBatchId), LocalDateTime.now());
    }

    @Transactional
    public QuoteBatch expireIfDue(QuoteBatch batch, LocalDateTime now) {
        if (CcConstants.QB_OPEN.equals(batch.getStatus())
                && !now.isBefore(batch.getQuoteExpireTime())) {
            doExpire(batch, now);
        }
        return batch;
    }

    /** 定时/手动扫描：把所有到点的 OPEN 批次置 EXPIRED，返回扫描记账 */
    @Transactional
    public Map<String, Object> scanExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<QuoteBatch> due = batchMapper.selectList(new QueryWrapper<QuoteBatch>()
                .eq("status", CcConstants.QB_OPEN)
                .le("quote_expire_time", now));
        for (QuoteBatch batch : due) {
            doExpire(batch, now);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("expiredBatches", due.size());
        summary.put("scanTime", now);
        return summary;
    }

    private void doExpire(QuoteBatch batch, LocalDateTime now) {
        batch.setStatus(CcConstants.QB_EXPIRED);
        batch.setCloseTime(now);
        batchMapper.updateById(batch);
        List<CarrierQuote> activeQuotes = listCurrentQuotes(batch.getId());
        for (CarrierQuote q : activeQuotes) {
            q.setStatus(CcConstants.QUOTE_EXPIRED);
            quoteMapper.updateById(q);
        }
        opLogService.log(CcConstants.ENT_QUOTE_BATCH, batch.getId(), null, CcConstants.ACT_QUOTE_BATCH_EXPIRE,
                String.format("报价批次 %s 已于 %s 过期：%d 张有效报价全部失效，不能再被引用或锁价",
                        batch.getQuoteBatchNo(), batch.getQuoteExpireTime(), activeQuotes.size()));
    }

    // ---------------- 并行报价 ----------------

    /**
     * 承运商提交报价（OPEN 期间可重复调用=修订）：只追加新版本行，
     * 同承运商旧当前版本置 SUPERSEDED；金额按统一口径重算（基础运费+附加费+税率）。
     */
    @Transactional
    public CarrierQuote submitQuote(QuoteSubmitRequest req) {
        LocalDateTime now = LocalDateTime.now();
        QuoteBatch batch = requireBatch(req.getQuoteBatchId());
        expireIfDue(batch, now);
        if (!CcConstants.QB_OPEN.equals(batch.getStatus())) {
            throw new BusinessException(String.format(
                    "报价批次 %s 当前状态=%s：过期/已截止批次不能再提交或修订报价（过期报价不能被新批次引用）",
                    batch.getQuoteBatchNo(), batch.getStatus()));
        }
        Carrier carrier = carrierService.requireActiveCarrier(req.getCarrierId());

        BigDecimal base = req.getBaseAmount();
        if (base.signum() < 0) {
            throw new BusinessException("基础运费不能为负");
        }
        BigDecimal taxRate = req.getTaxRate();
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new BusinessException("税率必须在 [0,1) 区间内（如 0.09 表示 9%）");
        }
        if (req.getValidUntil() == null) {
            throw new BusinessException("报价有效期 validUntil 不能为空");
        }
        if (req.getValidUntil().isBefore(now)) {
            throw new BusinessException("报价有效期不能早于当前时间");
        }
        if (req.getValidUntil().isAfter(batch.getQuoteExpireTime())) {
            throw new BusinessException(String.format(
                    "报价有效期 %s 不能晚于报价批次截止时间 %s",
                    req.getValidUntil(), batch.getQuoteExpireTime()));
        }

        // 附加费逐行落库（INSERT-only），金额合计由服务端汇总，调用方无法给一口价
        List<QuoteSurcharge> surchargeRows = new ArrayList<>();
        BigDecimal surchargeSum = PriceMath.sumSurcharge(null);
        if (req.getSurcharges() != null) {
            for (SurchargeItem item : req.getSurcharges()) {
                if (item.getAmount().signum() < 0) {
                    throw new BusinessException("附加费金额不能为负: " + item.getSurchargeType());
                }
                QuoteSurcharge row = new QuoteSurcharge();
                row.setSurchargeType(item.getSurchargeType().trim().toUpperCase());
                row.setTypeName(QuoteDimensions.surchargeTypeName(
                        item.getSurchargeType(), item.getTypeName()));
                row.setAmount(PriceMath.money(item.getAmount()));
                row.setRemark(item.getRemark());
                surchargeRows.add(row);
                surchargeSum = surchargeSum.add(row.getAmount());
            }
        }
        PriceMath.Breakdown bd = new PriceMath.Breakdown(base, surchargeSum, taxRate);

        // 修订：旧当前版本置 SUPERSEDED（原值/明细行永不修改）
        CarrierQuote previous = currentQuote(batch.getId(), carrier.getId());
        int versionNo = 1;
        if (previous != null) {
            previous.setStatus(CcConstants.QUOTE_SUPERSEDED);
            previous.setSupersededTime(now);
            quoteMapper.updateById(previous);
            versionNo = previous.getVersionNo() + 1;
        }

        CarrierQuote quote = new CarrierQuote();
        quote.setQuoteBatchId(batch.getId());
        quote.setVersionNo(versionNo);
        quote.setCarrierId(carrier.getId());
        quote.setCarrierNameSnap(carrier.getCarrierName());
        quote.setRouteId(batch.getRouteId());
        quote.setRouteNameSnap(batch.getRouteNameSnap());
        quote.setTempZoneCode(batch.getTempZoneCode());
        quote.setTempZoneName(batch.getTempZoneName());
        quote.setBoxType(batch.getBoxType());
        quote.setBoxTypeName(batch.getBoxTypeName());
        quote.setServiceLevel(batch.getServiceLevel());
        quote.setServiceLevelName(batch.getServiceLevelName());
        quote.setBoxQty(batch.getBoxQty());
        applyBreakdown(quote, bd);
        quote.setCurrency("CNY");
        quote.setValidUntil(req.getValidUntil());
        quote.setQuoteRemark(req.getQuoteRemark());
        quote.setSubmittedBy(UserContext.currentId());
        quote.setSubmittedByName(UserContext.currentName());
        quote.setSubmitTime(now);
        quote.setStatus(CcConstants.QUOTE_SUBMITTED);
        quoteMapper.insert(quote);

        for (QuoteSurcharge row : surchargeRows) {
            row.setQuoteId(quote.getId());
            surchargeMapper.insert(row);
        }

        opLogService.log(CcConstants.ENT_QUOTE, quote.getId(), null, CcConstants.ACT_QUOTE_SUBMIT,
                String.format("承运商[%s]对报价批次 %s 提交报价 V%d（%s）：基础运费=%s，附加费=%d 项合计=%s，税率=%s，税额=%s，含税总价=%s，有效期至=%s%s",
                        carrier.getCarrierName(), batch.getQuoteBatchNo(), versionNo,
                        versionNo == 1 ? "首次报价" : "修订报价，旧版本已置 SUPERSEDED 原值保留",
                        bd.base, surchargeRows.size(), bd.surcharge,
                        percent(bd.taxRate), bd.tax, bd.total, req.getValidUntil(),
                        req.getQuoteRemark() == null ? "" : "；备注=" + req.getQuoteRemark()));
        return quote;
    }

    static void applyBreakdown(CarrierQuote quote, PriceMath.Breakdown bd) {
        quote.setBaseAmount(bd.base);
        quote.setSurchargeAmount(bd.surcharge);
        quote.setSubtotalAmount(bd.subtotal);
        quote.setTaxRate(bd.taxRate);
        quote.setTaxAmount(bd.tax);
        quote.setTotalAmount(bd.total);
    }

    static String percent(BigDecimal taxRate) {
        return taxRate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    // ---------------- 查询 / 比选 ----------------

    public QuoteBatch requireBatch(Long quoteBatchId) {
        QuoteBatch batch = batchMapper.selectById(quoteBatchId);
        if (batch == null) {
            throw new BusinessException("报价批次不存在: " + quoteBatchId);
        }
        return batch;
    }

    /** 取批次实时视图：先懒过期，再返回批次头、各承运商当前报价（含附加费、修订版本数）、比选排名 */
    public Map<String, Object> batchDetail(Long quoteBatchId) {
        QuoteBatch batch = expireIfDue(quoteBatchId);
        return assembleDetail(batch, LocalDateTime.now());
    }

    /** 比选视图（锁价前）：当前有效方案按含税总价升序 */
    public List<Map<String, Object>> compare(Long quoteBatchId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) batchDetail(quoteBatchId).get("comparison");
        return rows;
    }

    private Map<String, Object> assembleDetail(QuoteBatch batch, LocalDateTime now) {
        List<CarrierQuote> currents = listCurrentQuotes(batch.getId());
        List<Map<String, Object>> comparison = new ArrayList<>();
        for (CarrierQuote q : currents) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("quote", q);
            row.put("surcharges", surchargeMapper.selectList(new QueryWrapper<QuoteSurcharge>()
                    .eq("quote_id", q.getId()).isNull("locked_price_id").orderByAsc("id")));
            int versionCount = quoteMapper.selectCount(new QueryWrapper<CarrierQuote>()
                    .eq("quote_batch_id", batch.getId())
                    .eq("carrier_id", q.getCarrierId())).intValue();
            row.put("versionCount", versionCount);
            // 可选（有效）方案：报价仍为当前 SUBMITTED、自身有效期未过、批次可锁价（OPEN/CLOSED）
            boolean batchLockable = CcConstants.QB_OPEN.equals(batch.getStatus())
                    || CcConstants.QB_CLOSED.equals(batch.getStatus());
            row.put("valid", batchLockable
                    && CcConstants.QUOTE_SUBMITTED.equals(q.getStatus())
                    && !now.isAfter(q.getValidUntil()));
            comparison.add(row);
        }
        comparison.sort((a, b) -> {
            CarrierQuote qa = (CarrierQuote) a.get("quote");
            CarrierQuote qb = (CarrierQuote) b.get("quote");
            int byTotal = qa.getTotalAmount().compareTo(qb.getTotalAmount());
            return byTotal != 0 ? byTotal : qa.getId().compareTo(qb.getId());
        });
        int rank = 1;
        for (Map<String, Object> row : comparison) {
            row.put("rank", rank++);
        }

        // 全部历版报价（修订链可追）
        List<CarrierQuote> history = quoteMapper.selectList(new QueryWrapper<CarrierQuote>()
                .eq("quote_batch_id", batch.getId()).orderByAsc("id"));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batch", batch);
        detail.put("comparison", comparison);
        detail.put("quoteHistory", history);
        detail.put("quoteCount", currents.size());
        return detail;
    }

    /** 各承运商当前版本报价（SUBMITTED/LOCKED/REJECTED/EXPIRED，不含 SUPERSEDED） */
    public List<CarrierQuote> listCurrentQuotes(Long quoteBatchId) {
        return quoteMapper.selectList(new QueryWrapper<CarrierQuote>()
                .eq("quote_batch_id", quoteBatchId)
                .ne("status", CcConstants.QUOTE_SUPERSEDED)
                .orderByAsc("id"));
    }

    /** 某承运商在批次内的当前（SUBMITTED）报价版本 */
    public CarrierQuote currentQuote(Long quoteBatchId, Long carrierId) {
        List<CarrierQuote> rows = quoteMapper.selectList(new QueryWrapper<CarrierQuote>()
                .eq("quote_batch_id", quoteBatchId)
                .eq("carrier_id", carrierId)
                .eq("status", CcConstants.QUOTE_SUBMITTED)
                .orderByDesc("version_no"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 全部报价批次（管理视图） */
    public List<QuoteBatch> listBatches(String status) {
        QueryWrapper<QuoteBatch> qw = new QueryWrapper<QuoteBatch>().orderByDesc("id");
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        return batchMapper.selectList(qw);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 报价批次号：BJ + 9 位序列 */
    private String generateQuoteBatchNo() {
        return String.format("BJ%09d", sequenceMapper.nextQuoteBatchNo());
    }
}
