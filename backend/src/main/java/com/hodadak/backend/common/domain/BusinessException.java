package com.hodadak.backend.common.domain;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public ErrorCode code() { return code; }

    public static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.AUTH_REQUIRED);
    }
}
