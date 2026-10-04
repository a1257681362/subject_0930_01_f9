package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ApprovalApplyRequest;
import com.evops.coldchain.entity.InspectionApproval;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.InspectionApprovalMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 冷链药品运输审批服务（V7，两步流程）。
 *
 * - 第一步申请：落 PENDING（待审批，只完成一半，系统停留待处理）。
 * - 第二步审批：同意 -> APPROVED（批次方可发运）；驳回 -> REJECTED（停留，可重新申请）。
 * - 同一批次同时至多一条 PENDING；已经同意的不可重复审批。
 * - 审批进行期间抽检任务立项、样本封存、双人转移照常办理、全程可追溯（审批不冻结抽检）。
 *
 * 仅依赖 Mapper，不依赖 TransportBatchService，避免与批次服务循环引用。
 */
@Service
public class ApprovalService {

    @Resource
    private InspectionApprovalMapper approvalMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private InspectorRegionService regionService;
    @Resource
    private OpLogService opLogService;

    /** 第一步：发起运输审批申请，停留 PENDING 待处理 */
    @Transactional
    public InspectionApproval apply(ApprovalApplyRequest req) {
        TransportBatch batch = requireBatch(req.getBatchId());
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能发起运输审批: " + batch.getBatchNo());
        }
        if (!CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException(String.format(
                    "仅已派车未发运的批次需要运输审批，批次 %s 当前状态=%s", batch.getBatchNo(), batch.getStatus()));
        }
        InspectionApproval latest = latestByBatch(batch.getId());
        if (latest != null && CcConstants.APPROVAL_PENDING.equals(latest.getStatus())) {
            throw new BusinessException(String.format(
                    "批次 %s 的运输审批单 %s 只完成一半、仍停留待处理（PENDING），不能重复申请，"
                            + "请等待审批人同意/驳回", batch.getBatchNo(), latest.getApprovalNo()));
        }
        if (latest != null && CcConstants.APPROVAL_APPROVED.equals(latest.getStatus())) {
            throw new BusinessException(String.format(
                    "批次 %s 的运输审批单 %s 已同意，不能重复申请", batch.getBatchNo(), latest.getApprovalNo()));
        }
        String region = resolveApplyRegion(req.getSuperviseRegion(), batch);

        LocalDateTime now = LocalDateTime.now();
        InspectionApproval approval = new InspectionApproval();
        approval.setApprovalNo(generateApprovalNo());
        approval.setBatchId(batch.getId());
        approval.setSuperviseRegion(region);
        approval.setApprovalType("TRANSPORT");
        approval.setReason(req.getReason().trim());
        approval.setStatus(CcConstants.APPROVAL_PENDING);
        approval.setApplyBy(UserContext.currentId());
        approval.setApplyName(UserContext.currentName());
        approval.setApplyTime(now);
        approvalMapper.insert(approval);

        opLogService.log(CcConstants.ENT_APPROVAL, approval.getId(), batch.getId(),
                CcConstants.ACT_APPROVAL_APPLY,
                String.format("批次 %s 发起冷链药品运输审批 %s（区域=%s）：申请后停留待处理，"
                                + "同意后方可发运；审批期间抽检任务与样本封存照常可办、可追溯。事由：%s",
                        batch.getBatchNo(), approval.getApprovalNo(), region, approval.getReason()));
        return approval;
    }

    /** 第二步：同意审批（必须留意见），APPROVED 后批次方可发运 */
    @Transactional
    public InspectionApproval approve(Long approvalId, String reviewNote) {
        return review(approvalId, true, reviewNote);
    }

    /** 第二步：驳回审批（必须留意见），停留 REJECTED，可重新申请 */
    @Transactional
    public InspectionApproval reject(Long approvalId, String reviewNote) {
        return review(approvalId, false, reviewNote);
    }

    private InspectionApproval review(Long approvalId, boolean approved, String reviewNote) {
        InspectionApproval approval = requireApproval(approvalId);
        if (!CcConstants.APPROVAL_PENDING.equals(approval.getStatus())) {
            throw new BusinessException(String.format(
                    "审批单 %s 当前状态=%s，仅待处理（PENDING）审批可审查，不能重复审批",
                    approval.getApprovalNo(), approval.getStatus()));
        }
        approval.setStatus(approved ? CcConstants.APPROVAL_APPROVED : CcConstants.APPROVAL_REJECTED);
        approval.setApproveBy(UserContext.currentId());
        approval.setApproveName(UserContext.currentName());
        approval.setApproveTime(LocalDateTime.now());
        approval.setReviewNote(reviewNote.trim());
        approvalMapper.updateById(approval);

        opLogService.log(CcConstants.ENT_APPROVAL, approval.getId(), approval.getBatchId(),
                approved ? CcConstants.ACT_APPROVAL_APPROVE : CcConstants.ACT_APPROVAL_REJECT,
                String.format("运输审批单 %s（批次ID=%d）%s：审批人=%s，意见=%s%s",
                        approval.getApprovalNo(), approval.getBatchId(),
                        approved ? "同意，批次可发运" : "驳回，停留待处理（可重新申请）",
                        approval.getApproveName(), approval.getReviewNote(),
                        approved ? "" : "；驳回期间抽检任务与样本封存仍可办理、可追溯"));
        return approval;
    }

    // ---------------- 查询 / 发运卡口 ----------------

    public InspectionApproval requireApproval(Long approvalId) {
        InspectionApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new BusinessException("运输审批单不存在: " + approvalId);
        }
        if (approval.getSuperviseRegion() != null) {
            regionService.requireAccess(approval.getSuperviseRegion());
        }
        return approval;
    }

    public InspectionApproval latestByBatch(Long batchId) {
        List<InspectionApproval> rows = approvalMapper.selectList(new QueryWrapper<InspectionApproval>()
                .eq("batch_id", batchId).orderByDesc("id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<InspectionApproval> listByBatch(Long batchId) {
        return approvalMapper.selectList(new QueryWrapper<InspectionApproval>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    public List<InspectionApproval> listPending() {
        return approvalMapper.selectList(new QueryWrapper<InspectionApproval>()
                .eq("status", CcConstants.APPROVAL_PENDING).orderByAsc("id"));
    }

    /**
     * 发运卡口：一旦发起过运输审批，最近一张审批单必须 APPROVED 才放行；
     * 只完成一半（PENDING）停留待处理、已驳回（REJECTED）均拦截发运。未发起审批的批次不受此卡口约束。
     */
    public void requireApprovedForShip(Long batchId) {
        InspectionApproval latest = latestByBatch(batchId);
        if (latest == null) {
            return;
        }
        if (CcConstants.APPROVAL_APPROVED.equals(latest.getStatus())) {
            return;
        }
        if (CcConstants.APPROVAL_PENDING.equals(latest.getStatus())) {
            throw new BusinessException(String.format(
                    "冷链药品运输审批只完成一半：批次（ID=%d）审批单 %s 仍停留待处理（PENDING），"
                            + "审批同意前不得发运；审批期间抽检任务与样本封存可照常办理",
                    batchId, latest.getApprovalNo()));
        }
        throw new BusinessException(String.format(
                "批次（ID=%d）运输审批单 %s 已驳回（REJECTED），请重新申请并经同意后再发运",
                batchId, latest.getApprovalNo()));
    }

    private TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    /**
     * 申请阶段区域解析：取显式指定区域，其次批次已固化区域。
     * 申请人（调度岗）不要求具备监管区域授权（能否申请由 PERM_APPROVAL_APPLY 权限点控制）；
     * 区域授权隔离作用于监管人的审查与抽检办理。批次无区域时申请必须显式携带。
     */
    private String resolveApplyRegion(String desired, TransportBatch batch) {
        String region = desired == null ? null : (desired.trim().isEmpty() ? null : desired.trim());
        if (region == null) {
            region = batch.getSuperviseRegion();
        }
        if (region == null) {
            throw new BusinessException(String.format(
                    "批次 %s 尚未归属监管区域，发起运输审批时必须显式携带 superviseRegion",
                    batch.getBatchNo()));
        }
        if (batch.getSuperviseRegion() == null) {
            batch.setSuperviseRegion(region);
            batchMapper.updateById(batch);
        } else if (!batch.getSuperviseRegion().equals(region)) {
            throw new BusinessException(String.format(
                    "批次 %s 已归属监管区域[%s]，不能改用区域[%s]申请审批",
                    batch.getBatchNo(), batch.getSuperviseRegion(), region));
        }
        return region;
    }

    /** 审批单号：SP + 8 位序列 */
    private String generateApprovalNo() {
        return String.format("SP%08d", sequenceMapper.nextApprovalNo());
    }
}
