package com.github.guoyyit.icebergorderbookjava.dto;

/** Uniform envelope. ok=false carries a distinguishable errorCode. */
public record ApiResponse<T>(boolean ok, T data, String errorCode, String message, String requestId) {

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(true, data, null, null, requestId);
    }

    public static <T> ApiResponse<T> error(String errorCode, String message, String requestId) {
        return new ApiResponse<>(false, null, errorCode, message, requestId);
    }
}
