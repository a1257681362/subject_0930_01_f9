package com.evops.coldchain.controller;

import com.evops.coldchain.dto.DrugChangeRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.service.DrugService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品档案：建档与变更。变更只追加新版本，旧版本永久保留。
 */
@RestController
@RequestMapping("/api/cold-chain/drugs")
public class DrugController {

    private final DrugService drugService;

    public DrugController(DrugService drugService) {
        this.drugService = drugService;
    }

    /** 建档（V1） */
    @PostMapping
    public ApiResponse<Drug> create(@Valid @RequestBody DrugCreateRequest req) {
        return ApiResponse.ok(drugService.create(req));
    }

    /** 档案变更（追加新版本） */
    @PostMapping("/{id}/versions")
    public ApiResponse<DrugVersion> change(@PathVariable("id") Long id,
                                           @Valid @RequestBody DrugChangeRequest req) {
        return ApiResponse.ok(drugService.change(id, req));
    }

    /** 查看档案全部版本（含已封存的历史版本） */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<DrugVersion>> versions(@PathVariable("id") Long id) {
        return ApiResponse.ok(drugService.listVersions(id));
    }

    /** 档案详情（含当前版本） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        Drug drug = drugService.requireDrug(id);
        Map<String, Object> data = new HashMap<>();
        data.put("drug", drug);
        data.put("versions", drugService.listVersions(id));
        return ApiResponse.ok(data);
    }
}
