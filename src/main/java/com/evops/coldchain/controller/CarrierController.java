package com.evops.coldchain.controller;

import com.evops.coldchain.dto.CarrierCreateRequest;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.service.CarrierService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 承运商主档（V8） */
@RestController
@RequestMapping("/api/cold-chain/carriers")
public class CarrierController {

    private final CarrierService carrierService;

    public CarrierController(CarrierService carrierService) {
        this.carrierService = carrierService;
    }

    @PostMapping("/create")
    public ApiResponse<Carrier> create(@Valid @RequestBody CarrierCreateRequest req) {
        return ApiResponse.ok(carrierService.create(req));
    }

    @GetMapping
    public ApiResponse<List<Carrier>> list() {
        return ApiResponse.ok(carrierService.list());
    }
}
