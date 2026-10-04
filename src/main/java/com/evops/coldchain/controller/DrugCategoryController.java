package com.evops.coldchain.controller;

import com.evops.coldchain.dto.CategoryChangeRequest;
import com.evops.coldchain.dto.CategoryCreateRequest;
import com.evops.coldchain.dto.DrugAssignCategoryRequest;
import com.evops.coldchain.dto.TemplateSaveRequest;
import com.evops.coldchain.entity.AlertTemplate;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.service.DrugCategoryService;
import com.evops.coldchain.service.DrugService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 药品类别树 + 温控预警模板维护（V5）。
 * 写接口由 Shiro 权限点 coldchain:template:manage 守卫；只读查看需 coldchain:alert:view。
 * 模板修改只影响此后新建批次，历史批次继续使用派车时固化的快照。
 */
@RestController
@RequestMapping("/api/cold-chain")
public class DrugCategoryController {

    private final DrugCategoryService categoryService;
    private final DrugService drugService;

    public DrugCategoryController(DrugCategoryService categoryService, DrugService drugService) {
        this.categoryService = categoryService;
        this.drugService = drugService;
    }

    /** 类别建档（parentId 为空=根类别） */
    @PostMapping("/categories")
    public ApiResponse<DrugCategory> create(@Valid @RequestBody CategoryCreateRequest req) {
        return ApiResponse.ok(categoryService.createCategory(req));
    }

    /** 类别改名/挂接新上级（防环；不动历史批次快照） */
    @PostMapping("/categories/{id}/change")
    public ApiResponse<DrugCategory> change(@PathVariable("id") Long id,
                                            @Valid @RequestBody CategoryChangeRequest req) {
        return ApiResponse.ok(categoryService.changeCategory(id, req));
    }

    /** 类别作废（仍有启用药品/下级类别时拒绝） */
    @PostMapping("/categories/{id}/revoke")
    public ApiResponse<DrugCategory> revoke(@PathVariable("id") Long id) {
        return ApiResponse.ok(categoryService.revokeCategory(id));
    }

    /**
     * 类别树全量列表（只读，需 coldchain:alert:view）。
     * 刻意用 /categories/tree 而非 /categories：Shiro 过滤链不区分 HTTP 方法，
     * 精确路径 /categories 保留给建档（需 coldchain:template:manage）。
     */
    @GetMapping("/categories/tree")
    public ApiResponse<List<DrugCategory>> list() {
        return ApiResponse.ok(categoryService.listCategories());
    }

    /** 类别详情 */
    @GetMapping("/categories/{id}")
    public ApiResponse<DrugCategory> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(categoryService.requireCategory(id));
    }

    /** 维护类别温控预警模板（升版：仅携带要显式覆盖的温区，其余按继承） */
    @PostMapping("/categories/{id}/template")
    public ApiResponse<AlertTemplate> saveTemplate(@PathVariable("id") Long id,
                                                   @Valid @RequestBody TemplateSaveRequest req) {
        return ApiResponse.ok(categoryService.saveTemplate(id, req));
    }

    /** 模板详情（本类别覆盖温区 + 沿链继承解析后的生效温区，标明来源） */
    @GetMapping("/categories/{id}/template")
    public ApiResponse<Map<String, Object>> templateDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(categoryService.templateDetail(id));
    }

    /** 把药品归组到类别（只影响此后新建批次） */
    @PostMapping("/drugs/{id}/category")
    public ApiResponse<Drug> assignCategory(@PathVariable("id") Long id,
                                            @Valid @RequestBody DrugAssignCategoryRequest req) {
        return ApiResponse.ok(drugService.assignCategory(id, req.getCategoryId()));
    }
}
