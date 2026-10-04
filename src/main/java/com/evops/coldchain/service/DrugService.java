package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DrugChangeRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.mapper.DrugMapper;
import com.evops.coldchain.mapper.DrugVersionMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.List;

/**
 * 药品档案服务。
 * 核心规则：档案版本只追加——建档落 V1；变更把旧版本置 SUPERSEDED 并新增一行，
 * 旧版本的批准文号/温区/包装原值永不修改，已派车批次永远能按原版本核对。
 * V5：药品可挂药品类别，类别温控预警模板在派车时按创建时快照固化。
 */
@Service
public class DrugService {

    @Resource
    private DrugMapper drugMapper;
    @Resource
    private DrugVersionMapper versionMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;
    // @Lazy：DrugCategoryService 也依赖药品 Mapper，延迟注入避免潜在循环依赖
    @Resource
    @Lazy
    private DrugCategoryService categoryService;

    @Transactional
    public Drug create(DrugCreateRequest req) {
        validateRange(req.getTempMin(), req.getTempMax());
        DrugCategory category = null;
        if (req.getCategoryId() != null) {
            category = categoryService.requireActiveCategory(req.getCategoryId());
        }

        Drug drug = new Drug();
        drug.setDrugNo("YP" + sequenceMapper.nextDrugNo());
        drug.setDrugName(req.getDrugName());
        drug.setCurrentVersion(1);
        drug.setStatus(CcConstants.ACTIVE);
        drug.setCategoryId(category == null ? null : category.getId());
        drug.setRemark(req.getRemark());
        drugMapper.insert(drug);

        DrugVersion v1 = new DrugVersion();
        v1.setDrugId(drug.getId());
        v1.setVersionNo(1);
        v1.setApprovalNo(req.getApprovalNo());
        v1.setTempMin(req.getTempMin());
        v1.setTempMax(req.getTempMax());
        v1.setPackageSpec(req.getPackageSpec());
        v1.setStatus(CcConstants.EFFECTIVE);
        versionMapper.insert(v1);

        opLogService.log(CcConstants.ENT_DRUG, drug.getId(), null, CcConstants.ACT_CREATE,
                String.format("药品建档：%s(%s) V1，批准文号=%s，温区=%s~%s℃，包装=%s",
                        drug.getDrugName(), drug.getDrugNo(), req.getApprovalNo(),
                        req.getTempMin(), req.getTempMax(), req.getPackageSpec()));
        return drug;
    }

    /**
     * 档案变更：追加新版本，不改旧版本原值。
     */
    @Transactional
    public DrugVersion change(Long drugId, DrugChangeRequest req) {
        Drug drug = requireDrug(drugId);
        if (CcConstants.REVOKED.equals(drug.getStatus())) {
            throw new BusinessException("档案已撤回，不能变更");
        }
        validateRange(req.getTempMin(), req.getTempMax());

        int newVersionNo = drug.getCurrentVersion() + 1;

        // 旧版本仅状态置 SUPERSEDED，业务字段原值不动
        DrugVersion current = requireVersion(drugId, drug.getCurrentVersion());
        current.setStatus(CcConstants.SUPERSEDED);
        versionMapper.updateById(current);

        DrugVersion next = new DrugVersion();
        next.setDrugId(drugId);
        next.setVersionNo(newVersionNo);
        next.setApprovalNo(req.getApprovalNo());
        next.setTempMin(req.getTempMin());
        next.setTempMax(req.getTempMax());
        next.setPackageSpec(req.getPackageSpec());
        next.setChangeReason(req.getChangeReason());
        next.setStatus(CcConstants.EFFECTIVE);
        versionMapper.insert(next);

        drug.setCurrentVersion(newVersionNo);
        drugMapper.updateById(drug);

        opLogService.log(CcConstants.ENT_DRUG, drugId, null, CcConstants.ACT_CHANGE,
                String.format("药品 %s 变更 V%d（原因：%s）：批准文号=%s，温区=%s~%s℃，包装=%s；原 V%d 已封存",
                        drug.getDrugNo(), newVersionNo, req.getChangeReason(),
                        req.getApprovalNo(), req.getTempMin(), req.getTempMax(), req.getPackageSpec(),
                        current.getVersionNo()));
        return next;
    }

    public Drug requireDrug(Long drugId) {
        Drug drug = drugMapper.selectById(drugId);
        if (drug == null) {
            throw new BusinessException("药品档案不存在: " + drugId);
        }
        return drug;
    }

    public DrugVersion requireVersion(Long drugId, Integer versionNo) {
        DrugVersion version = versionMapper.selectOne(new QueryWrapper<DrugVersion>()
                .eq("drug_id", drugId)
                .eq("version_no", versionNo));
        if (version == null) {
            throw new BusinessException(String.format("药品档案版本不存在: drugId=%d, version=%d", drugId, versionNo));
        }
        return version;
    }

    public List<DrugVersion> listVersions(Long drugId) {
        return versionMapper.selectList(new QueryWrapper<DrugVersion>()
                .eq("drug_id", drugId)
                .orderByAsc("version_no"));
    }

    /**
     * 把药品归组到类别（只影响此后新建批次）。
     * 已派车/在途批次的预警模板快照在派车时已固化，归组/改挂不回写旧批次。
     */
    @Transactional
    public Drug assignCategory(Long drugId, Long categoryId) {
        Drug drug = requireDrug(drugId);
        if (CcConstants.REVOKED.equals(drug.getStatus())) {
            throw new BusinessException("档案已撤回，不能改挂类别");
        }
        DrugCategory category = categoryService.requireActiveCategory(categoryId);
        Long old = drug.getCategoryId();
        drug.setCategoryId(category.getId());
        drugMapper.updateById(drug);

        opLogService.log(CcConstants.ENT_DRUG, drugId, null, CcConstants.ACT_DRUG_ASSIGN_CATEGORY,
                String.format("药品 %s(%s) 归组到类别[%s(%s)]（原类别=%s）；只影响此后新建批次，旧批次预警快照不变",
                        drug.getDrugName(), drug.getDrugNo(),
                        category.getCategoryName(), category.getCategoryNo(),
                        old == null ? "无" : String.valueOf(old)));
        return drug;
    }

    private void validateRange(BigDecimal min, BigDecimal max) {
        if (min.compareTo(max) > 0) {
            throw new BusinessException("储存温区下限不能高于上限");
        }
    }
}
