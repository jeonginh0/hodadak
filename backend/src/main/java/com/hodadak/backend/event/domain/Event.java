package com.hodadak.backend.event.domain;

import com.hodadak.backend.common.exception.BusinessException;
import com.hodadak.backend.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    private static final char[] SHARE_CODE_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int SHARE_CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<EventStatus> CANCELABLE =
        EnumSet.of(EventStatus.DRAFT, EventStatus.SCHEDULED, EventStatus.OPEN, EventStatus.CLOSED);

    // DB CHECK 제약과 같은 값 (SPEC 5.1, 7)
    private static final int MIN_CAPACITY = 1;
    private static final int MAX_CAPACITY = 10_000;
    private static final int MIN_WAITLIST_CAPACITY = 0;
    private static final int MAX_WAITLIST_CAPACITY = 10_000;
    private static final int MIN_OFFER_WINDOW_MIN = 5;
    private static final int MAX_OFFER_WINDOW_MIN = 1_440;
    private static final int DEFAULT_OFFER_WINDOW_MIN = 30;
    private static final int DEFAULT_PER_USER_LIMIT = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "host_id", nullable = false, updatable = false)
    private Long hostId;

    @Column(name = "share_code", nullable = false, length = 16, updatable = false)
    private String shareCode;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType type;

    @Column(precision = 12, scale = 0)
    private BigDecimal price;

    @Column(nullable = false, length = 200)
    private String location;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "waitlist_capacity", nullable = false)
    private int waitlistCapacity;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;

    @Column(name = "open_at", nullable = false)
    private Instant openAt;

    @Column(name = "close_at", nullable = false)
    private Instant closeAt;

    @Column(name = "pickup_start_at", nullable = false)
    private Instant pickupStartAt;

    @Column(name = "pickup_end_at", nullable = false)
    private Instant pickupEndAt;

    @Column(name = "offer_window_min", nullable = false)
    private int offerWindowMin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // DRAFT 이벤트를 만든다. 시각이 미래인지는 publish에서 본다 (초안은 과거 시각도 허용)
    @Builder
    private Event(Long hostId, String title, String description, String imageUrl,
        EventType type, BigDecimal price, String location,
        int capacity, Integer waitlistCapacity, Integer perUserLimit,
        Instant openAt, Instant closeAt, Instant pickupStartAt, Instant pickupEndAt,
        Integer offerWindowMin, Instant createdAt) {

        requirePresent(hostId, title, location, type, createdAt);
        validateTimeOrder(openAt, closeAt, pickupStartAt, pickupEndAt);
        validateCapacity(capacity, waitlistCapacity);
        validatePrice(type, price);

        int window = offerWindowMin != null ? offerWindowMin : DEFAULT_OFFER_WINDOW_MIN;
        int limit = perUserLimit != null ? perUserLimit : DEFAULT_PER_USER_LIMIT;
        validateOfferWindow(window);
        validatePerUserLimit(limit);

        this.hostId = hostId;
        this.shareCode = generateShareCode();
        this.title = title;
        this.description = description != null ? description : "";
        this.imageUrl = imageUrl;
        this.type = type;
        this.price = price;
        this.location = location;
        this.capacity = capacity;
        this.waitlistCapacity = waitlistCapacity != null ? waitlistCapacity : capacity;  // R-E2
        this.reservedCount = 0;
        this.perUserLimit = limit;
        this.openAt = openAt;
        this.closeAt = closeAt;
        this.pickupStartAt = pickupStartAt;
        this.pickupEndAt = pickupEndAt;
        this.offerWindowMin = window;
        this.status = EventStatus.DRAFT;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // ---------- 상태 전이 (SPEC 6.1) ----------

    // DRAFT → SCHEDULED. 시각이 미래여야 한다 (R-E1)
    public void publish(Instant now) {
        requireStatus(EventStatus.DRAFT);
        if (!openAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        transitionTo(EventStatus.SCHEDULED, now);
    }

    // SCHEDULED → OPEN. 스케줄러가 부른다
    public void open(Instant now) {
        requireStatus(EventStatus.SCHEDULED);
        requireReached(openAt, now);
        transitionTo(EventStatus.OPEN, now);
    }

    // OPEN → CLOSED. 스케줄러가 부른다
    public void close(Instant now) {
        requireStatus(EventStatus.OPEN);
        requireReached(closeAt, now);
        transitionTo(EventStatus.CLOSED, now);
    }

    // CLOSED → ENDED. 노쇼 처리(R-T5)는 apply 모듈이 한다
    public void end(Instant now) {
        requireStatus(EventStatus.CLOSED);
        requireReached(pickupEndAt, now);
        transitionTo(EventStatus.ENDED, now);
    }

    // → CANCELED (R-E7). 활성 신청 취소는 apply 모듈이 한다
    public void cancel(Instant now) {
        if (!CANCELABLE.contains(status)) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        transitionTo(EventStatus.CANCELED, now);
    }

    public boolean isDeletable() {
        return status == EventStatus.DRAFT;
    }

    // ---------- 내부 ----------
    private void transitionTo(EventStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    private void requireStatus(EventStatus expected) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
    }

    private static void requireReached(Instant threshold, Instant now) {
        if (now.isBefore(threshold)) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
    }

    private static void requirePresent(Object... values) {
        for (Object value : values) {
            if (value == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    // R-E1: openAt < closeAt <= pickupEndAt, pickupStartAt < pickupEndAt
    private static void validateTimeOrder(Instant openAt, Instant closeAt,
        Instant pickupStartAt, Instant pickupEndAt) {
        requirePresent(openAt, closeAt, pickupStartAt, pickupEndAt);
        if (!openAt.isBefore(closeAt)
            || closeAt.isAfter(pickupEndAt)
            || !pickupStartAt.isBefore(pickupEndAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    // R-E2: capacity 1~10000, waitlistCapacity 0~10000
    private static void validateCapacity(int capacity, Integer waitlistCapacity) {
        int waitlist = waitlistCapacity != null ? waitlistCapacity : capacity;
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY
            || waitlist < MIN_WAITLIST_CAPACITY || waitlist > MAX_WAITLIST_CAPACITY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    // R-E3: FREE면 price 없음, ONSITE_PAY면 price 0 이상
    private static void validatePrice(EventType type, BigDecimal price) {
        boolean valid = switch (type) {
            case FREE -> price == null;
            case ONSITE_PAY -> price != null && price.signum() >= 0;
        };
        if (!valid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    // ck_offer_window: 5~1440분
    private static void validateOfferWindow(int offerWindowMin) {
        if (offerWindowMin < MIN_OFFER_WINDOW_MIN || offerWindowMin > MAX_OFFER_WINDOW_MIN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static void validatePerUserLimit(int perUserLimit) {
        if (perUserLimit < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    // R-E8: 추측 어려운 8자리 영숫자 (대소문자 구분)
    private static String generateShareCode() {
        char[] code = new char[SHARE_CODE_LENGTH];
        for (int i = 0; i < code.length; i++) {
            code[i] = SHARE_CODE_ALPHABET[RANDOM.nextInt(SHARE_CODE_ALPHABET.length)];
        }
        return new String(code);
    }
}
