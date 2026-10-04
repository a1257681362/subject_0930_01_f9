package com.evops.coldchain.controller;

import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.service.VehicleService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 承运车辆 */
@RestController
@RequestMapping("/api/cold-chain/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @PostMapping
    public ApiResponse<Vehicle> create(@Valid @RequestBody VehicleCreateRequest req) {
        return ApiResponse.ok(vehicleService.create(req));
    }

    @GetMapping
    public ApiResponse<List<Vehicle>> list() {
        return ApiResponse.ok(vehicleService.list());
    }
}
