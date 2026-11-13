package com.hodadak.backend.common.exception;

import org.springframework.http.HttpStatus;

// SPEC 9.2. 여기 없는 코드는 쓰지 않는다
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    ADMISSION_REQUIRED(HttpStatus.FORBIDDEN),       // 입장 토큰 없음·불일치·만료
    USER_RESTRICTED(HttpStatus.FORBIDDEN),          // 노쇼로 신청 제한 중
    HOST_CANNOT_APPLY(HttpStatus.FORBIDDEN),        // R-E6
    NOT_FOUND(HttpStatus.NOT_FOUND),
    EVENT_NOT_OPEN(HttpStatus.CONFLICT),            // 오픈 전
    EVENT_CLOSED(HttpStatus.CONFLICT),              // 마감·종료·취소됨
    EVENT_FULL(HttpStatus.CONFLICT),                // R-A5, 자리와 대기 모두 없음
    ALREADY_APPLIED(HttpStatus.CONFLICT),           // R-A1
    EVENT_NOT_EDITABLE(HttpStatus.CONFLICT),        // R-E5
    INVALID_STATE(HttpStatus.CONFLICT),             // 허용되지 않은 상태 전이
    OFFER_EXPIRED(HttpStatus.CONFLICT),             // 승계 수락 기한 초과
    PHONE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT), // R-E4
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS);     // Retry-After 헤더는 전역 핸들러가 붙인다

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
