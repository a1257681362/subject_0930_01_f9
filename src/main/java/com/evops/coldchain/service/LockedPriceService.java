package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.PriceAdjustApplyRequest;
import com.evops.coldchain.dto.PriceLockRequest;
import com.evops.coldchain.dto.SurchargeItem;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.entity.CarrierQuote;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.QuoteAdjustApproval;
import com.evops.coldchain.entity.QuoteBatch;
import com.evops.coldchain.entity.QuoteSurcharge;
import com.evops.coldchain.mapper.CarrierMapper;
import com.evops.coldchain.mapper.CarrierQuoteMapper;
import com.evops.coldchain.mapper.LockedPriceMapper;
import com.evops.coldchain.mapper.QuoteAdjustApprovalMapper;
import com.evops.coldchain.mapper.QuoteBatchMapper;
import com.evops.coldchain.mapper.QuoteSurchargeMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.evops.coldchain.entity.TransportBatch;
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
 * 锁价与人工改价服务（V8）。
 *
 * - 锁价：报价批次只能选一个有效方案（uk_cc_locked_batch 数据库层兜底），中选报价 LOCKED、
 *   落选方案 REJECTED、批次 CLOSED；锁价瞬间全要素快照（含附加费逐行复制）。
 * - 过期报价不能被引用：锁价时再次懒过期 + 校验报价仍有效。
 * - 人工改价：只能发起审批（PENDING），审批同意后 final_* 才更新；原始报价金额 *_snap 与
 *   原始附加费快照永不覆盖；驳回则继续按原金额执行。
 * - 反查：锁价单 -> 中选原单（含全部历版）-> 报价批次 -> 承运商 -> 改价审批链，
 *   并聚合各环节处理人与异常原因（过期失效/落选/修订替换/改价驳回）。
 */
@Service
public class LockedPriceService {

    @Resource
    private LockedPriceMapper lockedPriceMapper;
    @Resource
    private CarrierQuoteMapper quoteMapper;
    @Resource
    private QuoteBatchMapper quoteBatchMapper;
    @Resource
    private CarrierMapper carrierMapper;
    @Resource
    private QuoteSurchargeMapper surchargeMapper;
    @Resource
    private QuoteAdjustApprovalMapper adjustMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private QuoteService quoteService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 锁价 ----------------

    @Transactional
    public LockedPrice lock(PriceLockRequest req) {
        LocalDateTime now = LocalDateTime.now();
        QuoteBatch batch = quoteService.requireBatch(req.getQuoteBatchId());
        quoteService.expireIfDue(batch, now);
        if (CcConstants.QB_EXPIRED.equals(batch.getStatus())) {
            throw new BusinessException(String.format(
                    "报价批次 %s 已过期（截止 %s），过期报价不能被引用或锁价，请重新发起报价批次",
                    batch.getQuoteBatchNo(), batch.getQuoteExpireTime()));
        }
        if (!CcConstants.QB_OPEN.equals(batch.getStatus()) && !CcConstants.QB_CLOSED.equals(batch.getStatus())) {
            throw new BusinessException(String.format("报价批次 %s 当前状态=%s，不能锁价",
                    batch.getQuoteBatchNo(), batch.getStatus()));
        }
        Long existing = lockedPriceMapper.selectCount(new QueryWrapper<LockedPrice>()
                .eq("quote_batch_id", batch.getId()));
        if (existing != null && existing > 0) {
            throw new BusinessException(String.format(
                    "报价批次 %s 已存在锁价单：一个报价批次只能选定一个有效方案，不能重复锁价",
                    batch.getQuoteBatchNo()));
        }

        CarrierQuote quote = quoteMapper.selectById(req.getQuoteId());
        if (quote == null || !quote.getQuoteBatchId().equals(batch.getId())) {
            throw new BusinessException("中选报价单不存在或不属于报价批次 " + batch.getQuoteBatchNo()
                    + ": " + req.getQuoteId());
        }
        if (!CcConstants.QUOTE_SUBMITTED.equals(quote.getStatus())) {
            throw new BusinessException(String.format("报价单#%d 当前状态=%s，仅有效(SUBMITTED)报价可中选锁价",
                    quote.getId(), quote.getStatus()));
        }
        if (now.isAfter(quote.getValidUntil())) {
            throw new BusinessException(String.format(
                    "承运商[%s]报价已于 %s 过有效期，不能再被引用锁价",
                    quote.getCarrierNameSnap(), quote.getValidUntil()));
        }

        LockedPrice lp = new LockedPrice();
        lp.setLockedPriceNo(generateLockedPriceNo());
        lp.setQuoteBatchId(batch.getId());
        lp.setQuoteId(quote.getId());
        lp.setQuoteVersionNo(quote.getVersionNo());
        lp.setCarrierId(quote.getCarrierId());
        lp.setCarrierNameSnap(quote.getCarrierNameSnap());
        // 维度快照
        lp.setRouteId(quote.getRouteId());
        lp.setRouteNameSnap(quote.getRouteNameSnap());
        lp.setOriginSnap(batch.getOrigin());
        lp.setDestinationSnap(batch.getDestination());
        lp.setTempZoneCodeSnap(quote.getTempZoneCode());
        lp.setTempZoneNameSnap(quote.getTempZoneName());
        lp.setBoxTypeSnap(quote.getBoxType());
        lp.setBoxTypeNameSnap(quote.getBoxTypeName());
        lp.setServiceLevelSnap(quote.getServiceLevel());
        lp.setServiceLevelNameSnap(quote.getServiceLevelName());
        lp.setBoxQtySnap(quote.getBoxQty());
        // 金额原始快照 + 生效金额（锁价时两者一致）
        lp.setBaseAmountSnap(quote.getBaseAmount());
        lp.setSurchargeAmountSnap(quote.getSurchargeAmount());
        lp.setSubtotalAmountSnap(quote.getSubtotalAmount());
        lp.setTaxRateSnap(quote.getTaxRate());
        lp.setTaxAmountSnap(quote.getTaxAmount());
        lp.setTotalAmountSnap(quote.getTotalAmount());
        lp.setFinalBaseAmount(quote.getBaseAmount());
        lp.setFinalSurchargeAmount(quote.getSurchargeAmount());
        lp.setFinalSubtotalAmount(quote.getSubtotalAmount());
        lp.setFinalTaxRate(quote.getTaxRate());
        lp.setFinalTaxAmount(quote.getTaxAmount());
        lp.setFinalTotalAmount(quote.getTotalAmount());
        lp.setCurrency(quote.getCurrency() == null ? "CNY" : quote.getCurrency());
        lp.setValidUntilSnap(quote.getValidUntil());
        lp.setLockTime(now);
        lp.setLockedBy(UserContext.currentId());
        lp.setLockedByName(UserContext.currentName());
        lp.setStatus(CcConstants.LP_LOCKED);
        lockedPriceMapper.insert(lp);

        // 附加费逐行快照（保留 quote_id 来源；adjust_id 为空=锁价原始快照，永不覆盖）
        List<QuoteSurcharge> originalRows = listQuoteOriginalSurcharges(quote.getId());
        for (QuoteSurcharge src : originalRows) {
            QuoteSurcharge snap = new QuoteSurcharge();
            snap.setQuoteId(quote.getId());
            snap.setLockedPriceId(lp.getId());
            snap.setSurchargeType(src.getSurchargeType());
            snap.setTypeName(src.getTypeName());
            snap.setAmount(src.getAmount());
            snap.setRemark(src.getRemark());
            surchargeMapper.insert(snap);
        }

        // 中选 LOCKED；同批其余当前有效报价落选 REJECTED；批次 CLOSED（只能选一个有效方案）
        quote.setStatus(CcConstants.QUOTE_LOCKED);
        quote.setLockedPriceId(lp.getId());
        quoteMapper.updateById(quote);
        List<CarrierQuote> rivals = quoteService.listCurrentQuotes(batch.getId());
        int rejected = 0;
        for (CarrierQuote other : rivals) {
            if (!other.getId().equals(quote.getId())
                    && CcConstants.QUOTE_SUBMITTED.equals(other.getStatus())) {
                other.setStatus(CcConstants.QUOTE_REJECTED);
                quoteMapper.updateById(other);
                rejected++;
            }
        }
        batch.setStatus(CcConstants.QB_CLOSED);
        batch.setCloseTime(now);
        quoteBatchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_LOCKED_PRICE, lp.getId(), null, CcConstants.ACT_QUOTE_LOCK,
                String.format("报价批次 %s 锁价：中选承运商[%s]报价 V%d（报价单#%d），含税总价=%s（基础=%s+附加费=%s，税率=%s，税额=%s），"
                                + "附加费逐行快照 %d 项；其余 %d 家落选置 REJECTED；锁价单号=%s，全要素快照此后与报价修订/过期脱钩",
                        batch.getQuoteBatchNo(), quote.getCarrierNameSnap(), quote.getVersionNo(), quote.getId(),
                        quote.getTotalAmount(), quote.getBaseAmount(), quote.getSurchargeAmount(),
                        QuoteService.percent(quote.getTaxRate()), quote.getTaxAmount(),
                        originalRows.size(), rejected, lp.getLockedPriceNo()));
        return lp;
    }

    /** 作废锁价单（只置状态不删快照；已被运输批次引用的不可作废） */
    @Transactional
    public LockedPrice voidLockedPrice(Long lockedPriceId, String reason) {
        LockedPrice lp = requireLockedPrice(lockedPriceId);
        if (CcConstants.LP_VOIDED.equals(lp.getStatus())) {
            throw new BusinessException("锁价单 " + lp.getLockedPriceNo() + " 已作废，不能重复作废");
        }
        Long referenced = batchMapper.selectCount(new QueryWrapper<TransportBatch>()
                .eq("locked_price_id", lp.getId()));
        if (referenced != null && referenced > 0) {
            throw new BusinessException(String.format(
                    "锁价单 %s 已被 %d 个运输批次引用，不能作废（原始报价与快照须保留可追溯）",
                    lp.getLockedPriceNo(), referenced));
        }
        Long pending = adjustMapper.selectCount(new QueryWrapper<QuoteAdjustApproval>()
                .eq("locked_price_id", lp.getId()).eq("status", CcConstants.APPROVAL_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException("锁价单存在待审批改价申请，请先同意或驳回后再作废");
        }
        lp.setStatus(CcConstants.LP_VOIDED);
        lp.setVoidReason(reason);
        lp.setVoidTime(LocalDateTime.now());
        lockedPriceMapper.updateById(lp);
        opLogService.log(CcConstants.ENT_LOCKED_PRICE, lp.getId(), null, CcConstants.ACT_LOCKED_PRICE_VOID,
                "锁价单 " + lp.getLockedPriceNo() + " 作废（快照与审批链全部保留）。原因：" + reason);
        return lp;
    }

    // ---------------- 人工改价审批 ----------------

    /** 发起人工改价：落 PENDING，锁价单转 ADJUSTING，原始金额保留 */
    @Transactional
    public QuoteAdjustApproval applyAdjust(PriceAdjustApplyRequest req) {
        LockedPrice lp = requireLockedPrice(req.getLockedPriceId());
        if (CcConstants.LP_VOIDED.equals(lp.getStatus())) {
            throw new BusinessException("锁价单 " + lp.getLockedPriceNo() + " 已作废，不能改价");
        }
        Long pending = adjustMapper.selectCount(new QueryWrapper<QuoteAdjustApproval>()
                .eq("locked_price_id", lp.getId()).eq("status", CcConstants.APPROVAL_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException("锁价单 " + lp.getLockedPriceNo() + " 已有改价申请停留待审批，不能重复发起");
        }
        BigDecimal taxRate = req.getNewTaxRate();
        if (taxRate == null || taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new BusinessException("改后税率必须在 [0,1) 区间内（如 0.09 表示 9%）");
        }
        PriceMath.Breakdown after = breakdown(req.getNewBaseAmount(), req.getNewSurcharges(), taxRate);

        QuoteAdjustApproval approval = new QuoteAdjustApproval();
        approval.setLockedPriceId(lp.getId());
        approval.setQuoteId(lp.getQuoteId());
        approval.setReason(req.getReason().trim());
        approval.setBeforeBaseAmount(lp.getFinalBaseAmount());
        approval.setBeforeSurchargeAmount(lp.getFinalSurchargeAmount());
        approval.setBeforeSubtotalAmount(lp.getFinalSubtotalAmount());
        approval.setBeforeTaxRate(lp.getFinalTaxRate());
        approval.setBeforeTaxAmount(lp.getFinalTaxAmount());
        approval.setBeforeTotalAmount(lp.getFinalTotalAmount());
        approval.setAfterBaseAmount(after.base);
        approval.setAfterSurchargeAmount(after.surcharge);
        approval.setAfterSubtotalAmount(after.subtotal);
        approval.setAfterTaxRate(after.taxRate);
        approval.setAfterTaxAmount(after.tax);
        approval.setAfterTotalAmount(after.total);
        approval.setStatus(CcConstants.APPROVAL_PENDING);
        approval.setApplyBy(UserContext.currentId());
        approval.setApplyName(UserContext.currentName());
        approval.setApplyTime(LocalDateTime.now());
        adjustMapper.insert(approval);

        // 暂存改后附加费行（adjust_id 指向本申请）；审批同意后成为生效附加费，驳回则保留但不生效
        persistSurcharges(req.getNewSurcharges(), lp.getQuoteId(), lp.getId(), approval.getId());

        lp.setStatus(CcConstants.LP_ADJUSTING);
        lockedPriceMapper.updateById(lp);

        opLogService.log(CcConstants.ENT_QUOTE_ADJUST, approval.getId(), null,
                CcConstants.ACT_PRICE_ADJUST_APPLY,
                String.format("锁价单 %s 发起人工改价申请#%d（审批中，原始报价保留）：含税总价 %s -> %s（基础 %s -> %s，附加费 %s -> %s，税率 %s -> %s）。原因：%s",
                        lp.getLockedPriceNo(), approval.getId(),
                        approval.getBeforeTotalAmount(), approval.getAfterTotalAmount(),
                        approval.getBeforeBaseAmount(), approval.getAfterBaseAmount(),
                        approval.getBeforeSurchargeAmount(), approval.getAfterSurchargeAmount(),
                        QuoteService.percent(approval.getBeforeTaxRate()), QuoteService.percent(after.taxRate),
                        approval.getReason()));
        return approval;
    }

    /** 审批同意：final_* 生效，原始 *_snap 与原附加费快照不动 */
    @Transactional
    public QuoteAdjustApproval approveAdjust(Long approvalId, String reviewNote) {
        return review(approvalId, true, reviewNote);
    }

    /** 审批驳回：锁价单恢复 LOCKED，继续按原金额执行；申请行保留 */
    @Transactional
    public QuoteAdjustApproval rejectAdjust(Long approvalId, String reviewNote) {
        return review(approvalId, false, reviewNote);
    }

    private QuoteAdjustApproval review(Long approvalId, boolean approved, String reviewNote) {
        QuoteAdjustApproval approval = adjustMapper.selectById(approvalId);
        if (approval == null) {
            throw new BusinessException("改价审批单不存在: " + approvalId);
        }
        if (!CcConstants.APPROVAL_PENDING.equals(approval.getStatus())) {
            throw new BusinessException(String.format("改价审批单#%d 当前状态=%s，仅待审批申请可审查",
                    approvalId, approval.getStatus()));
        }
        LockedPrice lp = requireLockedPrice(approval.getLockedPriceId());
        LocalDateTime now = LocalDateTime.now();

        approval.setStatus(approved ? CcConstants.APPROVAL_APPROVED : CcConstants.APPROVAL_REJECTED);
        approval.setReviewBy(UserContext.currentId());
        approval.setReviewName(UserContext.currentName());
        approval.setReviewTime(now);
        approval.setReviewNote(reviewNote.trim());
        adjustMapper.updateById(approval);

        if (approved) {
            lp.setFinalBaseAmount(approval.getAfterBaseAmount());
            lp.setFinalSurchargeAmount(approval.getAfterSurchargeAmount());
            lp.setFinalSubtotalAmount(approval.getAfterSubtotalAmount());
            lp.setFinalTaxRate(approval.getAfterTaxRate());
            lp.setFinalTaxAmount(approval.getAfterTaxAmount());
            lp.setFinalTotalAmount(approval.getAfterTotalAmount());
            lp.setLastAdjustId(approval.getId());
            lp.setStatus(CcConstants.LP_LOCKED);
            lockedPriceMapper.updateById(lp);
        } else {
            // 驳回：不更新任何 final_*，锁价单恢复按原金额执行
            lp.setStatus(CcConstants.LP_VOIDED.equals(lp.getStatus())
                    ? CcConstants.LP_VOIDED : CcConstants.LP_LOCKED);
            lockedPriceMapper.updateById(lp);
        }

        opLogService.log(CcConstants.ENT_QUOTE_ADJUST, approval.getId(), null,
                approved ? CcConstants.ACT_PRICE_ADJUST_APPROVE : CcConstants.ACT_PRICE_ADJUST_REJECT,
                String.format("锁价单 %s 改价申请#%d 审批%s：审批人=%s，意见=%s%s",
                        lp.getLockedPriceNo(), approval.getId(), approved ? "同意，新金额已生效（原始报价快照保留）" : "驳回，继续按原金额执行",
                        approval.getReviewName(), approval.getReviewNote(),
                        approved ? String.format("；生效含税总价=%s", approval.getAfterTotalAmount()) : ""));
        return approval;
    }

    // ---------------- 查询 / 反查 / 卡口 ----------------

    public LockedPrice requireLockedPrice(Long lockedPriceId) {
        LockedPrice lp = lockedPriceId == null ? null : lockedPriceMapper.selectById(lockedPriceId);
        if (lp == null) {
            throw new BusinessException("锁价单不存在: " + lockedPriceId);
        }
        return lp;
    }

    /**
     * 派车卡口：冷链批次派车必须引用一张有效锁价单（存在、未作废、无改价审批在途）。
     */
    public LockedPrice requireLockedForDispatch(Long lockedPriceId) {
        if (lockedPriceId == null) {
            throw new BusinessException("冷链运输派车必须引用锁价单（lockedPriceId 不能为空）：请先完成承运商报价比选并锁价");
        }
        LockedPrice lp = requireLockedPrice(lockedPriceId);
        if (CcConstants.LP_VOIDED.equals(lp.getStatus())) {
            throw new BusinessException("锁价单 " + lp.getLockedPriceNo() + " 已作废，不能用于派车");
        }
        if (CcConstants.LP_ADJUSTING.equals(lp.getStatus())) {
            throw new BusinessException(String.format(
                    "锁价单 %s 有人工改价申请停留待审批，审批完成前不能派车", lp.getLockedPriceNo()));
        }
        return lp;
    }

    /** 锁价单详情（含快照、生效金额、原始/生效附加费、审批链） */
    public Map<String, Object> detail(Long lockedPriceId) {
        LockedPrice lp = requireLockedPrice(lockedPriceId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("lockedPrice", lp);
        detail.put("originalQuote", quoteMapper.selectById(lp.getQuoteId()));
        detail.put("carrier", carrierMapper.selectById(lp.getCarrierId()));
        detail.put("quoteBatch", quoteBatchMapper.selectById(lp.getQuoteBatchId()));
        detail.put("lockedSurcharges", listLockedSurcharges(lp.getId(), null));
        detail.put("adjustments", listAdjustments(lp.getId()));
        detail.put("adjustmentSurcharges", adjustmentSurchargeMap(lp.getId()));
        return detail;
    }

    /**
     * 锁价单反查：沿 锁价单 -> 中选原单（含历版）-> 报价批次 -> 承运商 -> 改价审批链，
     * 定位原单、各环节处理人及异常原因（过期/落选/修订替换/改价驳回）。
     */
    public Map<String, Object> trace(Long lockedPriceId) {
        LockedPrice lp = requireLockedPrice(lockedPriceId);
        CarrierQuote chosen = quoteMapper.selectById(lp.getQuoteId());
        QuoteBatch batch = quoteBatchMapper.selectById(lp.getQuoteBatchId());
        Carrier carrier = carrierMapper.selectById(lp.getCarrierId());
        List<CarrierQuote> versions = quoteMapper.selectList(new QueryWrapper<CarrierQuote>()
                .eq("quote_batch_id", lp.getQuoteBatchId())
                .eq("carrier_id", lp.getCarrierId()).orderByAsc("version_no"));
        List<CarrierQuote> allQuotes = quoteMapper.selectList(new QueryWrapper<CarrierQuote>()
                .eq("quote_batch_id", lp.getQuoteBatchId()).orderByAsc("id"));
        List<QuoteAdjustApproval> adjustments = listAdjustments(lp.getId());

        List<Map<String, Object>> handlers = new ArrayList<>();
        List<String> anomalyReasons = new ArrayList<>();

        handlers.add(node("QUOTE_BATCH_OPEN", "报价批次发起", null,
                batch.getCreateTime(), null));
        for (CarrierQuote q : allQuotes) {
            handlers.add(node("QUOTE_SUBMIT", String.format("承运商[%s]报价 V%d",
                    q.getCarrierNameSnap(), q.getVersionNo()), q.getSubmittedByName(),
                    q.getSubmitTime(), q.getStatus()));
            if (CcConstants.QUOTE_SUPERSEDED.equals(q.getStatus())) {
                anomalyReasons.add(String.format("承运商[%s]报价 V%d 已被其修订新版本替换（SUPERSEDED，原值保留）",
                        q.getCarrierNameSnap(), q.getVersionNo()));
            }
            if (CcConstants.QUOTE_REJECTED.equals(q.getStatus())) {
                anomalyReasons.add(String.format("承运商[%s]报价 V%d 未中选（落选 REJECTED），中选方=%s",
                        q.getCarrierNameSnap(), q.getVersionNo(), lp.getCarrierNameSnap()));
            }
            if (CcConstants.QUOTE_EXPIRED.equals(q.getStatus())) {
                anomalyReasons.add(String.format("承运商[%s]报价 V%d 随报价批次 %s 过期失效（EXPIRED，截止 %s）",
                        q.getCarrierNameSnap(), q.getVersionNo(),
                        batch.getQuoteBatchNo(), batch.getQuoteExpireTime()));
            }
        }
        if (CcConstants.QB_EXPIRED.equals(batch.getStatus())) {
            anomalyReasons.add(String.format("报价批次 %s 已过期（截止 %s），过期报价不能被新批次引用",
                    batch.getQuoteBatchNo(), batch.getQuoteExpireTime()));
        }
        handlers.add(node("QUOTE_LOCK", String.format("锁价单 %s 中选[%s]报价 V%d",
                lp.getLockedPriceNo(), lp.getCarrierNameSnap(), lp.getQuoteVersionNo()),
                lp.getLockedByName(), lp.getLockTime(), lp.getStatus()));
        for (QuoteAdjustApproval a : adjustments) {
            handlers.add(node("PRICE_ADJUST_" + a.getStatus(),
                    String.format("改价申请#%d（%s）：含税总价 %s -> %s", a.getId(), a.getStatus(),
                            a.getBeforeTotalAmount(), a.getAfterTotalAmount()),
                    CcConstants.APPROVAL_APPROVED.equals(a.getStatus())
                            || CcConstants.APPROVAL_REJECTED.equals(a.getStatus())
                            ? a.getReviewName() : a.getApplyName(),
                    CcConstants.APPROVAL_PENDING.equals(a.getStatus()) ? a.getApplyTime() : a.getReviewTime(),
                    a.getStatus()));
            if (CcConstants.APPROVAL_REJECTED.equals(a.getStatus())) {
                anomalyReasons.add(String.format("改价申请#%d 被审批驳回：%s（原始报价保留，继续按原金额执行）",
                        a.getId(), a.getReviewNote()));
            }
        }
        if (CcConstants.LP_VOIDED.equals(lp.getStatus())) {
            anomalyReasons.add("锁价单 " + lp.getLockedPriceNo() + " 已作废：" + lp.getVoidReason());
        }

        // 引用该锁价单的运输批次（反查去向）
        List<TransportBatch> refBatches = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .eq("locked_price_id", lp.getId()).orderByAsc("id"));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("lockedPrice", lp);
        trace.put("originalQuote", chosen);
        trace.put("quoteBatch", batch);
        trace.put("carrier", carrier);
        trace.put("carrierQuoteVersions", versions);
        trace.put("allBatchQuotes", allQuotes);
        trace.put("lockedSurcharges", listLockedSurcharges(lp.getId(), null));
        trace.put("adjustments", adjustments);
        trace.put("adjustmentSurcharges", adjustmentSurchargeMap(lp.getId()));
        trace.put("referencedBatches", refBatches);
        trace.put("handlers", handlers);
        trace.put("anomalyReasons", anomalyReasons);
        return trace;
    }

    /** 从运输批次反查锁价与原报价（台账调用） */
    public Map<String, Object> traceByBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("batchNo", batch.getBatchNo());
        if (batch.getLockedPriceId() == null) {
            result.put("locked", false);
            result.put("message", "该批次未引用锁价单");
            return result;
        }
        result.put("locked", true);
        result.put("trace", trace(batch.getLockedPriceId()));
        return result;
    }

    public List<LockedPrice> listByCarrier(Long carrierId) {
        return lockedPriceMapper.selectList(new QueryWrapper<LockedPrice>()
                .eq(carrierId != null, "carrier_id", carrierId)
                .orderByDesc("id"));
    }

    public List<QuoteAdjustApproval> listPendingAdjustments() {
        return adjustMapper.selectList(new QueryWrapper<QuoteAdjustApproval>()
                .eq("status", CcConstants.APPROVAL_PENDING).orderByAsc("id"));
    }

    // ---------------- 内部工具 ----------------

    private List<QuoteSurcharge> listQuoteOriginalSurcharges(Long quoteId) {
        return surchargeMapper.selectList(new QueryWrapper<QuoteSurcharge>()
                .eq("quote_id", quoteId).isNull("locked_price_id").orderByAsc("id"));
    }

    private List<QuoteSurcharge> listLockedSurcharges(Long lockedPriceId, Long adjustId) {
        QueryWrapper<QuoteSurcharge> qw = new QueryWrapper<QuoteSurcharge>()
                .eq("locked_price_id", lockedPriceId).orderByAsc("id");
        if (adjustId == null) {
            qw.isNull("adjust_id");
        } else {
            qw.eq("adjust_id", adjustId);
        }
        return surchargeMapper.selectList(qw);
    }

    private List<QuoteAdjustApproval> listAdjustments(Long lockedPriceId) {
        return adjustMapper.selectList(new QueryWrapper<QuoteAdjustApproval>()
                .eq("locked_price_id", lockedPriceId).orderByAsc("id"));
    }

    /** 每次改价申请的附加费行（含被驳回未生效的，按 adjustId 分组，便于反查） */
    private Map<Long, List<QuoteSurcharge>> adjustmentSurchargeMap(Long lockedPriceId) {
        List<QuoteSurcharge> rows = surchargeMapper.selectList(new QueryWrapper<QuoteSurcharge>()
                .eq("locked_price_id", lockedPriceId).isNotNull("adjust_id").orderByAsc("id"));
        Map<Long, List<QuoteSurcharge>> map = new LinkedHashMap<>();
        for (QuoteSurcharge row : rows) {
            map.computeIfAbsent(row.getAdjustId(), k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    private void persistSurcharges(List<SurchargeItem> items, Long quoteId, Long lockedPriceId, Long adjustId) {
        if (items == null) {
            return;
        }
        for (SurchargeItem item : items) {
            if (item.getAmount().signum() < 0) {
                throw new BusinessException("附加费金额不能为负: " + item.getSurchargeType());
            }
            QuoteSurcharge row = new QuoteSurcharge();
            row.setQuoteId(quoteId);
            row.setLockedPriceId(lockedPriceId);
            row.setAdjustId(adjustId);
            row.setSurchargeType(item.getSurchargeType().trim().toUpperCase());
            row.setTypeName(QuoteDimensions.surchargeTypeName(item.getSurchargeType(), item.getTypeName()));
            row.setAmount(PriceMath.money(item.getAmount()));
            row.setRemark(item.getRemark());
            surchargeMapper.insert(row);
        }
    }

    private PriceMath.Breakdown breakdown(BigDecimal base, List<SurchargeItem> items, BigDecimal taxRate) {
        if (base == null || base.signum() < 0) {
            throw new BusinessException("改后基础运费必须为非负数");
        }
        BigDecimal sum = BigDecimal.ZERO.setScale(PriceMath.MONEY_SCALE, java.math.RoundingMode.HALF_UP);
        if (items != null) {
            for (SurchargeItem item : items) {
                sum = sum.add(PriceMath.money(item.getAmount()));
            }
        }
        return new PriceMath.Breakdown(base, sum, taxRate);
    }

    private Map<String, Object> node(String nodeType, String desc, String handler,
                                     LocalDateTime time, String status) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("node", nodeType);
        n.put("description", desc);
        n.put("handler", handler);
        n.put("time", time);
        n.put("status", status);
        return n;
    }

    /** 锁价单号：SJ + 9 位序列 */
    private String generateLockedPriceNo() {
        return String.format("SJ%09d", sequenceMapper.nextLockedPriceNo());
    }
}
