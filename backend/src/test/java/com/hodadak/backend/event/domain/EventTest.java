package com.hodadak.backend.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hodadak.backend.common.exception.BusinessException;
import com.hodadak.backend.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// 도메인 규칙(SPEC 5.1)과 상태 머신(SPEC 6.1). Spring도 Docker도 안 쓴다
class EventTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final Instant OPEN_AT = NOW.plus(1, ChronoUnit.DAYS);
    private static final Instant CLOSE_AT = NOW.plus(2, ChronoUnit.DAYS);
    private static final Instant PICKUP_START_AT = NOW.plus(2, ChronoUnit.DAYS);
    private static final Instant PICKUP_END_AT = NOW.plus(3, ChronoUnit.DAYS);

    // ===================== 생성 =====================

    @Test
    @DisplayName("생성하면 DRAFT 상태이고 기본값이 채워진다")
    void 생성_기본값() {
        Event event = draft().build();

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getReservedCount()).isZero();
        assertThat(event.getPerUserLimit()).isEqualTo(1);
        assertThat(event.getOfferWindowMin()).isEqualTo(30);
        assertThat(event.getDescription()).isEmpty();
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
        assertThat(event.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("R-E2: waitlistCapacity를 주지 않으면 capacity와 같다")
    void 대기_정원_기본값() {
        Event event = draft().capacity(250).build();

        assertThat(event.getWaitlistCapacity()).isEqualTo(250);
    }

    @Test
    @DisplayName("R-E8: shareCode는 8자리 영숫자이고 매번 다르다")
    void 공유_코드() {
        Event one = draft().build();
        Event other = draft().build();

        assertThat(one.getShareCode()).matches("[0-9A-Za-z]{8}");
        assertThat(one.getShareCode()).isNotEqualTo(other.getShareCode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("시각_순서_위반")
    @DisplayName("R-E1: 시각 순서가 어긋나면 거부한다")
    void 시각_순서_위반(String 설명, Instant openAt, Instant closeAt,
        Instant pickupStartAt, Instant pickupEndAt) {

        assertThatThrownBy(() -> draft()
            .openAt(openAt)
            .closeAt(closeAt)
            .pickupStartAt(pickupStartAt)
            .pickupEndAt(pickupEndAt)
            .build())
            .isInstanceOf(BusinessException.class)
            .extracting(EventTest::errorCodeOf)
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    static Stream<Arguments> 시각_순서_위반() {
        return Stream.of(
            Arguments.of("openAt == closeAt",
                OPEN_AT, OPEN_AT, PICKUP_START_AT, PICKUP_END_AT),
            Arguments.of("openAt > closeAt",
                CLOSE_AT, OPEN_AT, PICKUP_START_AT, PICKUP_END_AT),
            Arguments.of("closeAt > pickupEndAt",
                OPEN_AT, PICKUP_END_AT.plus(1, ChronoUnit.HOURS), PICKUP_START_AT, PICKUP_END_AT),
            Arguments.of("pickupStartAt == pickupEndAt",
                OPEN_AT, CLOSE_AT, PICKUP_END_AT, PICKUP_END_AT),
            Arguments.of("pickupStartAt > pickupEndAt",
                OPEN_AT, CLOSE_AT, PICKUP_END_AT.plus(1, ChronoUnit.HOURS), PICKUP_END_AT));
    }

    @Test
    @DisplayName("R-E1: closeAt == pickupEndAt은 허용한다")
    void 마감과_수령_종료가_같으면_통과() {
        assertThatCode(() -> draft()
            .closeAt(PICKUP_END_AT)
            .pickupStartAt(PICKUP_START_AT)
            .pickupEndAt(PICKUP_END_AT)
            .build())
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10_000})
    @DisplayName("R-E2: capacity 경계값은 통과한다")
    void 정원_경계_통과(int capacity) {
        assertThatCode(() -> draft().capacity(capacity).build()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10_001})
    @DisplayName("R-E2: capacity가 범위를 벗어나면 거부한다")
    void 정원_범위_위반(int capacity) {
        assertValidationFailed(() -> draft().capacity(capacity).build());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10_001})
    @DisplayName("R-E2: waitlistCapacity가 범위를 벗어나면 거부한다")
    void 대기_정원_범위_위반(int waitlistCapacity) {
        assertValidationFailed(() -> draft().waitlistCapacity(waitlistCapacity).build());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    @DisplayName("R-E2: waitlistCapacity 경계값은 통과한다 (0은 대기 없음)")
    void 대기_정원_경계_통과(int waitlistCapacity) {
        assertThatCode(() -> draft().waitlistCapacity(waitlistCapacity).build())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R-E3: FREE인데 price가 있으면 거부한다")
    void 무료인데_금액_있음() {
        assertValidationFailed(() -> draft()
            .type(EventType.FREE)
            .price(BigDecimal.valueOf(1000))
            .build());
    }

    @Test
    @DisplayName("R-E3: ONSITE_PAY인데 price가 없으면 거부한다")
    void 유료인데_금액_없음() {
        assertValidationFailed(() -> draft()
            .type(EventType.ONSITE_PAY)
            .price(null)
            .build());
    }

    @Test
    @DisplayName("R-E3: ONSITE_PAY인데 price가 음수면 거부한다")
    void 유료인데_금액_음수() {
        assertValidationFailed(() -> draft()
            .type(EventType.ONSITE_PAY)
            .price(BigDecimal.valueOf(-1))
            .build());
    }

    @Test
    @DisplayName("R-E3: ONSITE_PAY + price 0 이상은 통과한다")
    void 유료_금액_통과() {
        Event event = draft()
            .type(EventType.ONSITE_PAY)
            .price(BigDecimal.ZERO)
            .build();

        assertThat(event.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 1_441})
    @DisplayName("offerWindowMin이 5~1440을 벗어나면 거부한다 (ck_offer_window)")
    void 승계_기한_범위_위반(int offerWindowMin) {
        assertValidationFailed(() -> draft().offerWindowMin(offerWindowMin).build());
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 1_440})
    @DisplayName("offerWindowMin 경계값은 통과한다")
    void 승계_기한_경계_통과(int offerWindowMin) {
        assertThatCode(() -> draft().offerWindowMin(offerWindowMin).build())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("필수 값이 비면 거부한다")
    void 필수_값_누락() {
        assertValidationFailed(() -> draft().hostId(null).build());
        assertValidationFailed(() -> draft().title(null).build());
        assertValidationFailed(() -> draft().location(null).build());
        assertValidationFailed(() -> draft().type(null).build());
        assertValidationFailed(() -> draft().createdAt(null).build());
        assertValidationFailed(() -> draft().openAt(null).build());
    }

    // ===================== 상태 전이 (SPEC 6.1) =====================

    // SPEC 6.1 전이 표를 여기 한 번 더 적어 두고 구현이 맞는지 본다.
    // 표에 없는 조합은 전부 INVALID_STATE여야 한다
    private enum Action {
        PUBLISH(event -> event.publish(NOW), EventStatus.SCHEDULED,
            EnumSet.of(EventStatus.DRAFT)),
        OPEN(event -> event.open(OPEN_AT), EventStatus.OPEN,
            EnumSet.of(EventStatus.SCHEDULED)),
        CLOSE(event -> event.close(CLOSE_AT), EventStatus.CLOSED,
            EnumSet.of(EventStatus.OPEN)),
        END(event -> event.end(PICKUP_END_AT), EventStatus.ENDED,
            EnumSet.of(EventStatus.CLOSED)),
        CANCEL(event -> event.cancel(NOW), EventStatus.CANCELED,
            EnumSet.of(EventStatus.DRAFT, EventStatus.SCHEDULED,
                EventStatus.OPEN, EventStatus.CLOSED));

        private final Consumer<Event> call;
        private final EventStatus next;
        private final Set<EventStatus> allowedFrom;

        Action(Consumer<Event> call, EventStatus next, Set<EventStatus> allowedFrom) {
            this.call = call;
            this.next = next;
            this.allowedFrom = allowedFrom;
        }
    }

    static Stream<Arguments> 전이_조합() {
        return Stream.of(EventStatus.values())
            .flatMap(from -> Stream.of(Action.values()).map(action -> Arguments.of(from, action)));
    }

    @ParameterizedTest(name = "{0} - {1}")
    @MethodSource("전이_조합")
    @DisplayName("SPEC 6.1 전이 표를 그대로 따른다")
    void 전이_표(EventStatus from, Action action) {
        Event event = eventAt(from);

        if (action.allowedFrom.contains(from)) {
            action.call.accept(event);
            assertThat(event.getStatus()).isEqualTo(action.next);
            return;
        }

        assertThatThrownBy(() -> action.call.accept(event))
            .isInstanceOf(BusinessException.class)
            .extracting(EventTest::errorCodeOf)
            .isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(event.getStatus()).isEqualTo(from);
    }

    @Test
    @DisplayName("전이하면 updatedAt이 갱신되고 createdAt은 그대로다")
    void 전이_시각_갱신() {
        Event event = draft().build();

        event.publish(NOW.plus(1, ChronoUnit.HOURS));

        assertThat(event.getUpdatedAt()).isEqualTo(NOW.plus(1, ChronoUnit.HOURS));
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("R-E1: openAt이 이미 지났으면 공개할 수 없다")
    void 공개_과거_시각_거부() {
        Event event = draft().build();

        assertValidationFailed(() -> event.publish(OPEN_AT));
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    @DisplayName("openAt에 도달하지 않으면 열 수 없다")
    void 오픈_시각_미도달() {
        Event event = eventAt(EventStatus.SCHEDULED);

        assertInvalidState(() -> event.open(OPEN_AT.minusMillis(1)));
    }

    @Test
    @DisplayName("closeAt에 도달하지 않으면 마감할 수 없다")
    void 마감_시각_미도달() {
        Event event = eventAt(EventStatus.OPEN);

        assertInvalidState(() -> event.close(CLOSE_AT.minusMillis(1)));
    }

    @Test
    @DisplayName("pickupEndAt에 도달하지 않으면 종료할 수 없다")
    void 종료_시각_미도달() {
        Event event = eventAt(EventStatus.CLOSED);

        assertInvalidState(() -> event.end(PICKUP_END_AT.minusMillis(1)));
    }

    @Test
    @DisplayName("DRAFT만 삭제할 수 있다")
    void 삭제_가능_상태() {
        assertThat(eventAt(EventStatus.DRAFT).isDeletable()).isTrue();
        assertThat(eventAt(EventStatus.SCHEDULED).isDeletable()).isFalse();
        assertThat(eventAt(EventStatus.OPEN).isDeletable()).isFalse();
        assertThat(eventAt(EventStatus.CLOSED).isDeletable()).isFalse();
        assertThat(eventAt(EventStatus.ENDED).isDeletable()).isFalse();
        assertThat(eventAt(EventStatus.CANCELED).isDeletable()).isFalse();
    }

    // ===================== 헬퍼 =====================

    private static Event.EventBuilder draft() {
        return Event.builder()
            .hostId(1L)
            .title("호다닥 간식 나눔")
            .location("학생회관 101호")
            .type(EventType.FREE)
            .capacity(100)
            .openAt(OPEN_AT)
            .closeAt(CLOSE_AT)
            .pickupStartAt(PICKUP_START_AT)
            .pickupEndAt(PICKUP_END_AT)
            .createdAt(NOW);
    }

    private static Event eventAt(EventStatus status) {
        Event event = draft().build();
        switch (status) {
            case DRAFT -> {
            }
            case CANCELED -> event.cancel(NOW);
            case SCHEDULED -> event.publish(NOW);
            case OPEN -> {
                event.publish(NOW);
                event.open(OPEN_AT);
            }
            case CLOSED -> {
                event.publish(NOW);
                event.open(OPEN_AT);
                event.close(CLOSE_AT);
            }
            case ENDED -> {
                event.publish(NOW);
                event.open(OPEN_AT);
                event.close(CLOSE_AT);
                event.end(PICKUP_END_AT);
            }
        }
        assertThat(event.getStatus()).isEqualTo(status);
        return event;
    }

    private static ErrorCode errorCodeOf(Throwable thrown) {
        return ((BusinessException) thrown).getErrorCode();
    }

    private static void assertValidationFailed(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BusinessException.class)
            .extracting(EventTest::errorCodeOf)
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static void assertInvalidState(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BusinessException.class)
            .extracting(EventTest::errorCodeOf)
            .isEqualTo(ErrorCode.INVALID_STATE);
    }
}
