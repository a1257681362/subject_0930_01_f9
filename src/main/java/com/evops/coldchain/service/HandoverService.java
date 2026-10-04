package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ArriveWarehouseRequest;
import com.evops.coldchain.dto.HandoverCreateRequest;
import com.evops.coldchain.dto.SealVerifyRequest;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSeal;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.HandoverOrderMapper;
import com.evops.coldchain.mapper.HandoverSealMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链批次分仓交接服务。
 *
 * 业务规则：
 * - 一车货（一个在途批次）到中转仓先登记“到仓总箱数”，作为拆单的箱数平衡基准。
 * - 一车货可拆成多个交接单：每单记录箱数、逐箱封签号、交接温度、接收人；
 *   封签数必须等于箱数，封签号在同一批次范围内唯一（即使其所在单后被作废，号仍占用、事实保留）。
 * - 封签逐条核验：PENDING -> CONFIRMED 只进不退；单内封签全部核验通过才允许提交。
 * - 箱数平衡：非作废交接单箱数合计在建单时不得超过到仓总箱数，提交时必须正好等于到仓总箱数；
 *   箱数不平给出差额/未交箱数并禁止提交。
 * - 提交即责任主体随单转移：单状态置 SUBMITTED 并冻结，责任由原承运批次转给接收人/接收机构。
 * - 未完成核验的箱子不能继续派送：全部到仓箱数都已分到交接单且所在单均 SUBMITTED 才放行签收。
 * - 权限收紧：建单/核验/提交/作废等一切推进动作在 COLD_CHAIN_HANDOVER 被收紧时停止，
 *   但已确认的封签核验事实原样保留、仍可查询。
 *
 * 本服务只依赖 Mapper（不依赖 TransportBatchService），与批次服务保持单向依赖，避免循环引用。
 */
@Service
public class HandoverService {

    @Resource
    private HandoverOrderMapper handoverMapper;
    @Resource
    private HandoverSealMapper sealMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private PermissionService permissionService;
    @Resource
    private ExceptionLifecycleService exceptionLifecycleService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 到仓登记 ----------------

    /** 一车货到达中转仓：登记到仓总箱数（分箱平衡基准），只登记一次 */
    @Transactional
    public TransportBatch arriveWarehouse(ArriveWarehouseRequest req) {
        permissionService.requirePermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        TransportBatch batch = requireHandoverBatch(req.getBatchId());
        if (batch.getArrivedBoxQty() != null) {
            throw new BusinessException(String.format(
                    "批次 %s 已登记到仓总箱数 %d，到仓事实不能重复登记/修改",
                    batch.getBatchNo(), batch.getArrivedBoxQty()));
        }
        batch.setArrivedBoxQty(req.getArrivedBoxQty());
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_ARRIVE,
                String.format("批次 %s 到达中转仓%s：到仓总箱数=%d（作为分仓交接箱数平衡基准），当前责任主体=%s/%s",
                        batch.getBatchNo(),
                        req.getWarehouseName() == null ? "" : "（" + req.getWarehouseName() + "）",
                        req.getArrivedBoxQty(), nz(batch.getResponsibleOrg()), nz(batch.getResponsibleParty())));
        return batch;
    }

    // ---------------- 拆单建单 ----------------

    /**
     * 建一个交接单（可连续建多个完成拆箱）。校验箱数、封签数/重复、交接温度与接收人，
     * 并保证累计箱数不超过到仓总箱数。
     */
    @Transactional
    public Map<String, Object> createHandover(HandoverCreateRequest req) {
        permissionService.requirePermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        TransportBatch batch = requireHandoverBatch(req.getBatchId());
        if (batch.getArrivedBoxQty() == null) {
            throw new BusinessException(String.format(
                    "批次 %s 尚未登记到仓总箱数，请先办理到仓登记后再拆分交接单", batch.getBatchNo()));
        }

        List<String> sealNos = normalizeSealNos(req.getSealNos());

        // 1) 单内：箱数必须与封签数一致（逐箱封签）
        if (sealNos.size() != req.getBoxQty()) {
            throw new BusinessException(String.format(
                    "箱数不平：本单箱数=%d，但登记封签数=%d（必须逐箱封签，一箱一签）",
                    req.getBoxQty(), sealNos.size()));
        }
        // 2) 交接温度、接收人缺失禁止提交（建单即记录，缺失直接拒绝）
        if (req.getHandoverTemp() == null) {
            throw new BusinessException("交接温度缺失：必须记录交接温度才能建单/提交");
        }
        if (isBlank(req.getReceiverName()) || isBlank(req.getReceiverOrg())) {
            throw new BusinessException("接收人/接收责任主体缺失：必须记录接收人才能建单/提交");
        }

        // 3) 封签号在同批次范围内（含历史/作废单）唯一；单内也不得重复
        List<String> dupInside = duplicateWithin(sealNos);
        if (!dupInside.isEmpty()) {
            throw new BusinessException("封签重复：本单内封签号重复 " + dupInside);
        }
        for (String sealNo : sealNos) {
            HandoverSeal conflict = sealMapper.selectOne(new QueryWrapper<HandoverSeal>()
                    .eq("batch_id", batch.getId())
                    .eq("seal_no", sealNo)
                    .last("LIMIT 1"));
            if (conflict != null) {
                HandoverOrder owner = handoverMapper.selectById(conflict.getHandoverId());
                throw new BusinessException(String.format(
                        "封签重复：封签号[%s]已在批次 %s 的交接单 %s 中登记（第%d签），不能重复使用",
                        sealNo, batch.getBatchNo(),
                        owner == null ? "#" + conflict.getHandoverId() : owner.getHandoverNo(),
                        conflict.getSealSeq()));
            }
        }

        // 4) 箱数平衡：非作废单累计箱数 + 本单 不得超过到仓总箱数
        int allocatedBefore = sumNonVoidedBoxes(batch.getId());
        int allocatedAfter = allocatedBefore + req.getBoxQty();
        if (allocatedAfter > batch.getArrivedBoxQty()) {
            throw new BusinessException(String.format(
                    "箱数不平：到仓总箱数=%d，此前已分箱=%d，本单=%d，合计=%d 超出 %d 箱；请核减本单箱数",
                    batch.getArrivedBoxQty(), allocatedBefore, req.getBoxQty(),
                    allocatedAfter, allocatedAfter - batch.getArrivedBoxQty()));
        }

        LocalDateTime now = LocalDateTime.now();
        HandoverOrder order = new HandoverOrder();
        order.setHandoverNo(generateHandoverNo());
        order.setBatchId(batch.getId());
        order.setWarehouseName(req.getWarehouseName().trim());
        order.setBoxQty(req.getBoxQty());
        order.setSealQty(sealNos.size());
        order.setHandoverTemp(req.getHandoverTemp());
        order.setReceiverName(req.getReceiverName().trim());
        order.setReceiverOrg(req.getReceiverOrg().trim());
        order.setHandoverTime(req.getHandoverTime() == null ? now : req.getHandoverTime());
        order.setStatus(CcConstants.HANDOVER_DRAFT);
        // 提交前责任主体仍为原承运批次；提交时随单转移给接收方
        order.setResponsibleOrg(batch.getResponsibleOrg());
        order.setResponsibleParty(batch.getResponsibleParty());
        order.setRemark(req.getRemark());
        handoverMapper.insert(order);

        List<HandoverSeal> seals = new ArrayList<>();
        int seq = 1;
        for (String sealNo : sealNos) {
            HandoverSeal seal = new HandoverSeal();
            seal.setHandoverId(order.getId());
            seal.setBatchId(batch.getId());
            seal.setSealSeq(seq++);
            seal.setSealNo(sealNo);
            seal.setVerifyStatus(CcConstants.SEAL_PENDING);
            sealMapper.insert(seal);
            seals.add(seal);
        }

        int remaining = batch.getArrivedBoxQty() - allocatedAfter;
        opLogService.log(CcConstants.ENT_HANDOVER, order.getId(), batch.getId(), CcConstants.ACT_HANDOVER_CREATE,
                String.format("批次 %s 在 %s 拆出交接单 %s：箱数=%d，封签=%d（待逐签核验），交接温度=%s℃，接收人=%s（%s）；"
                                + "已分箱合计=%d/%d，剩余未分=%d；责任主体暂仍为 %s/%s，提交后随单转移",
                        batch.getBatchNo(), order.getWarehouseName(), order.getHandoverNo(),
                        order.getBoxQty(), order.getSealQty(), order.getHandoverTemp(),
                        order.getReceiverName(), order.getReceiverOrg(),
                        allocatedAfter, batch.getArrivedBoxQty(), remaining,
                        nz(batch.getResponsibleOrg()), nz(batch.getResponsibleParty())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("seals", seals);
        result.put("arrivedBoxQty", batch.getArrivedBoxQty());
        result.put("allocatedBoxQty", allocatedAfter);
        result.put("remainingBoxQty", remaining);
        return result;
    }

    // ---------------- 封签核验 ----------------

    /** 逐条核验封签：PENDING -> CONFIRMED 只进不退；已确认事实不可重复核验/改写 */
    @Transactional
    public HandoverSeal verifySeal(SealVerifyRequest req) {
        permissionService.requirePermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        HandoverSeal seal = requireSeal(req.getSealId());
        HandoverOrder order = requireOrder(seal.getHandoverId());
        if (CcConstants.HANDOVER_VOIDED.equals(order.getStatus())) {
            throw new BusinessException(String.format(
                    "交接单 %s 已作废，不能再核验其封签（已登记封签事实保留）", order.getHandoverNo()));
        }
        if (CcConstants.HANDOVER_SUBMITTED.equals(order.getStatus())) {
            throw new BusinessException(String.format(
                    "交接单 %s 已提交（责任已转移），封签核验已冻结", order.getHandoverNo()));
        }
        if (CcConstants.SEAL_CONFIRMED.equals(seal.getVerifyStatus())) {
            throw new BusinessException(String.format(
                    "封签[%s]已于 %s 由 %s 核验确认（%s），已确认事实保留，不能重复核验或改写",
                    seal.getSealNo(), seal.getVerifyTime(), seal.getVerifiedBy(), nz(seal.getVerifyNote())));
        }

        boolean brokenSeal = Boolean.TRUE.equals(req.getBroken());
        LocalDateTime verifyTime = LocalDateTime.now();
        seal.setVerifyStatus(CcConstants.SEAL_CONFIRMED);
        seal.setVerifyNote(brokenSeal ? ("【封签破损】" + req.getVerifyNote()) : req.getVerifyNote());
        seal.setVerifiedBy(isBlank(req.getVerifiedBy()) ? UserContext.currentName() : req.getVerifiedBy().trim());
        seal.setVerifyTime(verifyTime);
        sealMapper.updateById(seal);

        long confirmed = countSeals(order.getId(), CcConstants.SEAL_CONFIRMED);
        opLogService.log(CcConstants.ENT_SEAL, seal.getId(), order.getBatchId(), CcConstants.ACT_SEAL_VERIFY,
                String.format("交接单 %s 第%d签[%s]核验确认：%s（核验人=%s%s）；本单进度 %d/%d",
                        order.getHandoverNo(), seal.getSealSeq(), seal.getSealNo(),
                        seal.getVerifyNote(), seal.getVerifiedBy(),
                        brokenSeal ? "，结论=封签破损" : "", confirmed, order.getSealQty()));

        // V6：核验发现封签破损——破损是客观事实，核验确认即异常成立（SEAL_BROKEN 自动确认启钟，
        // 时钟起点=核验时刻，foundTime 不接受任何后续补录覆盖）
        if (brokenSeal) {
            TransportBatch sealBatch = batchMapper.selectById(order.getBatchId());
            if (sealBatch != null && !CcConstants.BATCH_REVOKED.equals(sealBatch.getStatus())
                    && !CcConstants.BATCH_SIGNED.equals(sealBatch.getStatus())) {
                String desc = String.format("交接单 %s 第%d签[%s]核验确认封签破损：%s（核验人=%s，核验时刻=%s）",
                        order.getHandoverNo(), seal.getSealSeq(), seal.getSealNo(),
                        req.getVerifyNote(), seal.getVerifiedBy(), verifyTime);
                exceptionLifecycleService.registerAutoConfirmed(sealBatch, CcConstants.EXC_SEAL_BROKEN,
                        desc, verifyTime, null, null);
            }
        }
        return seal;
    }

    // ---------------- 提交（责任转移） ----------------

    /**
     * 提交交接单：单内封签必须全部核验、非作废单箱数合计必须正好等于到仓总箱数（箱数平衡）。
     * 通过后状态 SUBMITTED 冻结，责任主体由原承运批次随单转移给接收方。
     */
    @Transactional
    public HandoverOrder submit(Long handoverId) {
        permissionService.requirePermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        HandoverOrder order = requireOrder(handoverId);
        TransportBatch batch = requireHandoverBatch(order.getBatchId());

        if (CcConstants.HANDOVER_SUBMITTED.equals(order.getStatus())) {
            throw new BusinessException(String.format("交接单 %s 已提交，责任已随单转移，不能重复提交", order.getHandoverNo()));
        }
        if (CcConstants.HANDOVER_VOIDED.equals(order.getStatus())) {
            throw new BusinessException(String.format("交接单 %s 已作废，不能提交", order.getHandoverNo()));
        }

        // 1) 单内封签必须全部核验
        List<HandoverSeal> seals = listSeals(handoverId);
        List<String> pending = new ArrayList<>();
        for (HandoverSeal s : seals) {
            if (!CcConstants.SEAL_CONFIRMED.equals(s.getVerifyStatus())) {
                pending.add(s.getSealNo());
            }
        }
        if (!pending.isEmpty()) {
            throw new BusinessException(String.format(
                    "交接单 %s 尚有 %d 个封签未完成核验（%s），未完成核验的箱子不能提交/继续派送",
                    order.getHandoverNo(), pending.size(), pending));
        }

        // 2) 箱数平衡：非作废单箱数合计必须正好等于到仓总箱数
        int allocated = sumNonVoidedBoxes(batch.getId());
        if (allocated != batch.getArrivedBoxQty()) {
            int remaining = batch.getArrivedBoxQty() - allocated;
            throw new BusinessException(String.format(
                    "箱数不平，禁止提交：到仓总箱数=%d，当前有效交接单合计=%d，%s%d 箱尚未分到交接单；请先补齐交接单再提交",
                    batch.getArrivedBoxQty(), allocated,
                    remaining > 0 ? "还差 " : "超出 ", Math.abs(remaining)));
        }

        // 3) 责任主体随单转移（单上冻结转移结果）
        String fromOrg = batch.getResponsibleOrg();
        String fromParty = batch.getResponsibleParty();
        order.setStatus(CcConstants.HANDOVER_SUBMITTED);
        order.setSubmitTime(LocalDateTime.now());
        order.setResponsibleOrg(order.getReceiverOrg());
        order.setResponsibleParty(order.getReceiverName());
        handoverMapper.updateById(order);

        // 批次当前责任主体同步到接收方（多单分别接收时，以最近一次提交为准；逐单责任以交接单快照为准）
        batch.setResponsibleOrg(order.getReceiverOrg());
        batch.setResponsibleParty(order.getReceiverName());
        batchMapper.updateById(batch);

        // 其余未提交单（仍在核验中）的箱子还不能继续派送
        int submittedBoxes = sumBoxesByStatus(batch.getId(), CcConstants.HANDOVER_SUBMITTED);
        opLogService.log(CcConstants.ENT_HANDOVER, order.getId(), batch.getId(), CcConstants.ACT_HANDOVER_SUBMIT,
                String.format("交接单 %s 提交：%d 箱、%d 封签全部核验通过，交接温度=%s℃；责任主体由 %s/%s 随单转移给 %s/%s。"
                                + "批次 %s 已提交交接 %d/%d 箱，其余箱未完成核验前不得继续派送",
                        order.getHandoverNo(), order.getBoxQty(), order.getSealQty(), order.getHandoverTemp(),
                        nz(fromOrg), nz(fromParty), order.getReceiverOrg(), order.getReceiverName(),
                        batch.getBatchNo(), submittedBoxes, batch.getArrivedBoxQty()));
        return order;
    }

    // ---------------- 作废（仅状态，事实保留） ----------------

    /** 作废草稿交接单：仅 DRAFT 可作废；封签与核验记录、操作日志全部保留，释放其箱数占用 */
    @Transactional
    public HandoverOrder voidHandover(Long handoverId, String reason) {
        permissionService.requirePermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        HandoverOrder order = requireOrder(handoverId);
        if (CcConstants.HANDOVER_SUBMITTED.equals(order.getStatus())) {
            throw new BusinessException(String.format(
                    "交接单 %s 已提交、责任已随单转移，不能作废（责任转移不可撤销）", order.getHandoverNo()));
        }
        if (CcConstants.HANDOVER_VOIDED.equals(order.getStatus())) {
            throw new BusinessException(String.format("交接单 %s 已作废，不能重复作废", order.getHandoverNo()));
        }
        order.setStatus(CcConstants.HANDOVER_VOIDED);
        order.setVoidReason(reason);
        order.setVoidTime(LocalDateTime.now());
        handoverMapper.updateById(order);

        opLogService.log(CcConstants.ENT_HANDOVER, order.getId(), order.getBatchId(), CcConstants.ACT_HANDOVER_VOID,
                String.format("交接单 %s 作废（仅置作废状态，%d 条封签/核验记录与日志原样保留；释放箱数占用 %d）。原因：%s",
                        order.getHandoverNo(), order.getSealQty(), order.getBoxQty(), reason));
        return order;
    }

    // ---------------- 查询 / 进度卡口 ----------------

    public HandoverOrder requireOrder(Long handoverId) {
        HandoverOrder order = handoverMapper.selectById(handoverId);
        if (order == null) {
            throw new BusinessException("交接单不存在: " + handoverId);
        }
        return order;
    }

    public HandoverSeal requireSeal(Long sealId) {
        HandoverSeal seal = sealMapper.selectById(sealId);
        if (seal == null) {
            throw new BusinessException("封签明细不存在: " + sealId);
        }
        return seal;
    }

    public List<HandoverSeal> listSeals(Long handoverId) {
        return sealMapper.selectList(new QueryWrapper<HandoverSeal>()
                .eq("handover_id", handoverId)
                .orderByAsc("seal_seq"));
    }

    public List<HandoverOrder> listByBatch(Long batchId) {
        return handoverMapper.selectList(new QueryWrapper<HandoverOrder>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }

    /** 交接单详情：单头 + 封签核验明细 */
    public Map<String, Object> detail(Long handoverId) {
        HandoverOrder order = requireOrder(handoverId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("seals", listSeals(handoverId));
        return data;
    }

    /**
     * 批次分仓交接进度（用于台账与“能否继续派送”判定）。
     * readyForDispatch=TRUE 当且仅当：已登记到仓箱数、有效交接单箱数合计=到仓总箱数、
     * 所有有效交接单均已 SUBMITTED（即每箱封签都已核验）。
     */
    public Map<String, Object> progress(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        List<HandoverOrder> orders = listByBatch(batchId);

        int allocated = 0, submittedBoxes = 0, draftBoxes = 0, voidedBoxes = 0;
        int sealTotal = 0, sealConfirmed = 0;
        for (HandoverOrder o : orders) {
            List<HandoverSeal> seals = listSeals(o.getId());
            switch (o.getStatus()) {
                case CcConstants.HANDOVER_VOIDED:
                    voidedBoxes += o.getBoxQty();
                    continue; // 作废单不参与箱数平衡，但其封签事实仍保留
                case CcConstants.HANDOVER_SUBMITTED:
                    submittedBoxes += o.getBoxQty();
                    break;
                default:
                    draftBoxes += o.getBoxQty();
            }
            allocated += o.getBoxQty();
            sealTotal += seals.size();
            for (HandoverSeal s : seals) {
                if (CcConstants.SEAL_CONFIRMED.equals(s.getVerifyStatus())) {
                    sealConfirmed++;
                }
            }
        }

        Integer arrived = batch.getArrivedBoxQty();
        boolean arrivedRegistered = arrived != null;
        boolean balanced = arrivedRegistered && allocated == arrived;
        boolean allSubmitted = arrivedRegistered && allocated > 0 && allocated == submittedBoxes;
        boolean ready = balanced && allSubmitted && arrived > 0;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("arrivedRegistered", arrivedRegistered);
        p.put("arrivedBoxQty", arrived);
        p.put("allocatedBoxQty", allocated);
        p.put("submittedBoxQty", submittedBoxes);
        p.put("draftBoxQty", draftBoxes);
        p.put("voidedBoxQty", voidedBoxes);
        p.put("remainingBoxQty", arrivedRegistered ? arrived - allocated : null);
        p.put("sealTotal", sealTotal);
        p.put("sealConfirmed", sealConfirmed);
        p.put("balanced", balanced);
        p.put("allSubmitted", allSubmitted);
        p.put("readyForDispatch", ready);
        return p;
    }

    /** 签收/继续派送卡口：未完成核验或箱数不平衡时给出具体原因并拒绝 */
    public void requireReadyForDispatch(Long batchId) {
        Map<String, Object> p = progress(batchId);
        if (Boolean.TRUE.equals(p.get("readyForDispatch"))) {
            return;
        }
        Integer arrived = (Integer) p.get("arrivedBoxQty");
        if (arrived == null) {
            throw new BusinessException("该批次尚未办理到仓登记与分仓交接，未完成核验的箱子不能继续派送/签收");
        }
        int allocated = (Integer) p.get("allocatedBoxQty");
        int submitted = (Integer) p.get("submittedBoxQty");
        int confirmed = (Integer) p.get("sealConfirmed");
        int sealTotal = (Integer) p.get("sealTotal");
        if (allocated != arrived) {
            throw new BusinessException(String.format(
                    "箱数不平，不能继续派送：到仓 %d 箱，有效交接单合计 %d 箱，还差 %d 箱未交接",
                    arrived, allocated, arrived - allocated));
        }
        if (submitted != allocated) {
            throw new BusinessException(String.format(
                    "尚有 %d 箱所在交接单未提交（%d 个封签已核验/%d 个），未完成核验的箱子不能继续派送",
                    allocated - submitted, confirmed, sealTotal));
        }
        throw new BusinessException("分仓交接尚未全部完成，不能继续派送");
    }

    /**
     * 条件卡口：仅当批次已进入分仓交接流程（已登记到仓总箱数）时才要求交接全部完成；
     * 未走中转仓拆分的直发批次（V1~V3 口径）不受此卡口影响。
     */
    public void requireReadyForDispatchIfStarted(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch != null && batch.getArrivedBoxQty() != null) {
            requireReadyForDispatch(batchId);
        }
    }

    // ---------------- 私有工具 ----------------

    private TransportBatch requireHandoverBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能办理分仓交接: " + batch.getBatchNo());
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能再办理分仓交接: " + batch.getBatchNo());
        }
        if (CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException(String.format(
                    "批次 %s 尚未发运在途，不能办理到仓/分仓交接", batch.getBatchNo()));
        }
        // 允许 SHIPPED / EXCEPTION（曾越限挂起）批次办理交接
        return batch;
    }

    /** 非作废交接单箱数合计（箱数平衡口径） */
    private int sumNonVoidedBoxes(Long batchId) {
        return sumBoxesByStatus(batchId, CcConstants.HANDOVER_DRAFT)
                + sumBoxesByStatus(batchId, CcConstants.HANDOVER_SUBMITTED);
    }

    private int sumBoxesByStatus(Long batchId, String status) {
        List<HandoverOrder> rows = handoverMapper.selectList(new QueryWrapper<HandoverOrder>()
                .eq("batch_id", batchId)
                .eq("status", status));
        int sum = 0;
        for (HandoverOrder o : rows) {
            sum += o.getBoxQty();
        }
        return sum;
    }

    private long countSeals(Long handoverId, String status) {
        Long n = sealMapper.selectCount(new QueryWrapper<HandoverSeal>()
                .eq("handover_id", handoverId)
                .eq("verify_status", status));
        return n == null ? 0 : n;
    }

    private List<String> normalizeSealNos(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private List<String> duplicateWithin(List<String> sealNos) {
        List<String> dup = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String s : sealNos) {
            if (seen.contains(s) && !dup.contains(s)) {
                dup.add(s);
            }
            seen.add(s);
        }
        return dup;
    }

    /** 稳定交接单号：JJ + 8 位序列 */
    private String generateHandoverNo() {
        return String.format("JJ%08d", sequenceMapper.nextHandoverNo());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
