package com.hodadak.backend.common.domain;

public enum ErrorCode {
    VALIDATION_FAILED(400, "요청 값을 확인해 주세요."),
    AUTH_REQUIRED(401, "로그인이 필요합니다."),
    FORBIDDEN(403, "접근 권한이 없습니다."),
    NOT_FOUND(404, "요청한 정보를 찾을 수 없습니다.");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int status() { return status; }
    public String message() { return message; }
}
