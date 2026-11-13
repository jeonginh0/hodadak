package com.hodadak.backend.common.exception;

// 업무 규칙 위반. 전역 핸들러가 SPEC 9.1 형식으로 바꾼다
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
