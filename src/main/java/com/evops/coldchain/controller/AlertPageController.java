package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.BatchAlertZoneSnap;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.mapper.BatchAlertZoneSnapMapper;
import com.evops.coldchain.mapper.DrugCategoryMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.service.DrugCategoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 温控预警 + 药品类别模板管理页面（V5，HTTP Basic）。
 * 页面本身经 Shiro coldchain:alert:view 守卫；页面内维护/导出/闭环/扫描动作再经对应 API 权限点校验。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class AlertPageController {

    @Resource
    private TempAlertMapper alertMapper;
    @Resource
    private DrugCategoryMapper categoryMapper;
    @Resource
    private BatchAlertZoneSnapMapper snapMapper;
    @Resource
    private DrugCategoryService categoryService;

    @GetMapping("/alerts")
    public String alerts(Model model) {
        List<TempAlert> alertList = alertMapper.selectList(new QueryWrapper<TempAlert>()
                .orderByDesc("trigger_time").orderByDesc("id"));
        model.addAttribute("alertList", alertList);

        List<DrugCategory> categories = categoryMapper.selectList(new QueryWrapper<DrugCategory>()
                .orderByAsc("id"));
        List<Map<String, Object>> categoryViews = new ArrayList<>();
        for (DrugCategory c : categories) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("category", c);
            view.put("detail", categoryService.templateDetail(c.getId()));
            categoryViews.add(view);
        }
        model.addAttribute("categoryViews", categoryViews);

        long snapBatches = snapMapper.selectCount(new QueryWrapper<BatchAlertZoneSnap>()
                .select("DISTINCT batch_id"));
        model.addAttribute("snapshotBatchCount", snapBatches);
        return "coldchain/alerts";
    }
}
