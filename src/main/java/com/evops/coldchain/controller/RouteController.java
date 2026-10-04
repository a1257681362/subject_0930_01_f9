package com.evops.coldchain.controller;

import com.evops.coldchain.dto.RouteCreateRequest;
import com.evops.coldchain.dto.RouteDraftRequest;
import com.evops.coldchain.dto.RoutePublishRequest;
import com.evops.coldchain.dto.RouteWithdrawRequest;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.entity.RouteSegment;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.service.RouteService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链运输路线温区分段配置入口：
 * 路线建档 -> 按季节编排分段草稿（相邻无空档/重叠）-> 发布前查看差异 -> 发布（热替换）
 * -> 仅可撤回尚未生效的版本。已发运执行的批次按发运时固化的分段快照执行，不受后续版本影响。
 */
@RestController
@RequestMapping("/api/cold-chain/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    /** 路线建档 */
    @PostMapping
    public ApiResponse<Route> create(@Valid @RequestBody RouteCreateRequest req) {
        return ApiResponse.ok(routeService.createRoute(req));
    }

    /** 路线列表 */
    @GetMapping
    public ApiResponse<List<Route>> list() {
        return ApiResponse.ok(routeService.listRoutes());
    }

    /** 路线详情（含全部季节版本） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        Route route = routeService.requireRoute(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("route", route);
        data.put("versions", routeService.listVersions(id));
        return ApiResponse.ok(data);
    }

    /** 某季节版本的全部分段（含草稿/历史版本，便于验收追溯到分段行） */
    @GetMapping("/versions/{versionId}/segments")
    public ApiResponse<List<RouteSegment>> segments(@PathVariable("versionId") Long versionId) {
        return ApiResponse.ok(routeService.listSegments(versionId));
    }

    /** 按季节编排（或覆盖未发布的）分段草稿 */
    @PostMapping("/{id}/versions/draft")
    public ApiResponse<RouteVersion> saveDraft(@PathVariable("id") Long id,
                                               @Valid @RequestBody RouteDraftRequest req) {
        return ApiResponse.ok(routeService.saveDraft(id, req));
    }

    /** 发布前差异：草稿相对当前在效版本的分段增/删/改 */
    @GetMapping("/versions/{versionId}/diff")
    public ApiResponse<Map<String, Object>> diff(@PathVariable("versionId") Long versionId) {
        return ApiResponse.ok(routeService.previewDiff(versionId));
    }

    /** 发布（即时生效=热替换；预约生效不得与现有窗口重叠） */
    @PostMapping("/versions/publish")
    public ApiResponse<Map<String, Object>> publish(@Valid @RequestBody RoutePublishRequest req) {
        return ApiResponse.ok(routeService.publish(req.getVersionId()));
    }

    /** 撤回：仅尚未生效且未被批次引用的版本可撤 */
    @PostMapping("/versions/withdraw")
    public ApiResponse<RouteVersion> withdraw(@Valid @RequestBody RouteWithdrawRequest req) {
        return ApiResponse.ok(routeService.withdraw(req.getVersionId(), req.getReason()));
    }
}
