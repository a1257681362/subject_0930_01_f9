package com.evops.coldchain.controller;

import com.evops.coldchain.dto.DeviceBindRequest;
import com.evops.coldchain.dto.DeviceRegisterRequest;
import com.evops.coldchain.dto.DeviceRevokeRequest;
import com.evops.coldchain.entity.Device;
import com.evops.coldchain.service.DeviceService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 车载温控设备登记/证书备案/作废/换绑（V9）。
 * 写操作需 coldchain:device:manage，查看需 coldchain:device:view（Shiro 链路配置）。
 */
@RestController
@RequestMapping("/api/cold-chain/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 设备登记（备案 X.509 证书，可同时安装车辆） */
    @PostMapping
    public ApiResponse<Device> register(@Valid @RequestBody DeviceRegisterRequest req) {
        return ApiResponse.ok(deviceService.register(req));
    }

    /** 设备安装/换绑车辆 */
    @PostMapping("/bind")
    public ApiResponse<Device> bind(@Valid @RequestBody DeviceBindRequest req) {
        return ApiResponse.ok(deviceService.bindVehicle(req));
    }

    /** 设备作废（此后报文一律隔离，历史数据保留） */
    @PostMapping("/revoke")
    public ApiResponse<Device> revoke(@Valid @RequestBody DeviceRevokeRequest req) {
        return ApiResponse.ok(deviceService.revoke(req));
    }

    /** 设备列表（独立只读路径，查看权限） */
    @GetMapping("/list")
    public ApiResponse<List<Device>> list() {
        return ApiResponse.ok(deviceService.listDevices());
    }

    /** 设备详情（含备案证书要素） */
    @GetMapping("/{id}")
    public ApiResponse<Device> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(deviceService.requireDevice(id));
    }
}
