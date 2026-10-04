package com.evops.common;

/**
 * 业务规则异常：状态机非法流转、核对不符、稳定编号冲突等。
 * 由 GlobalExceptionHandler 转换为 ApiResponse.fail，不回退为“系统处理失败”。
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
