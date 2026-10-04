package com.evops.coldchain.controller;

import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.service.OrderService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** 发货单：创建即锁定档案版本 */
@RestController
@RequestMapping("/api/cold-chain/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ApiResponse<ShipmentOrder> create(@Valid @RequestBody OrderCreateRequest req) {
        return ApiResponse.ok(orderService.create(req));
    }

    @GetMapping("/{id}")
    public ApiResponse<ShipmentOrder> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(orderService.requireOrder(id));
    }
}
