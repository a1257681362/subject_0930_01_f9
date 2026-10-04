package com.evops.common;

public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.message = "OK";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> fail(String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.message = message;
        return response;
    }

    /** V9：携带数据的失败响应（如设备报文验签失败已入隔离队列，回带隔离单号/原因码） */
    public static <T> ApiResponse<T> status(boolean success, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = success;
        response.message = message;
        response.data = data;
        return response;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}
