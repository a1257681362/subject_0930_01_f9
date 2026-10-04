package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.CategoryChangeRequest;
import com.evops.coldchain.dto.CategoryCreateRequest;
import com.evops.coldchain.dto.TemplateSaveRequest;
import com.evops.coldchain.dto.ZoneDraftRequest;
import com.evops.coldchain.entity.AlertTemplate;
import com.evops.coldchain.entity.AlertTemplateZone;
import com.evops.coldchain.entity.BatchAlertZoneSnap;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.AlertTemplateMapper;
import com.evops.coldchain.mapper.AlertTemplateZoneMapper;
import com.evops.coldchain.mapper.BatchAlertZoneSnapMapper;
import com.evops.coldchain.mapper.DrugCategoryMapper;
import com.evops.coldchain.mapper.DrugMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 药品类别 + 温控预警模板服务（V5）。
 *
 * 继承与覆盖：
 * - 类别树通过 parent_id 自引用；每个类别至多一份模板，模板按温区行给阈值。
 * - 类别未显式配置某温区且 inheritParent=TRUE 时，沿类别链向上继承最近一级阈值；
 *   在指定温区显式提交阈值即覆盖上级。中间类别 inheritParent=FALSE 会截断继续向上的继承。
 *
 * 只影响新批次：
 * - 派车（批次创建）时调用 {@link #snapshotForBatch} 把“解析后的生效模板”逐温区固化到批次快照；
 *   此后类别树改名/移动、模板升版、温区阈值调整都与在途批次脱钩，旧批次继续用创建时快照。
 *
 * 模板温区行只追加：维护=旧生效行置 SUPERSEDED + 插入新生效行，历史行原值永不修改。
 */
@Service
public class DrugCategoryService {

    @Resource
    private DrugCategoryMapper categoryMapper;
    @Resource
    private AlertTemplateMapper templateMapper;
    @Resource
    private AlertTemplateZoneMapper zoneMapper;
    @Resource
    private BatchAlertZoneSnapMapper snapMapper;
    @Resource
    private DrugMapper drugMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 类别树 ----------------

    @Transactional
    public DrugCategory createCategory(CategoryCreateRequest req) {
        if (req.getParentId() != null) {
            requireActiveCategory(req.getParentId());
        }
        DrugCategory category = new DrugCategory();
        category.setCategoryNo("LB" + sequenceMapper.nextCategoryNo());
        category.setCategoryName(req.getCategoryName());
        category.setParentId(req.getParentId());
        category.setStatus(CcConstants.ACTIVE);
        category.setRemark(req.getRemark());
        categoryMapper.insert(category);

        opLogService.log(CcConstants.ENT_CATEGORY, category.getId(), null, CcConstants.ACT_CATEGORY_CREATE,
                String.format("药品类别建档：%s(%s)%s",
                        category.getCategoryName(), category.getCategoryNo(),
                        req.getParentId() == null ? "（根类别）" : "，上级类别ID=" + req.getParentId()));
        return category;
    }

    /** 改名/挂接新上级（不允许把类别挂到自己或自己的后代下，防止成环） */
    @Transactional
    public DrugCategory changeCategory(Long categoryId, CategoryChangeRequest req) {
        DrugCategory category = requireActiveCategory(categoryId);
        Long newParentId = req.getParentId();
        if (newParentId != null) {
            DrugCategory parent = requireActiveCategory(newParentId);
            if (parent.getId().equals(categoryId)) {
                throw new BusinessException("上级类别不能是自己");
            }
            if (isDescendant(categoryId, newParentId)) {
                throw new BusinessException(String.format(
                        "不能把类别[%s]挂到其下级类别[%s]下，会形成环",
                        category.getCategoryName(), parent.getCategoryName()));
            }
        }
        String oldName = category.getCategoryName();
        category.setCategoryName(req.getCategoryName());
        category.setParentId(newParentId);
        category.setRemark(req.getRemark());
        categoryMapper.updateById(category);

        opLogService.log(CcConstants.ENT_CATEGORY, categoryId, null, CcConstants.ACT_CATEGORY_CHANGE,
                String.format("药品类别变更：%s(%s) 改名为[%s]，上级类别=%s",
                        oldName, category.getCategoryNo(), req.getCategoryName(),
                        newParentId == null ? "根类别" : String.valueOf(newParentId)));
        return category;
    }

    /** 作废类别：仍有启用药品或启用下级类别时拒绝（避免新批次失去模板归属） */
    @Transactional
    public DrugCategory revokeCategory(Long categoryId) {
        DrugCategory category = requireCategory(categoryId);
        if (CcConstants.REVOKED.equals(category.getStatus())) {
            throw new BusinessException("类别已作废");
        }
        Long drugs = drugMapper.selectCount(new QueryWrapper<Drug>()
                .eq("category_id", categoryId).eq("status", CcConstants.ACTIVE));
        if (drugs != null && drugs > 0) {
            throw new BusinessException(String.format(
                    "类别[%s]下仍有 %d 个启用药品，不能作废；请先把药品改挂其它类别", category.getCategoryName(), drugs));
        }
        Long children = categoryMapper.selectCount(new QueryWrapper<DrugCategory>()
                .eq("parent_id", categoryId).eq("status", CcConstants.ACTIVE));
        if (children != null && children > 0) {
            throw new BusinessException(String.format(
                    "类别[%s]下仍有 %d 个启用下级类别，不能作废", category.getCategoryName(), children));
        }
        category.setStatus(CcConstants.REVOKED);
        categoryMapper.updateById(category);

        opLogService.log(CcConstants.ENT_CATEGORY, categoryId, null, CcConstants.ACT_CATEGORY_REVOKE,
                "药品类别作废：" + category.getCategoryName() + "(" + category.getCategoryNo() + ")（历史模板行与批次快照保留）");
        return category;
    }

    public DrugCategory requireCategory(Long categoryId) {
        DrugCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException("药品类别不存在: " + categoryId);
        }
        return category;
    }

    public DrugCategory requireActiveCategory(Long categoryId) {
        DrugCategory category = requireCategory(categoryId);
        if (CcConstants.REVOKED.equals(category.getStatus())) {
            throw new BusinessException("药品类别已作废: " + category.getCategoryName());
        }
        return category;
    }

    public List<DrugCategory> listCategories() {
        return categoryMapper.selectList(new QueryWrapper<DrugCategory>().orderByAsc("id"));
    }

    /** candidateId 是否是 ancestorId 的后代（用于防环：若 ancestorId 在 candidateId 的子树内则成环） */
    private boolean isDescendant(Long ancestorId, Long candidateId) {
        Long cursor = candidateId;
        Set<Long> guard = new HashSet<>();
        while (cursor != null && guard.add(cursor)) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            DrugCategory c = categoryMapper.selectById(cursor);
            cursor = c == null ? null : c.getParentId();
        }
        return false;
    }

    // ---------------- 模板维护（温区行只追加） ----------------

    /**
     * 维护类别模板（升一版）：仅提交本类别要显式覆盖的温区；
     * 未提交温区在 inheritParent=TRUE 时继续向上继承。只影响此后新建批次。
     */
    @Transactional
    public AlertTemplate saveTemplate(Long categoryId, TemplateSaveRequest req) {
        DrugCategory category = requireActiveCategory(categoryId);
        boolean inheritParent = req.getInheritParent() == null || req.getInheritParent();
        List<ZoneDraftRequest> zones = req.getZones() == null ? new ArrayList<>() : req.getZones();
        validateZones(zones, inheritParent);

        AlertTemplate template = findTemplate(categoryId);
        int newVersionNo;
        if (template == null) {
            template = new AlertTemplate();
            template.setCategoryId(categoryId);
            newVersionNo = 1;
            template.setVersionNo(newVersionNo);
            template.setStatus(CcConstants.EFFECTIVE);
        } else {
            newVersionNo = template.getVersionNo() + 1;
            template.setVersionNo(newVersionNo);
            // 旧模板维持 EFFECTIVE 单例行（uk 类别唯一），版本号直接抬升；
            // 阈值历史由“温区行只追加”完整保留。
        }
        template.setInheritParent(inheritParent);
        template.setChangeReason(req.getChangeReason());
        if (template.getId() == null) {
            templateMapper.insert(template);
        } else {
            templateMapper.updateById(template);
        }

        // 旧生效温区行置 SUPERSEDED（原值不动），再插入新生效行
        List<AlertTemplateZone> oldZones = listEffectiveZoneEntities(categoryId);
        for (AlertTemplateZone old : oldZones) {
            old.setStatus(CcConstants.SUPERSEDED);
            zoneMapper.updateById(old);
        }
        for (ZoneDraftRequest z : zones) {
            AlertTemplateZone row = new AlertTemplateZone();
            row.setTemplateId(template.getId());
            row.setZoneCode(z.getZoneCode().trim().toUpperCase());
            row.setZoneName(z.getZoneName());
            row.setTempMin(z.getTempMin());
            row.setTempMax(z.getTempMax());
            row.setTolerateMinutes(z.getTolerateMinutes());
            row.setJitterGapMinutes(z.getJitterGapMinutes() == null ? 0 : z.getJitterGapMinutes());
            row.setStatus(CcConstants.EFFECTIVE);
            zoneMapper.insert(row);
        }

        opLogService.log(CcConstants.ENT_ALERT_TEMPLATE, template.getId(), null, CcConstants.ACT_TEMPLATE_SAVE,
                String.format("类别[%s]温控预警模板升 V%d：本类别覆盖温区 %d 个（%s），未覆盖温区%s；原因=%s。只影响此后新建批次",
                        category.getCategoryName(), newVersionNo, zones.size(), zoneCodes(zones),
                        inheritParent ? "沿类别树继承上级阈值" : "不继承（未配置即不预警）",
                        req.getChangeReason()));
        return template;
    }

    private void validateZones(List<ZoneDraftRequest> zones, boolean inheritParent) {
        Set<String> seen = new HashSet<>();
        for (ZoneDraftRequest z : zones) {
            String code = z.getZoneCode().trim().toUpperCase();
            if (!seen.add(code)) {
                throw new BusinessException("同一温区在一次提交中重复: " + code);
            }
            if (z.getTempMin().compareTo(z.getTempMax()) > 0) {
                throw new BusinessException("温区[" + code + "]下限不能高于上限");
            }
            if (z.getTolerateMinutes() == null || z.getTolerateMinutes() < 0) {
                throw new BusinessException("温区[" + code + "]连续超限容忍时长必须≥0");
            }
            if (z.getJitterGapMinutes() != null && z.getJitterGapMinutes() < 0) {
                throw new BusinessException("温区[" + code + "]抖动合并窗必须≥0");
            }
        }
        if (zones.isEmpty() && !inheritParent) {
            throw new BusinessException("本类别既不覆盖任何温区又关闭继承，将没有任何预警阈值，拒绝保存");
        }
    }

    private String zoneCodes(List<ZoneDraftRequest> zones) {
        if (zones.isEmpty()) {
            return "无";
        }
        List<String> codes = new ArrayList<>();
        for (ZoneDraftRequest z : zones) {
            codes.add(z.getZoneCode().trim().toUpperCase());
        }
        return String.join("/", codes);
    }

    public AlertTemplate findTemplate(Long categoryId) {
        return templateMapper.selectOne(new QueryWrapper<AlertTemplate>()
                .eq("category_id", categoryId).last("LIMIT 1"));
    }

    /** 某类别自身显式生效的温区行（不含继承） */
    public List<AlertTemplateZone> listEffectiveZoneEntities(Long categoryId) {
        AlertTemplate template = findTemplate(categoryId);
        if (template == null) {
            return new ArrayList<>();
        }
        return zoneMapper.selectList(new QueryWrapper<AlertTemplateZone>()
                .eq("template_id", template.getId())
                .eq("status", CcConstants.EFFECTIVE)
                .orderByAsc("zone_code"));
    }

    // ---------------- 继承解析 ----------------

    /**
     * 沿类别链解析全部生效温区：本类别覆盖优先，缺失温区按 inheritParent 向上继承最近一级。
     * 返回有序 map（zoneCode -> 解析结果）。
     */
    public Map<String, ResolvedAlertZone> resolveEffectiveZones(Long categoryId) {
        DrugCategory start = requireActiveCategory(categoryId);
        Map<String, ResolvedAlertZone> resolved = new LinkedHashMap<>();

        // 1) 本类别显式覆盖
        AlertTemplate ownTemplate = findTemplate(categoryId);
        boolean ownInherit = ownTemplate == null || !Boolean.FALSE.equals(ownTemplate.getInheritParent());
        for (AlertTemplateZone z : listEffectiveZoneEntities(categoryId)) {
            resolved.put(z.getZoneCode(), new ResolvedAlertZone(z, false, start));
        }

        // 2) 沿链继承缺失温区；中间类别 inheritParent=FALSE 则停止继续向上
        if (ownInherit) {
            DrugCategory cursor = parent(start);
            Set<Long> guard = new HashSet<>();
            while (cursor != null && guard.add(cursor.getId())) {
                if (CcConstants.REVOKED.equals(cursor.getStatus())) {
                    break;
                }
                AlertTemplate tpl = findTemplate(cursor.getId());
                boolean cursorInherit = tpl == null || !Boolean.FALSE.equals(tpl.getInheritParent());
                if (tpl != null) {
                    for (AlertTemplateZone z : listEffectiveZoneEntities(cursor.getId())) {
                        resolved.putIfAbsent(z.getZoneCode(), new ResolvedAlertZone(z, true, cursor));
                    }
                }
                if (!cursorInherit) {
                    break; // 该类别关闭继承：缺失温区到此为止，不再继续向上
                }
                cursor = parent(cursor);
            }
        }
        return resolved;
    }

    private DrugCategory parent(DrugCategory category) {
        return category.getParentId() == null ? null : categoryMapper.selectById(category.getParentId());
    }

    /**
     * 派车时把解析后的生效模板逐温区固化到批次快照，并按药品档案储存温区命中本批次预警温区。
     * 药品未挂类别或解析不到任何温区时不生成快照、不做 V5 预警（不影响 V1/V2 既有判定）。
     *
     * @return 命中的预警温区代码（无则 null）
     */
    @Transactional
    public String snapshotForBatch(TransportBatch batch, DrugVersion version) {
        if (batch.getDrugId() == null) {
            return null;
        }
        Drug drug = drugMapper.selectById(batch.getDrugId());
        if (drug == null || drug.getCategoryId() == null) {
            return null;
        }
        Long categoryId = drug.getCategoryId();
        Map<String, ResolvedAlertZone> resolved = resolveEffectiveZones(categoryId);
        if (resolved.isEmpty()) {
            return null;
        }
        AlertTemplate ownTemplate = findTemplate(categoryId);

        // 先清掉同批次旧快照（正常派车不会有；重入/异常重试时保证幂等，不多写）
        snapMapper.delete(new QueryWrapper<BatchAlertZoneSnap>().eq("batch_id", batch.getId()));

        for (ResolvedAlertZone r : resolved.values()) {
            AlertTemplateZone z = r.getZone();
            BatchAlertZoneSnap snap = new BatchAlertZoneSnap();
            snap.setBatchId(batch.getId());
            snap.setCategoryId(categoryId);
            snap.setTemplateVersionNo(ownTemplate == null ? null : ownTemplate.getVersionNo());
            snap.setZoneCode(z.getZoneCode());
            snap.setZoneName(z.getZoneName());
            snap.setTempMin(z.getTempMin());
            snap.setTempMax(z.getTempMax());
            snap.setTolerateMinutes(z.getTolerateMinutes());
            snap.setJitterGapMinutes(z.getJitterGapMinutes());
            snap.setInherited(r.isInherited());
            snap.setSourceCategoryId(r.getSourceCategory().getId());
            snap.setSourceCategoryNo(r.getSourceCategory().getCategoryNo());
            snapMapper.insert(snap);
        }

        String zoneCode = matchZoneCode(resolved, version.getTempMin(), version.getTempMax());
        return zoneCode;
    }

    /** 按药品储存温区从已解析温区中命中：优先能完整包含储存温区的，否则取包含中值的 */
    private String matchZoneCode(Map<String, ResolvedAlertZone> resolved, BigDecimal drugMin, BigDecimal drugMax) {
        String fallback = null;
        BigDecimal mid = drugMin.add(drugMax).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
        for (Map.Entry<String, ResolvedAlertZone> e : resolved.entrySet()) {
            AlertTemplateZone z = e.getValue().getZone();
            boolean encloses = z.getTempMin().compareTo(drugMin) <= 0 && z.getTempMax().compareTo(drugMax) >= 0;
            if (encloses) {
                return e.getKey();
            }
            if (fallback == null && z.getTempMin().compareTo(mid) <= 0 && z.getTempMax().compareTo(mid) >= 0) {
                fallback = e.getKey();
            }
        }
        return fallback;
    }

    // ---------------- 模板查看（含继承解析视图） ----------------

    /** 类别模板详情：本类别模板 + 自身覆盖温区 + 沿链解析后的生效温区（标明继承来源） */
    public Map<String, Object> templateDetail(Long categoryId) {
        DrugCategory category = requireCategory(categoryId);
        AlertTemplate template = findTemplate(categoryId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", category);
        data.put("template", template);
        data.put("ownZones", listEffectiveZoneEntities(categoryId));

        List<Map<String, Object>> resolvedView = new ArrayList<>();
        for (ResolvedAlertZone r : resolveEffectiveZones(categoryId).values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("zone", r.getZone());
            v.put("inherited", r.isInherited());
            v.put("sourceCategory", r.getSourceCategory());
            resolvedView.add(v);
        }
        data.put("resolvedZones", resolvedView);
        return data;
    }
}
