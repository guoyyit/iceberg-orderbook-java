package com.github.guoyyit.icebergorderbookjava.web;

import com.github.guoyyit.icebergorderbookjava.dto.ApiResponse;
import com.github.guoyyit.icebergorderbookjava.model.MatchingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps business rejections to distinguishable error codes. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MatchingException.class)
    public ResponseEntity<ApiResponse<Void>> matching(MatchingException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getErrorCode().name(), ex.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> other(Exception ex) {
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("INTERNAL_ERROR", ex.getMessage(), null));
    }
}
