package com.github.guoyyit.icebergorderbookjava.model;

/** Business rejection; carries a distinguishable ErrorCode. */
public class MatchingException extends RuntimeException {

    private final ErrorCode errorCode;

    public MatchingException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
