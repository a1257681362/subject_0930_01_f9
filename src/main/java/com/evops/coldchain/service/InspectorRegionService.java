package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.InspectorRegion;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.InspectorRegionMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 监管区域授权与数据隔离（V7）。
 *
 * - t_cc_inspector_region 维护“监管账号 -> 监管区域”授权（可多区；'*' 为全域）。
 * - 抽检/审批的查询与办理一律先解析当前账号授权区域，再校验目标区域：
 *   无任何区域授权拒绝办理；访问未授权区域的数据直接拒绝（区域隔离）。
 * - 批次监管区域可在派车时指定，也可在首次对该批次办理抽检/审批时补登，补登后不再改变。
 */
@Service
public class InspectorRegionService {

    /** 全域通配（仅内置管理员） */
    public static final String REGION_ALL = "*";

    @Resource
    private InspectorRegionMapper regionMapper;
    @Resource
    private TransportBatchMapper batchMapper;

    /** 当前登录账号被授权的监管区域集合（含 '*' 即全域） */
    public Set<String> myRegions() {
        return regionsOf(UserContext.currentName());
    }

    public Set<String> regionsOf(String username) {
        List<InspectorRegion> rows = regionMapper.selectList(new QueryWrapper<InspectorRegion>()
                .eq("username", username));
        Set<String> regions = new LinkedHashSet<>();
        for (InspectorRegion r : rows) {
            regions.add(r.getRegionCode());
        }
        return regions;
    }

    public boolean isGlobal(Set<String> regions) {
        return regions.contains(REGION_ALL);
    }

    /** 当前账号是否可访问指定区域 */
    public boolean canAccess(String regionCode) {
        Set<String> mine = myRegions();
        return isGlobal(mine) || regionCode != null && mine.contains(regionCode);
    }

    /** 区域访问卡口：未授权直接拒绝 */
    public void requireAccess(String regionCode) {
        if (!canAccess(regionCode)) {
            throw new BusinessException(String.format(
                    "监管区域隔离：当前账号[%s]未被授权监管区域[%s]，不能查询或办理该区域的抽检/审批数据",
                    UserContext.currentName(), regionCode));
        }
    }

    /**
     * 解析批次办理区域：优先显式指定，其次批次已固化区域，再次账号唯一授权区域；
     * 批次区域为空时把解析结果补登到批次（补登一次，之后不再改变），并校验授权。
     */
    @Transactional
    public String resolveAndBindRegion(String desired, TransportBatch batch) {
        String region = trimToNull(desired);
        if (region == null) {
            region = trimToNull(batch.getSuperviseRegion());
        }
        Set<String> mine = myRegions();
        boolean global = isGlobal(mine);
        if (region == null) {
            if (global) {
                throw new BusinessException(String.format(
                        "批次 %s 尚未归属监管区域，全域账号必须显式指定 superviseRegion 后才能办理抽检/审批",
                        batch.getBatchNo()));
            }
            if (mine.isEmpty()) {
                throw new BusinessException(String.format(
                        "当前账号[%s]没有任何监管区域授权，不能办理抽检/审批（请先在 t_cc_inspector_region 授权）",
                        UserContext.currentName()));
            }
            if (mine.size() > 1) {
                throw new BusinessException(String.format(
                        "批次 %s 尚未归属监管区域，当前账号被授权多个区域 %s，必须显式指定 superviseRegion",
                        batch.getBatchNo(), mine));
            }
            region = mine.iterator().next();
        }
        if (!global && !mine.contains(region)) {
            throw new BusinessException(String.format(
                    "监管区域隔离：当前账号[%s]未被授权监管区域[%s]（授权区域=%s），不能办理批次 %s 的抽检/审批",
                    UserContext.currentName(), region, mine, batch.getBatchNo()));
        }
        if (batch.getSuperviseRegion() == null) {
            batch.setSuperviseRegion(region);
            batchMapper.updateById(batch);
        } else if (!batch.getSuperviseRegion().equals(region)) {
            throw new BusinessException(String.format(
                    "批次 %s 已归属监管区域[%s]，不能改用区域[%s]办理（监管区域一经确定不再改变）",
                    batch.getBatchNo(), batch.getSuperviseRegion(), region));
        }
        return region;
    }

    /** 按当前账号授权区域收窄查询条件；全域不加限制 */
    public QueryWrapper<com.evops.coldchain.entity.InspectionTask> applyRegionFilter(
            QueryWrapper<com.evops.coldchain.entity.InspectionTask> qw, String filterRegion) {
        Set<String> mine = myRegions();
        if (isGlobal(mine)) {
            if (filterRegion != null) {
                qw.eq("supervise_region", filterRegion);
            }
        } else {
            if (mine.isEmpty()) {
                // 无授权：强制查不到任何数据
                qw.eq("supervise_region", "__NO_REGION__");
            } else if (filterRegion != null) {
                if (!mine.contains(filterRegion)) {
                    throw new BusinessException(String.format(
                            "监管区域隔离：当前账号[%s]未被授权监管区域[%s]，不能查询该区域抽检数据",
                            UserContext.currentName(), filterRegion));
                }
                qw.eq("supervise_region", filterRegion);
            } else {
                qw.in("supervise_region", mine);
            }
        }
        return qw;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
