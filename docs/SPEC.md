# 호다닥(Hodadak) 제품·기술 명세

> 버전 1.2 · 2026-11-03
> 이 문서는 구현의 기준이다. 문서와 코드가 다르면 문서를 먼저 고친 뒤 코드를 바꾼다.
> 제품 배경, 화면 목록, 역할 분담, 일정은 `docs/PRD.md`에 있다. 둘이 다르면 함께 고친다.

## 목차
1. 개요
2. 범위
3. 용어
4. 사용자와 시나리오
5. 도메인 규칙
6. 상태 머신
7. 데이터베이스 (DDL)
8. Redis 설계
9. API 명세
10. 이벤트 메시징 (Outbox, Kafka)
11. 핵심 처리 흐름
12. 화면 명세
13. 비기능 요구사항과 테스트
14. 마일스톤
15. 인프라와 배포
16. 결정 로그와 열린 질문

---

## 1. 개요

호다닥은 동아리, 학생회 같은 소규모 주최자가 **한정 수량 이벤트**를 열면, 참여자가 링크로 들어와 **선착순으로 신청**하고, 현장에서 **QR로 수령·입장**하는 웹 서비스다.

- 이벤트 유형은 두 가지다.
  - `FREE`: 무료 신청형 (굿즈 나눔, 특강, 좌석, 스터디 모집)
  - `ONSITE_PAY`: 현장 결제 예약형 (축제 부스, 동아리 굿즈 판매). 가격은 표시만 하고 **호다닥은 결제를 처리하지 않는다.**
- 팀: 2명 (풀스택 1, 백엔드 1), 기간 8주. 웹으로 먼저 출시하고, 이후 React Native iOS 앱(주최자용)으로 확장한다.
- 배포: 백엔드, 프론트엔드, 데이터 저장소 모두 Oracle Cloud 무료 티어 Ampere A1 VM 1대(arm64, 2 OCPU, 12GB)의 k3s 클러스터에 올린다. 하나의 도메인에서 `/api`는 백엔드, 나머지는 프론트엔드가 받는다 (§15).
- 프로젝트 목표
  1. 어떤 부하에서도 초과 확정 0건, 중복 확정 0건
  2. 실제 행사 1건 이상 운영
  3. 동시성 처리 방식 비교와 트러블슈팅 기록

## 2. 범위

### 2.1 MVP 포함
| ID | 기능 |
| --- | --- |
| F1 | 카카오 로그인, JWT 인증 |
| F2 | 이벤트 생성·수정·공개·취소 |
| F3 | 공개 이벤트 페이지 (OG 메타, 서버 시각 기준 카운트다운) |
| F4 | 선착순 신청 (초과·중복 방지) |
| F5 | 대기열 (순번 표시, 입장 토큰) |
| F6 | 대기자 등록, 취소·만료 시 자동 승계 제안 |
| F7 | 신청 취소, 승계 제안 수락·포기 |
| F8 | QR 티켓 발급, 주최자 QR 스캔·수동 체크인 |
| F9 | 주최자 현황판, 참여자 명단 |
| F10 | 이벤트 종료 처리 (미수령 노쇼 기록) |
| F11 | 레이트 리밋 |
| F12 | 이미지 업로드 (이벤트당 1장) |

### 2.2 이후 단계 (구현 금지, 설계만 고려)
- 노쇼 누적 시 신청 제한 (필드는 MVP에 존재, 로직은 이후)
- 공동 주최자
- 알림 (푸시, 이메일)
- 관리자 화면 (MVP는 DB 조회와 Grafana로 대체)
- React Native iOS 앱

### 2.3 범위 밖
- 온라인 결제, 정산, 수수료
- 좌석 지정
- 문자·알림톡 등 유료 발송

## 3. 용어
| 용어 | 정의 |
| --- | --- |
| 주최자 (host) | 이벤트를 만든 사용자. 별도 가입 없이 누구나 주최자가 될 수 있다 |
| 참여자 | 이벤트에 신청한 사용자 |
| 수량 (capacity) | 확정 가능한 최대 인원 |
| 점유 (reserved) | 수량을 차지한 신청. 상태가 `OFFERED`, `CONFIRMED`, `USED`, `NO_SHOW`인 신청 |
| 대기자 | 수량이 찬 뒤 신청해 `WAITING` 상태인 신청 |
| 승계 제안 (offer) | 자리가 나서 대기자에게 기한부로 자리를 제안한 상태 (`OFFERED`) |
| 대기열 (queue) | 오픈 순간 몰린 요청을 순서대로 신청 API에 들여보내는 장치. 대기자 명단과 다르다 |
| 입장 토큰 (admission token) | 대기열을 통과한 사용자에게 주는 단기 토큰. 신청 API 호출에 필요 |
| 티켓 | 확정 신청에 발급되는 QR. 1회만 사용 가능 |

## 4. 사용자와 시나리오

### 4.1 사용자
| 사용자 | 환경 | 주요 행동 |
| --- | --- | --- |
| 주최자 | 모바일(현장 스캔), PC(이벤트 관리) | 이벤트 생성, 링크 공유, 현황 확인, QR 스캔 |
| 참여자 | 모바일 브라우저 (카톡·인스타 인앱 브라우저 포함) | 오픈 대기, 신청, 대기, 수락, QR 제시, 취소 |

### 4.2 핵심 시나리오
1. 주최자가 이벤트를 만들고 공개한다.
2. 주최자가 공유 링크(`/e/{shareCode}`)를 퍼뜨린다. 링크 미리보기에 제목, 이미지, 오픈 시각이 보인다.
3. 참여자가 링크로 들어와 로그인하고 카운트다운을 본다.
4. 오픈 시각에 "신청하기"를 누르면 대기열에 들어가고 순번을 본다.
5. 차례가 오면 입장 토큰을 받고 자동으로 신청이 전송된다.
6. 결과는 확정(QR 발급), 대기자 등록(대기 번호), 마감 중 하나다.
7. 확정자가 취소하면 가장 앞 대기자에게 승계 제안이 간다. 대기자는 기한 안에 수락하면 확정, 넘기면 다음 대기자로 넘어간다.
8. 현장에서 주최자가 QR을 스캔해 수령·입장 처리한다.
9. 수령 기간이 끝나면 미수령 확정자는 노쇼로 기록된다.

## 5. 도메인 규칙

### 5.1 이벤트
- R-E1. 시각 순서: `open_at < close_at <= pickup_end_at`, `pickup_start_at < pickup_end_at`. 모두 미래여야 공개할 수 있다.
- R-E2. `capacity`는 1 이상 10,000 이하. `waitlist_capacity`는 0 이상 10,000 이하, 기본값은 `capacity`와 같다.
- R-E3. `FREE`면 `price`는 NULL, `ONSITE_PAY`면 `price >= 0` 필수.
- R-E4. `ONSITE_PAY` 이벤트는 신청자의 전화번호가 필요하다. 없으면 신청 시 `PHONE_REQUIRED` 오류.
- R-E5. 수정 가능 범위
  - `DRAFT`: 전부
  - `SCHEDULED`: `type` 제외 전부
  - `OPEN` 이후: `description`, `image_url`, `location`만
- R-E6. 주최자는 자신의 이벤트에 신청할 수 없다.
- R-E7. 주최자가 이벤트를 취소하면 모든 활성 신청(`WAITING`, `OFFERED`, `CONFIRMED`)은 `CANCELED`(`cancel_reason = EVENT_CANCELED`)가 되고 승계는 일어나지 않는다.
- R-E8. `share_code`는 8자리 영숫자(대소문자 구분), 추측하기 어렵게 무작위 생성한다.

### 5.2 신청
- R-A1. 한 사용자는 한 이벤트에 **평생 1건만** 신청할 수 있다. 취소해도 재신청 불가. (`UNIQUE(event_id, user_id)`)
- R-A2. 신청은 `OPEN` 상태에서만, 유효한 입장 토큰이 있을 때만 받는다.
- R-A3. 점유 수가 `capacity` 미만이면 `CONFIRMED`로 생성하고 티켓을 발급한다.
- R-A4. 점유가 가득 차고 대기자 수가 `waitlist_capacity` 미만이면 `WAITING`으로 생성하고 `wait_no`(1부터 증가)를 부여한다.
- R-A5. 둘 다 가득 차면 `EVENT_FULL`로 거부한다. 레코드는 만들지 않는다.
- R-A6. 같은 `Idempotency-Key`로 다시 요청하면 처음 결과를 그대로 돌려준다.
- R-A7. `events.reserved_count`는 항상 점유 상태 신청 수와 같아야 하며 `capacity`를 넘지 않는다 (DB CHECK 제약으로 2차 방어).

### 5.3 취소와 승계
- R-C1. 참여자는 `WAITING`, `OFFERED`, `CONFIRMED` 상태를 `pickup_end_at` 전까지 취소할 수 있다. `USED` 이후는 불가.
- R-C2. `CONFIRMED` 또는 `OFFERED`가 취소되거나 `OFFERED`가 만료되면 자리가 반납된다(`SEAT_RELEASED` 이벤트).
- R-C3. 자리가 반납되면 `wait_no`가 가장 작은 `WAITING` 신청을 `OFFERED`로 바꾸고 `offer_expires_at = now + offer_window_min`을 설정한다.
- R-C4. 승계는 `pickup_end_at - offer_window_min` 이전에만 일어난다. 그 이후 반납된 자리는 승계하지 않는다.
- R-C5. 대기자가 없고 이벤트가 `OPEN`이면 Redis 재고를 1 늘려 새 신청자가 가져갈 수 있게 한다.
- R-C6. `OFFERED`를 수락하면 `CONFIRMED`가 되고 티켓이 발급된다. 포기하면 `CANCELED`(R-C2 적용).
- R-C7. `WAITING` 취소는 자리에 영향이 없다.

### 5.4 체크인과 종료
- R-T1. 티켓은 확정 신청 1건당 1장, 1회만 사용 가능하다.
- R-T2. 스캔은 해당 이벤트의 주최자만 할 수 있고, `pickup_start_at`부터 `pickup_end_at`까지만 성공한다. 단 주최자 편의를 위해 시작 30분 전부터 허용한다(설정값).
- R-T3. 모든 스캔 시도는 결과와 함께 `check_in_logs`에 기록한다.
- R-T4. 수동 체크인(이름·번호 검색)도 같은 규칙과 로그를 따른다.
- R-T5. `pickup_end_at`이 지나면 이벤트는 `ENDED`. `CONFIRMED`는 `NO_SHOW`가 되고 사용자 `no_show_count`가 1 증가한다. `WAITING`, `OFFERED`는 `EXPIRED`가 된다.

### 5.5 기본 설정값
| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `hodadak.offer.window-minutes` | 30 | 이벤트별 기본 승계 수락 기한 |
| `hodadak.queue.admit-per-second` | 200 | 이벤트당 초당 입장 인원 |
| `hodadak.queue.admission-ttl-seconds` | 60 | 입장 토큰 유효 시간 |
| `hodadak.queue.poll-interval-ms` | 2000 | 클라이언트 순번 조회 간격 |
| `hodadak.checkin.early-minutes` | 30 | 수령 시작 전 스캔 허용 시간 |
| `hodadak.jwt.access-ttl-minutes` | 30 | |
| `hodadak.jwt.refresh-ttl-days` | 14 | |

## 6. 상태 머신

### 6.1 이벤트 (`events.status`)
| 현재 | 다음 | 트리거 |
| --- | --- | --- |
| DRAFT | SCHEDULED | 주최자 공개 (R-E1 검증) |
| SCHEDULED | OPEN | 스케줄러, `now >= open_at` |
| OPEN | CLOSED | 스케줄러, `now >= close_at` |
| CLOSED | ENDED | 스케줄러, `now >= pickup_end_at` (R-T5 처리) |
| DRAFT, SCHEDULED, OPEN, CLOSED | CANCELED | 주최자 취소 (R-E7) |

DRAFT는 삭제할 수 있다. 그 외 상태는 삭제하지 않는다.

### 6.2 신청 (`applications.status`)
| 현재 | 다음 | 트리거 | 자리 변화 |
| --- | --- | --- | --- |
| (없음) | CONFIRMED | 신청, 자리 있음 | +1 |
| (없음) | WAITING | 신청, 자리 없음, 대기 여유 있음 | 0 |
| WAITING | OFFERED | 승계 워커 | +1 |
| WAITING | CANCELED | 참여자 취소 | 0 |
| WAITING | EXPIRED | 이벤트 종료 | 0 |
| OFFERED | CONFIRMED | 참여자 수락 | 0 |
| OFFERED | CANCELED | 참여자 포기·취소 | −1, 승계 |
| OFFERED | EXPIRED | 기한 초과(스케줄러) 또는 이벤트 종료 | −1, 승계 |
| CONFIRMED | CANCELED | 참여자 취소 | −1, 승계 |
| CONFIRMED | USED | 체크인 | 0 |
| CONFIRMED | NO_SHOW | 이벤트 종료 | 0 |
| WAITING, OFFERED, CONFIRMED | CANCELED | 이벤트 취소 | 해당 없음 (R-E7) |

정의되지 않은 전이는 `INVALID_STATE` 오류. 상태 전이는 도메인 엔티티 메서드로만 한다(`application.cancel(now)` 등).

## 7. 데이터베이스 (DDL)

Flyway `V1__init.sql` 기준. PostgreSQL 16 이상.

```sql
CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    kakao_id          VARCHAR(64)  NOT NULL UNIQUE,
    nickname          VARCHAR(50)  NOT NULL,
    phone             VARCHAR(20),
    role              VARCHAR(20)  NOT NULL DEFAULT 'USER',      -- USER, ADMIN
    no_show_count     INT          NOT NULL DEFAULT 0,
    restricted_until  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id                 BIGSERIAL PRIMARY KEY,
    host_id            BIGINT       NOT NULL REFERENCES users(id),
    share_code         VARCHAR(16)  NOT NULL UNIQUE,
    title              VARCHAR(100) NOT NULL,
    description        TEXT         NOT NULL DEFAULT '',
    image_url          VARCHAR(500),
    type               VARCHAR(20)  NOT NULL,                    -- FREE, ONSITE_PAY
    price              NUMERIC(12,0),
    location           VARCHAR(200) NOT NULL,
    capacity           INT          NOT NULL,
    waitlist_capacity  INT          NOT NULL,
    reserved_count     INT          NOT NULL DEFAULT 0,
    per_user_limit     INT          NOT NULL DEFAULT 1,
    open_at            TIMESTAMPTZ  NOT NULL,
    close_at           TIMESTAMPTZ  NOT NULL,
    pickup_start_at    TIMESTAMPTZ  NOT NULL,
    pickup_end_at      TIMESTAMPTZ  NOT NULL,
    offer_window_min   INT          NOT NULL DEFAULT 30,
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_capacity        CHECK (capacity BETWEEN 1 AND 10000),
    CONSTRAINT ck_waitlist        CHECK (waitlist_capacity BETWEEN 0 AND 10000),
    CONSTRAINT ck_reserved        CHECK (reserved_count >= 0 AND reserved_count <= capacity),
    CONSTRAINT ck_times           CHECK (open_at < close_at AND close_at <= pickup_end_at
                                         AND pickup_start_at < pickup_end_at),
    CONSTRAINT ck_price           CHECK ((type = 'FREE' AND price IS NULL)
                                      OR (type = 'ONSITE_PAY' AND price IS NOT NULL AND price >= 0)),
    CONSTRAINT ck_offer_window    CHECK (offer_window_min BETWEEN 5 AND 1440)
);
CREATE INDEX ix_events_status_open   ON events (status, open_at);
CREATE INDEX ix_events_status_close  ON events (status, close_at);
CREATE INDEX ix_events_status_pickup ON events (status, pickup_end_at);
CREATE INDEX ix_events_host          ON events (host_id, created_at DESC);

CREATE TABLE applications (
    id                BIGSERIAL PRIMARY KEY,
    event_id          BIGINT      NOT NULL REFERENCES events(id),
    user_id           BIGINT      NOT NULL REFERENCES users(id),
    status            VARCHAR(20) NOT NULL,   -- WAITING, OFFERED, CONFIRMED, USED, NO_SHOW, CANCELED, EXPIRED
    wait_no           INT,
    offered_at        TIMESTAMPTZ,
    offer_expires_at  TIMESTAMPTZ,
    confirmed_at      TIMESTAMPTZ,
    canceled_at       TIMESTAMPTZ,
    cancel_reason     VARCHAR(20),            -- USER, OFFER_DECLINED, EVENT_CANCELED
    used_at           TIMESTAMPTZ,
    idempotency_key   VARCHAR(64) NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_event_user UNIQUE (event_id, user_id),
    CONSTRAINT uq_app_idem       UNIQUE (user_id, idempotency_key)
);
CREATE INDEX ix_app_next_waiting  ON applications (event_id, wait_no) WHERE status = 'WAITING';
CREATE INDEX ix_app_offer_expire  ON applications (offer_expires_at) WHERE status = 'OFFERED';
CREATE INDEX ix_app_event_status  ON applications (event_id, status);
CREATE INDEX ix_app_user          ON applications (user_id, created_at DESC);

CREATE TABLE tickets (
    id              BIGSERIAL PRIMARY KEY,
    application_id  BIGINT       NOT NULL UNIQUE REFERENCES applications(id),
    jti             VARCHAR(64)  NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ
);

CREATE TABLE check_in_logs (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events(id),
    ticket_id   BIGINT      REFERENCES tickets(id),   -- 위조·해석 불가 토큰이면 NULL
    scanned_by  BIGINT      NOT NULL REFERENCES users(id),
    method      VARCHAR(10) NOT NULL,                 -- QR, MANUAL
    result      VARCHAR(20) NOT NULL,                 -- SUCCESS, ALREADY_USED, EXPIRED, NOT_YET, WRONG_EVENT, INVALID, NOT_CONFIRMED
    scanned_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_checkin_event ON check_in_logs (event_id, scanned_at DESC);

CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(30)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    partition_key   VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING, PUBLISHED, FAILED
    attempts        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';

CREATE TABLE processed_messages (
    consumer     VARCHAR(50)  NOT NULL,
    message_id   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- ShedLock 락 테이블 (스케줄 잡 중복 실행 방지, §11.5)
CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
```

## 8. Redis 설계

표의 `{id}`는 이벤트 ID 자리이면서 Redis 클러스터 해시태그다. 실제 키는 중괄호를 포함해 `ev:{12}:stock`처럼 저장한다. 같은 이벤트의 키가 같은 슬롯에 모여야 Lua 스크립트가 여러 키를 다룰 수 있다. 운영은 단일 Redis지만, 나중에 클러스터로 바꿔도 키 설계를 고치지 않기 위해서다.

운영 Redis는 AOF(`appendonly yes`)를 켠다. 그래도 키가 사라질 수 있으므로 재고 관련 키는 §8.1 방식으로 DB에서 다시 만들 수 있어야 한다. 리프레시 토큰이 사라지면 사용자는 다시 로그인한다.

| 키 | 타입 | 용도 | TTL |
| --- | --- | --- | --- |
| `ev:{id}:info` | Hash | 공개 이벤트 정보 캐시 | 60초 |
| `ev:{id}:stock` | String(int) | 남은 자리 수 | 이벤트 종료 + 1일 |
| `ev:{id}:applied` | Set | 신청한 userId (중복 방지) | 이벤트 종료 + 1일 |
| `ev:{id}:waitseq` | String(int) | 마지막 대기 번호 | 이벤트 종료 + 1일 |
| `ev:{id}:queue` | Sorted Set | 대기열, score = 진입 시각(ms) | 이벤트 종료 + 1일 |
| `ev:{id}:admit:{userId}` | String | 입장 토큰 | 60초 |
| `rl:{scope}:{subject}:{window}` | String(int) | 레이트 리밋 카운터 | 윈도 길이 |
| `auth:refresh:{tokenId}` | String(userId) | 리프레시 토큰 | 14일 |

### 8.1 재고 초기화
- 이벤트가 `SCHEDULED → OPEN`으로 바뀔 때 `stock = capacity - reserved_count`, `waitseq = 0`으로 설정한다.
- 재구성: worker가 시작할 때와 정합성 점검 잡이 돌 때, `OPEN` 이벤트의 키가 없으면 DB 기준으로 다시 만든다 (`stock = capacity - reserved_count`, `applied` = 해당 이벤트 신청 user_id 전체, `waitseq` = 최대 `wait_no`). 다른 요청이 쓰는 중인 키를 덮어쓰지 않도록 임시 키에 만든 뒤 `RENAMENX`로 옮긴다. api 파드는 재구성하지 않는다.
- 정합성 점검 잡(1분): `stock + 점유 수 + (승계 대기 중인 반납 수) == capacity`를 확인하고 어긋나면 경고 로그와 메트릭을 남긴다. 자동 보정은 하지 않는다 (MVP).

### 8.2 신청 Lua 스크립트 (`apply.lua`)
```
KEYS[1] = ev:{id}:stock
KEYS[2] = ev:{id}:applied
KEYS[3] = ev:{id}:waitseq
ARGV[1] = userId
ARGV[2] = waitlistCapacity

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return {'DUPLICATE', 0} end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock > 0 then
  redis.call('DECR', KEYS[1])
  redis.call('SADD', KEYS[2], ARGV[1])
  return {'CONFIRMED', 0}
end
local seq = tonumber(redis.call('GET', KEYS[3]) or '0')
if seq < tonumber(ARGV[2]) then
  local no = redis.call('INCR', KEYS[3])
  redis.call('SADD', KEYS[2], ARGV[1])
  return {'WAITING', no}
end
return {'FULL', 0}
```

### 8.3 보상 스크립트 (`apply_rollback.lua`)
DB 저장이 실패하면 호출한다. `CONFIRMED`였으면 `INCR stock`, 공통으로 `SREM applied userId`. `WAITING`이었으면 대기 번호는 되돌리지 않는다 (번호에 빈칸이 생겨도 순서는 유지됨).

## 9. API 명세

- Base path: `/api`. JSON, UTF-8. 시각은 ISO-8601 UTC(`2026-11-20T09:00:00Z`).
- 인증: `Authorization: Bearer {accessToken}`. 표시가 없는 엔드포인트는 인증 필요.
- OpenAPI 문서를 `/api/docs`로 노출한다.

### 9.1 공통 응답
성공은 리소스를 그대로 반환한다. 목록은 다음 형식이다.
```json
{ "items": [], "nextCursor": "string|null" }
```
오류 형식:
```json
{ "code": "EVENT_FULL", "message": "사람이 읽을 수 있는 한국어 메시지", "traceId": "abc123" }
```

### 9.2 오류 코드
| 코드 | HTTP | 상황 |
| --- | --- | --- |
| VALIDATION_FAILED | 400 | 요청 값 검증 실패 (`fields` 배열 포함) |
| AUTH_REQUIRED | 401 | 토큰 없음·만료 |
| FORBIDDEN | 403 | 권한 없음 (남의 이벤트 등) |
| ADMISSION_REQUIRED | 403 | 입장 토큰 없음·불일치·만료 |
| USER_RESTRICTED | 403 | 신청 제한 중 (이후 단계) |
| HOST_CANNOT_APPLY | 403 | 주최자 본인 신청 |
| NOT_FOUND | 404 | 리소스 없음 |
| EVENT_NOT_OPEN | 409 | 오픈 전 |
| EVENT_CLOSED | 409 | 마감·종료·취소됨 |
| EVENT_FULL | 409 | 자리·대기 모두 없음 |
| ALREADY_APPLIED | 409 | 이미 신청함 (다른 Idempotency-Key) |
| EVENT_NOT_EDITABLE | 409 | 현재 상태에서 수정 불가 필드 |
| INVALID_STATE | 409 | 허용되지 않은 상태 전이 |
| OFFER_EXPIRED | 409 | 승계 제안 기한 초과 |
| PHONE_REQUIRED | 422 | 예약형인데 전화번호 없음 |
| RATE_LIMITED | 429 | 요청 과다 (`Retry-After` 헤더) |

### 9.3 인증
| 메서드·경로 | 설명 |
| --- | --- |
| `POST /auth/kakao` (공개) | 카카오 인가 코드로 로그인. 요청 `{code, redirectUri}`. 응답 `{accessToken, user}`, 리프레시 토큰은 `HttpOnly; Secure; SameSite=Lax` 쿠키 |
| `POST /auth/refresh` (공개, 쿠키) | 액세스 토큰 재발급, 리프레시 토큰 회전 |
| `POST /auth/logout` | 리프레시 토큰 폐기 |
| `GET /me` | `{id, nickname, phone, noShowCount}` |
| `PATCH /me` | `{nickname?, phone?}`. 전화번호는 숫자 10~11자리 |

카카오 흐름: 프론트가 카카오 인가 페이지로 이동 → 카카오가 `/auth/kakao/callback?code=`로 돌려보냄 → 프론트가 `POST /auth/kakao` 호출 → 백엔드가 카카오 토큰 교환과 사용자 조회 후 `kakao_id`로 upsert.

프론트와 API는 같은 오리진(`https://{DOMAIN}`)에서 제공되므로 리프레시 토큰 쿠키는 별도 CORS 설정 없이 전송된다. 쿠키 `Path`는 `/api/auth`로 제한한다. 로컬 개발에서는 Next.js 개발 서버의 rewrites로 `/api`를 백엔드로 넘겨 같은 오리진을 유지한다.

### 9.4 공개
| 메서드·경로 | 설명 |
| --- | --- |
| `GET /public/events/{shareCode}` | 공개 이벤트 정보. `DRAFT`는 404 |
| `GET /public/time` | 서버 현재 시각 `{now}` (카운트다운 보정용) |

`GET /public/events/{shareCode}` 응답:
```json
{
  "id": 12, "shareCode": "aZ3kP9qL",
  "title": "string", "description": "string", "imageUrl": "string|null",
  "type": "FREE|ONSITE_PAY", "price": 5000, "location": "string",
  "capacity": 100, "remaining": 37, "waitlistRemaining": 100,
  "openAt": "...", "closeAt": "...", "pickupStartAt": "...", "pickupEndAt": "...",
  "status": "SCHEDULED|OPEN|CLOSED|ENDED|CANCELED",
  "host": { "nickname": "string" },
  "myApplication": { "id": 1, "status": "CONFIRMED", "waitNo": null }
}
```
`remaining`은 Redis `stock` 기준 근사값이다. `myApplication`은 로그인한 경우에만 채운다.

### 9.5 참여자
| 메서드·경로 | 설명 |
| --- | --- |
| `POST /events/{eventId}/queue` | 대기열 진입. 이미 있으면 기존 순번 반환 (멱등) |
| `GET /events/{eventId}/queue/me` | 내 대기열 상태 |
| `POST /events/{eventId}/applications` | 신청. 헤더 `Admission-Token`, `Idempotency-Key`(UUID) 필수 |
| `GET /me/applications?status=&cursor=` | 내 신청 목록 |
| `GET /applications/{id}` | 신청 상세 (본인만) |
| `POST /applications/{id}/cancel` | 취소 |
| `POST /applications/{id}/accept` | 승계 제안 수락 |
| `POST /applications/{id}/decline` | 승계 제안 포기 |
| `GET /applications/{id}/ticket` | QR 티켓 `{token, expiresAt}`. `CONFIRMED`만 |

`GET /events/{eventId}/queue/me` 응답 (상태별):
```json
{ "status": "WAITING",  "position": 312, "estimatedSeconds": 2, "pollAfterMs": 2000 }
{ "status": "ADMITTED", "admissionToken": "string", "expiresInSeconds": 60 }
{ "status": "APPLIED",  "applicationId": 55 }
{ "status": "NOT_IN_QUEUE" }
```

`POST /events/{eventId}/applications` 응답 `201`:
```json
{ "applicationId": 55, "status": "CONFIRMED|WAITING", "waitNo": null }
```

### 9.6 주최자
| 메서드·경로 | 설명 |
| --- | --- |
| `POST /host/events` | 생성 (`DRAFT`) |
| `GET /host/events?status=&cursor=` | 내 이벤트 목록 |
| `GET /host/events/{id}` | 상세 (수정 화면용) |
| `PATCH /host/events/{id}` | 수정 (R-E5) |
| `DELETE /host/events/{id}` | `DRAFT`만 삭제 |
| `POST /host/events/{id}/publish` | 공개 → `SCHEDULED` |
| `POST /host/events/{id}/cancel` | 취소 (R-E7) |
| `GET /host/events/{id}/stats` | `{capacity, confirmed, offered, waiting, used, noShow, canceled}` |
| `GET /host/events/{id}/applications?status=&q=&cursor=` | 명단. `q`는 닉네임 또는 전화번호 뒷 4자리 |
| `POST /host/events/{id}/check-ins` | QR 체크인. 요청 `{token}` |
| `POST /host/events/{id}/applications/{appId}/check-in` | 수동 체크인 |
| `POST /host/uploads/images` | multipart 이미지 업로드, 최대 5MB, jpg/png/webp. 응답 `{url}` |

이벤트 생성 요청:
```json
{
  "title": "string", "description": "string", "imageUrl": "string|null",
  "type": "FREE|ONSITE_PAY", "price": null, "location": "string",
  "capacity": 100, "waitlistCapacity": 100, "offerWindowMin": 30,
  "openAt": "...", "closeAt": "...", "pickupStartAt": "...", "pickupEndAt": "..."
}
```

체크인 응답 (성공·실패 모두 200, 화면 피드백용):
```json
{ "result": "SUCCESS|ALREADY_USED|EXPIRED|NOT_YET|WRONG_EVENT|INVALID|NOT_CONFIRMED",
  "participant": { "nickname": "string", "phoneLast4": "1234" },
  "usedAt": "...|null" }
```

### 9.7 레이트 리밋
| 대상 | 기준 | 한도 |
| --- | --- | --- |
| `POST /events/*/queue` | 사용자 | 5회 / 10초 |
| `GET /events/*/queue/me` | 사용자 | 2회 / 1초 |
| `POST /events/*/applications` | 사용자 | 3회 / 10초 |
| 그 외 인증 API | 사용자 | 30회 / 10초 |
| 공개 API | IP | 60회 / 10초 |

## 10. 이벤트 메시징 (Outbox, Kafka)

### 10.1 토픽
| 토픽 | 키 | 발행 시점 | 소비자 |
| --- | --- | --- | --- |
| `hodadak.seat-released` | eventId | 자리 반납 (R-C2) | 승계 워커 |
| `hodadak.application-confirmed` | eventId | 확정 (신규·수락) | 없음 (이후 알림용, 발행만) |

키를 `eventId`로 해서 같은 이벤트의 메시지는 같은 파티션에서 순서대로 처리된다. 파티션 수 12, 복제 1 (브로커 1대). 소비자를 늘릴 여지를 두기 위해 파티션을 넉넉히 잡는다.

### 10.2 메시지 형식
```json
{
  "messageId": "outbox-1234",
  "type": "SEAT_RELEASED",
  "eventId": 12,
  "applicationId": 55,
  "reason": "USER_CANCELED|OFFER_DECLINED|OFFER_EXPIRED",
  "occurredAt": "..."
}
```

### 10.3 Outbox 릴레이
- 상태 변경과 `outbox_events` INSERT는 **같은 트랜잭션**에서 한다.
- 릴레이는 worker에서만 실행하고 ShedLock으로 한 번에 한 곳만 돈다 (같은 이벤트 메시지의 발행 순서 보장). 500ms마다 `SELECT … WHERE status='PENDING' ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`로 가져와 Kafka에 동기 전송하고 `PUBLISHED`로 바꾼다.
- 전송 실패 시 `attempts + 1`, 10회 초과면 `FAILED`로 두고 에러 로그와 메트릭을 남긴다.
- 발행은 at-least-once다. 소비자가 멱등해야 한다.

### 10.4 승계 워커 (`hodadak.seat-released` 소비, worker 프로필)
1. `processed_messages`에 `(succession-worker, messageId)`가 있으면 무시.
2. 이벤트가 `OPEN` 또는 `CLOSED`이고 `now < pickup_end_at - offer_window_min`인지 확인. 아니면 기록만 하고 종료.
3. 다음 대기자 조회: `SELECT … WHERE event_id=? AND status='WAITING' ORDER BY wait_no LIMIT 1 FOR UPDATE SKIP LOCKED`.
4. 있으면: `OFFERED`로 변경, `offered_at`, `offer_expires_at` 설정, `UPDATE events SET reserved_count = reserved_count + 1 WHERE id=? AND reserved_count < capacity` (영향 0행이면 예외로 롤백 후 경고).
5. 없고 이벤트가 `OPEN`이면: 커밋 후 Redis `INCR ev:{id}:stock`.
6. `processed_messages` INSERT. 3~6은 한 트랜잭션.
7. 실패 시 3회 재시도 후 DLT(`hodadak.seat-released.DLT`)로 보낸다.

## 11. 핵심 처리 흐름

### 11.1 대기열 입장
1. `POST /events/{id}/queue`: 이벤트가 `OPEN`인지 확인(캐시), `applied`에 있으면 `APPLIED` 반환, `stock == 0`이고 `waitseq >= waitlistCapacity`면 `EVENT_FULL`.
2. `ZADD NX ev:{id}:queue {nowMs} {userId}` 후 `ZRANK`로 순번 반환.
3. 입장 스케줄러(worker, 1초 주기, `OPEN` 이벤트마다, ShedLock으로 한 곳에서만): `ZPOPMIN ev:{id}:queue {admitPerSecond}`로 꺼내 각 사용자에게 `SET ev:{id}:admit:{userId} {uuid} EX 60`.
4. 클라이언트는 `pollAfterMs` 간격으로 `GET /queue/me`를 조회하고 `ADMITTED`가 되면 즉시 신청 API를 호출한다.
5. 입장 토큰은 신청 성공 시 삭제한다. 만료되면 다시 대기열에 들어가야 한다.

### 11.2 신청
1. 입장 토큰 검증 (`GET admit` 값 비교).
2. 같은 `(userId, idempotencyKey)` 신청이 DB에 있으면 그 결과 반환.
3. `apply.lua` 실행 → `DUPLICATE`면 `ALREADY_APPLIED`, `FULL`이면 `EVENT_FULL`.
4. 트랜잭션 시작
   - `CONFIRMED`: `UPDATE events SET reserved_count = reserved_count + 1 WHERE id=? AND reserved_count < capacity` (0행이면 예외), `applications` INSERT, `tickets` INSERT, `outbox`(`APPLICATION_CONFIRMED`) INSERT.
   - `WAITING`: `applications` INSERT (`wait_no`).
5. 커밋 실패 시 `apply_rollback.lua` 실행 후 예외를 다시 던진다.
6. 입장 토큰 삭제.

### 11.3 카운트다운
- 페이지 진입 시 `GET /public/time`으로 서버 시각과 클라이언트 시각의 차이(offset)를 구해 보정한다.
- 오픈 시각이 되기 전에는 신청 버튼을 비활성화한다. 서버도 `EVENT_NOT_OPEN`으로 막는다.

### 11.4 QR 티켓
- 토큰: JWT HS256, 서명 키는 사용자 인증 키와 **분리**.
- 클레임: `jti`(UUID), `aid`(applicationId), `eid`(eventId), `exp`(= `pickup_end_at`).
- QR 내용은 토큰 문자열 그대로. 프론트는 화면 밝기 최대 안내를 표시한다.
- 체크인 처리
  1. 서명·`exp` 검증 실패 → `INVALID` 또는 `EXPIRED`
  2. `eid`가 경로의 이벤트와 다름 → `WRONG_EVENT`
  3. 시간 창 밖 → `NOT_YET` 또는 `EXPIRED`
  4. 신청이 `CONFIRMED`가 아님 → `ALREADY_USED`(USED인 경우) 또는 `NOT_CONFIRMED`
  5. `UPDATE tickets SET used_at = now WHERE id=? AND used_at IS NULL` (0행이면 `ALREADY_USED`), 신청 `USED`로 변경
  6. 모든 경우 `check_in_logs` 기록

### 11.5 스케줄러
| 잡 | 주기 | 내용 |
| --- | --- | --- |
| 이벤트 상태 전환 | 10초 | SCHEDULED→OPEN(재고 초기화), OPEN→CLOSED, CLOSED→ENDED(R-T5) |
| 승계 제안 만료 | 30초 | `OFFERED AND offer_expires_at < now` → `EXPIRED` + outbox |
| 대기열 입장 | 1초 | §11.1의 3 |
| Outbox 릴레이 | 500ms | §10.3 |
| 정합성 점검 | 1분 | §8.1 |

모든 잡은 `worker` 프로필에서만 등록하고 ShedLock(JDBC, `shedlock` 테이블)으로 동시에 한 곳에서만 실행한다 (**필수**). api 파드가 여러 개이거나 worker를 2개로 늘려도 잡이 중복 실행되면 안 된다. 특히 대기열 입장 잡이 중복되면 입장 속도가 배로 빨라진다.

- 주기가 짧은 잡은 `lockAtLeastFor`를 주기보다 조금 짧게(예: 1초 잡이면 900ms), `lockAtMostFor`는 수 초로 잡는다.
- ShedLock의 Spring Boot 4.1 호환을 먼저 확인하고, 안 되면 Redis 기반 락으로 대체한다 (결정 로그에 기록).

## 12. 화면 명세

모바일 우선. 참여자 화면은 카톡 인앱 브라우저에서 동작해야 한다.

### 12.1 라우트
| 라우트 | 화면 | 인증 |
| --- | --- | --- |
| `/` | 랜딩 (소개, "이벤트 만들기") | 공개 |
| `/login` | 카카오 로그인 버튼. `?redirect=`로 복귀 경로 유지 | 공개 |
| `/auth/kakao/callback` | 코드 처리 후 redirect | 공개 |
| `/e/[shareCode]` | 이벤트 상세(공유 페이지) | 공개 |
| `/e/[shareCode]/queue` | 대기열 | 필요 |
| `/e/[shareCode]/result` | 신청 결과 | 필요 |
| `/me` | 마이페이지 (닉네임, 전화번호) | 필요 |
| `/me/applications` | 내 신청 목록 (탭: 진행 중 / 지난 신청) | 필요 |
| `/me/applications/[id]` | 신청 상세: QR, 승계 수락, 취소 | 필요 |
| `/host/events` | 내 이벤트 목록 | 필요 |
| `/host/events/new` | 이벤트 생성 (단계형 폼) | 필요 |
| `/host/events/[id]` | 현황판 + 명단 + 링크 복사 | 필요 |
| `/host/events/[id]/edit` | 이벤트 수정 | 필요 |
| `/host/events/[id]/scan` | QR 스캔 | 필요 |
| `/host/events/[id]/check-in` | 수동 체크인 (검색) | 필요 |

### 12.2 화면별 요구사항

**이벤트 상세 `/e/[shareCode]`** (가장 중요, 서버 렌더링)
- `generateMetadata`로 OG 태그: 제목, 설명 앞 80자, 이미지(없으면 기본 이미지), 오픈 시각.
- 상태별 하단 버튼

| 조건 | 표시 |
| --- | --- |
| 비로그인 | "로그인하고 신청하기" |
| `SCHEDULED` | 카운트다운, 버튼 비활성 "곧 열려요" |
| `OPEN`, 남은 자리 있음 | "신청하기" → 대기열 진입 |
| `OPEN`, 자리 없음, 대기 여유 | "대기 신청하기" |
| `OPEN`, 모두 가득 | "마감되었어요" 비활성 |
| `CLOSED`, `ENDED` | "신청이 끝났어요" |
| `CANCELED` | "취소된 이벤트예요" |
| 내 신청 있음 | 내 상태 요약 + "내 신청 보기" |
| 본인이 주최자 | "현황판으로 이동" |
| 예약형인데 전화번호 없음 | 신청 전 전화번호 입력 바텀시트 |

- `ONSITE_PAY`는 가격과 "현장에서 결제해요" 안내를 표시한다.

**대기열 `/e/[shareCode]/queue`**
- 순번, 예상 대기 시간, "이 화면을 벗어나지 마세요" 안내.
- `pollAfterMs` 간격 폴링. `ADMITTED`면 `Idempotency-Key`(페이지 진입 시 1회 생성해 보관)로 신청 API 호출 후 결과 화면으로 이동.
- 네트워크 오류 시 같은 키로 재시도.

**신청 결과 `/e/[shareCode]/result`**
- 확정: 축하 문구, "QR 보기", 수령 장소·기간
- 대기: 대기 번호, 승계 규칙 안내 ("자리가 나면 ○분 안에 수락해야 해요")
- 마감: 안내 후 이벤트 페이지로

**신청 상세 `/me/applications/[id]`**
- `CONFIRMED`: QR(화면 너비 70% 이상), 장소, 수령 기간, 취소 버튼(확인 모달)
- `OFFERED`: 남은 수락 시간 카운트다운, "수락" / "포기"
- `WAITING`: 대기 번호, 취소 버튼
- `USED`, `NO_SHOW`, `CANCELED`, `EXPIRED`: 상태 안내만
- 화면에 머무는 동안 10초마다 상태를 다시 조회한다 (승계 제안 반영).

**이벤트 생성 `/host/events/new`**
- 단계: ① 기본 정보(제목, 설명, 이미지, 유형, 가격) ② 수량(수량, 대기 인원, 수락 기한) ③ 일정(오픈, 마감, 수령 시작·끝, 장소) ④ 미리보기 → "임시 저장" 또는 "공개하기".
- 시각 입력은 KST로 받고 UTC로 전송한다. R-E1 위반은 해당 필드에 즉시 표시.

**현황판 `/host/events/[id]`**
- 숫자 카드: 확정, 수락 대기, 대기, 수령 완료, 노쇼.
- 공유 링크 복사, "QR 스캔", "수동 체크인", "이벤트 취소".
- 명단: 상태 필터, 검색, 닉네임·전화번호 뒷 4자리·상태·신청 시각. 오픈 중에는 5초마다 갱신.

**QR 스캔 `/host/events/[id]/scan`**
- 전체 화면 카메라. 결과를 1.5초간 크게 표시: 성공(초록, 닉네임·전화 뒷 4자리), 이미 사용(노랑, 사용 시각), 그 외(빨강, 사유).
- 같은 QR을 2초 안에 다시 인식하면 무시(중복 요청 방지).
- 카메라 권한 거부 시 수동 체크인으로 안내.

**수동 체크인 `/host/events/[id]/check-in`**
- 닉네임 또는 전화번호 뒷 4자리 검색 → 결과 행의 "체크인" 버튼.

### 12.3 공통 상태 처리
- 모든 데이터 화면에 로딩(스켈레톤), 빈 상태, 오류(재시도 버튼) 상태를 둔다.
- `401`이면 리프레시 시도 후 실패 시 `/login?redirect=현재경로`.
- `429`면 `Retry-After` 후 자동 재시도하고 "요청이 많아요" 토스트.

## 13. 비기능 요구사항과 테스트

### 13.1 목표
| 항목 | 목표 |
| --- | --- |
| 정합성 | 초과 확정 0건, 같은 사용자 중복 신청 0건, 티켓 중복 사용 0건 |
| 성능 | 운영 VM(2 OCPU, 12GB, 전 구성이 한 노드에 동거)에서 신청 API p95 500ms 이하, 오류율 1% 미만을 지키는 **최대 동시 사용자 수를 측정해 기록**한다. 첫 가설은 2,000명이며 측정 후 갱신한다 |
| 가용성 | 앱 재시작 후 Redis 재고를 DB 기준으로 복구, 확정 데이터 유실 없음 |
| 무중단 | api 롤링 배포나 파드 강제 종료 중에도 신청 유실 0건, 5xx 비율 1% 미만 |
| 보안 | QR 서명 검증, 주최자 권한 검증, 개인정보 최소 수집, 로그 마스킹 |
| 관측성 | 요청 수, 지연, 대기열 길이, 확정 수, outbox 적체, 정합성 불일치 메트릭 |

### 13.2 필수 테스트
- 도메인 단위 테스트: §6.2의 모든 전이와 금지된 전이.
- 동시성 통합 테스트 (Testcontainers)
  - 수량 100, 사용자 1,000명이 동시에 신청 → 확정 정확히 100, 대기 정확히 `min(900, waitlist)`.
  - 같은 사용자가 같은 키로 10번 동시 신청 → 신청 1건.
  - 확정자 50명 동시 취소 → 대기자 50명이 순서대로 `OFFERED`, `reserved_count`가 변하지 않음.
  - 같은 QR 동시 스캔 10회 → `SUCCESS` 1회.
- 메시징 테스트: 같은 `seat-released` 메시지 2회 소비 → 승계 1회.
- k6 시나리오 (`/loadtest`)
  - `apply-burst.js`: 오픈 순간 VU를 단계적으로 늘리며(최대 1만) 대기열 진입부터 신청까지, 한계 지점 찾기
  - `cancel-storm.js`: 오픈 중 확정자 대량 취소
  - `rolling.js`: 신청 부하 중 api 롤링 배포와 파드 강제 삭제
  - 비교 실험: api 파드 1개, 2개, 3개에서 처리량과 병목(CPU, DB 커넥션, Redis) 변화
  - k6는 **운영 VM 밖**(노트북 또는 CI 러너)에서 실행한다. 같은 VM에서 돌리면 측정이 왜곡된다.
  - 결과는 `docs/loadtest/` 에 날짜별로 기록

### 13.3 동시성 방식 비교 실험
같은 시나리오로 네 방식을 구현해 비교하고 `docs/adr/001-concurrency.md`에 정리한다. 실험용 구현은 `apply` 모듈 내 전략 인터페이스(`SeatAllocator`)로 교체 가능하게 만든다.

| 방식 | 구현 |
| --- | --- |
| DB 비관적 락 | `SELECT … FROM events WHERE id=? FOR UPDATE` |
| DB 낙관적 락 | `events.version` + 재시도 |
| Redis 분산 락 | 이벤트 단위 락 (예: Redisson) |
| Redis 원자 연산 (채택) | `apply.lua` |

## 14. 마일스톤

각 마일스톤은 DoD를 모두 만족해야 완료다. 담당: **F** = 풀스택, **B** = 백엔드.

| 주차 | 마일스톤 | 담당 | 내용 | DoD |
| --- | --- | --- | --- | --- |
| 1 | M0 기반 | F, B | 모노레포, 로컬 docker-compose(PostgreSQL, Redis, Kafka, 오브젝트 스토리지), Gradle, Flyway V1, 공통 에러 처리, OpenAPI, api/worker 프로필 분리, Next.js 골격(standalone, `/api` rewrites), GitHub Actions(빌드·테스트) | 로컬에서 `docker compose up` 후 백엔드·프론트 기동, CI 통과 |
| 1~2 | M0-K 클러스터 | B | Oracle VM, k3s, Traefik + cert-manager, CloudNativePG, Strimzi, Redis, 오브젝트 스토리지, 모니터링, arm64 이미지 빌드·GHCR 푸시, 배포 워크플로, Kustomize 매니페스트 | `https://{DOMAIN}`에서 프론트, `https://{DOMAIN}/api/public/time`에서 API 응답, api 2·worker 1·frontend 1 Running, DB 백업 1회 성공 |
| 1~2 | M1 인증 | F | 카카오 로그인, JWT, 리프레시 회전, `/me` | 로그인 후 새로고침해도 유지, 만료 시 자동 재발급 |
| 2~3 | M2 이벤트 | F | 이벤트 CRUD, 공개·취소, 상태 스케줄러(worker), 이미지 업로드, 공개 페이지(OG), 생성 폼 | 카톡에 링크 붙이면 미리보기 노출, 상태 자동 전환 테스트 |
| 3~4 | M3 신청 핵심 | B | `apply.lua`, 보상, DB 정합성, 재고 초기화·재구성, ShedLock, 4방식 비교 | §13.2 동시성 테스트 1·2 통과, ADR 초안 |
| 4~5 | M4 대기열 | B | 대기열 진입·조회, 입장 스케줄러, 입장 토큰, 레이트 리밋 / (F) 대기열·결과 화면 | 대기열 거쳐 신청 E2E 성공, 429 처리 |
| 5~6 | M5 취소·승계 | B | 취소, outbox, 릴레이, Kafka, 승계 워커, 제안 만료, 수락·포기 / (F) 신청 상세 화면 | §13.2 동시성 테스트 3, 메시징 멱등 테스트 통과 |
| 6 | M6 체크인·종료 | F | QR 발급·스캔, 수동 체크인, 현황판, 종료 처리(노쇼) | 실제 폰 2대로 스캔 성공, 동시 스캔 테스트 통과 |
| 7 | M7 품질 | B, F | Grafana 대시보드, k6 시나리오(§13.2), api 파드 수별 비교, 롤링 배포 무중단 검증, HPA, UI 다듬기 | 한계 동시 사용자 수와 병목 문서화, 롤링 배포 중 신청 유실 0건 |
| 8 | M8 출시 | F, B | 운영 배포(`v1.0.0`), 개인정보 처리방침, 행사 전 리허설, 실제 행사 운영, README | 실제 행사 1건 운영, 트러블슈팅 5건 이상 |

M0-K 때문에 B의 M3 시작이 며칠 늦어질 수 있다. 일정이 밀리면 M7의 HPA와 Grafana 대시보드 다듬기를 먼저 뺀다.

## 15. 인프라와 배포

### 15.1 환경
| 환경 | 위치 | 용도 |
| --- | --- | --- |
| 로컬 개발 | 각자 노트북, Docker Compose | 개발, 수동 테스트 |
| 자동 테스트 | Testcontainers (로컬, CI) | 단위·통합·동시성 테스트 |
| 매니페스트 검증 | 노트북 k3d | `infra/k8s` 변경을 운영 적용 전에 시험 |
| 운영 | Oracle Cloud Always Free, Ampere A1 VM 1대 (arm64, 2 OCPU, 12GB, 블록 스토리지 200GB 한도), k3s 단일 노드 | 실제 서비스, 부하 테스트 |

자원이 부족해 별도 스테이징 클러스터는 두지 않는다. 운영 적용 전 검증은 k3d로 한다.

### 15.2 로컬 docker-compose
- 서비스: `postgres`, `redis`, `kafka`(KRaft 단일), `object-storage`(§16.2에서 결정한 것과 같은 제품), 선택으로 `prometheus`, `grafana`.
- 백엔드는 IDE 또는 `bootRun`(프로필 `local,api,worker`), 프론트는 `npm run dev`로 띄운다.
- 프론트 개발 서버는 `next.config`의 rewrites로 `/api/*`를 `http://localhost:8080/api/*`로 넘겨 운영과 같은 오리진 구조를 유지한다.

### 15.3 운영 클러스터 구성
| 구성 | 방식 | 개수 | namespace |
| --- | --- | --- | --- |
| Ingress | k3s 기본 Traefik, TLS는 cert-manager + Let's Encrypt | - | kube-system, cert-manager |
| frontend | Deployment (Next.js standalone) | 1 | hodadak |
| api | Deployment, HPA 2~3 | 2 | hodadak |
| worker | Deployment, Service·Ingress 없음 | 1 | hodadak |
| PostgreSQL | CloudNativePG `Cluster`, 백업 활성화 | 1 | data |
| Kafka | Strimzi, KRaft 노드 1개(controller + broker), 복제 1, 보관 24시간 | 1 | data |
| Redis | 공식 이미지 StatefulSet, AOF | 1 | data |
| 오브젝트 스토리지 | §16.2에서 결정 | 1 | data |
| 모니터링 | kube-prometheus-stack 경량 설정(보관 3일, Alertmanager 없음) | - | monitoring |

Ingress 라우팅 (도메인 하나):

| 경로 | 대상 |
| --- | --- |
| `/api/*` | `api` Service |
| `/actuator/*` | 외부 비공개 (Ingress에 등록하지 않음) |
| 그 외 `/*` | `frontend` Service |

- 매니페스트는 `infra/k8s/`에 Kustomize로 둔다 (`base/`, `overlays/prod/`). 오퍼레이터는 각 프로젝트의 공식 Helm 차트로 설치하고 values 파일을 `infra/k8s/operators/`에 둔다.
- Bitnami 차트와 이미지는 쓰지 않는다 (2025년 무료 이미지 정책 변경).
- 모든 이미지는 linux/arm64를 지원해야 한다.
- Grafana와 Prometheus는 외부에 공개하지 않고 `kubectl port-forward`로 본다. k8s API(6443)도 외부에 열지 않는다.

### 15.4 애플리케이션 배포 규칙

**백엔드 (api, worker)**
- 같은 이미지에 프로필로 역할을 나눈다.
  - api: `SPRING_PROFILES_ACTIVE=prod,api`. HTTP 요청만 처리. 스케줄 잡, Kafka 소비자, Redis 재구성 없음.
  - worker: `SPRING_PROFILES_ACTIVE=prod,worker`. §11.5 스케줄 잡, Outbox 릴레이, 승계 소비자, Redis 재구성. HTTP는 actuator만.
- 프로브: readiness `/actuator/health/readiness`, liveness `/actuator/health/liveness`.
- 종료: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`, `preStop` 5초 sleep, `terminationGracePeriodSeconds: 30`.
- 롤링 배포: `maxUnavailable: 0`, `maxSurge: 1`.
- JVM: `-XX:MaxRAMPercentage=75`.
- DB 커넥션: Hikari `maximum-pool-size` 파드당 5. (api 최대 3 + worker 1) × 5 + 여유가 PostgreSQL `max_connections`보다 작아야 한다.

**프론트엔드**
- `next.config`에 `output: 'standalone'`. Node 이미지로 `server.js` 실행.
- 브라우저 요청은 같은 오리진의 `/api`를 쓰므로 API 주소를 빌드에 넣지 않는다.
- 서버 측 호출(서버 컴포넌트, `generateMetadata`)은 런타임 환경 변수 `API_INTERNAL_URL`(`http://api.hodadak.svc.cluster.local/api`)을 쓴다. 외부 도메인을 거쳐 자기 자신을 호출하지 않는다.
- 프로브: `/healthz` (프론트에 간단한 라우트를 둔다).
- 롤링 배포: `maxUnavailable: 0`, `maxSurge: 1` (배포 중에만 잠깐 2개).

### 15.5 자원 예산 (초기값, 측정 후 조정)
| 구성 | CPU requests | 메모리 limit |
| --- | --- | --- |
| k3s 시스템 (Traefik, CoreDNS, metrics-server 등) | - | 약 0.8GB |
| cert-manager | 50m | 256Mi |
| frontend × 1 | 100m | 384Mi |
| api × 2 (최대 3) | 각 250m | 각 1Gi |
| worker × 1 | 200m | 768Mi |
| PostgreSQL + CloudNativePG | 250m + 50m | 1.5Gi + 256Mi |
| Kafka + Strimzi | 250m + 50m | 1.25Gi + 384Mi |
| Redis | 50m | 384Mi (`maxmemory 256mb`) |
| 오브젝트 스토리지 | 50m | 384Mi |
| 모니터링 | 200m | 1.25Gi |
| OS와 여유 | - | 약 1.3GB |

평상시 합계는 약 10.7GB / 12GB다. api 3번째 파드와 롤링 배포 중 추가 파드는 이 여유분 안에서만 뜬다. HPA는 `minReplicas: 2`, `maxReplicas: 3`으로 둔다. 메모리가 부족하면 모니터링 보관 기간부터 줄인다.

### 15.6 이미지 빌드와 배포
- GitHub Actions에서 backend, frontend 이미지를 `linux/arm64`로 빌드해 GHCR에 푸시한다. 바뀐 폴더만 빌드한다 (경로 필터).
- `main` 머지: 테스트 후 이미지를 `sha-<커밋 7자리>` 태그로 푸시만 한다 (배포하지 않음).
- 운영 배포: `v<major>.<minor>.<patch>` 태그를 만들면 같은 태그로 이미지를 빌드하고, 워크플로가 Kustomize 이미지 태그를 바꿔 `kubectl apply` 한다. 수동 실행(`workflow_dispatch`)도 허용한다.
- 롤백: `kubectl rollout undo` 또는 이전 버전 태그로 재배포.
- Flyway 마이그레이션은 앱(api, worker) 기동 시 실행된다. 동시에 떠도 Flyway가 DB 락을 잡아 한 번만 적용한다. 변경은 이전 버전 코드와 호환되는 방식(컬럼 추가 → 코드 배포 → 이전 컬럼 제거)으로만 변경한다. 롤링 배포 중에는 두 버전이 동시에 돌기 때문이다.
- Argo CD 등 GitOps는 MVP 이후에 검토한다.

### 15.7 도메인과 네트워크
- 도메인 하나(`{DOMAIN}`)를 VM 공인 IP로 연결한다. 프론트와 API가 같은 오리진이라 CORS 설정이 필요 없다.
- Let's Encrypt 인증서 발급에는 도메인이 필수다 (IP로는 HTTPS 불가, 브라우저 카메라 스캔은 HTTPS 필수).

### 15.8 환경 변수
| 변수 | 대상 | 설명 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | backend | `prod,api` 또는 `prod,worker` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | backend | PostgreSQL (CloudNativePG가 만든 Secret 참조) |
| `REDIS_HOST`, `REDIS_PORT` | backend | Redis |
| `KAFKA_BOOTSTRAP_SERVERS` | backend | Kafka |
| `JWT_ACCESS_SECRET` | backend | 인증 토큰 서명 키 |
| `JWT_TICKET_SECRET` | backend | QR 티켓 서명 키 (인증 키와 다르게) |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | backend | 카카오 로그인 |
| `STORAGE_ENDPOINT`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | backend | 이미지 저장소 |
| `PUBLIC_BASE_URL` | backend, frontend | `https://{DOMAIN}` (OG 태그 절대 주소, 이미지 URL) |
| `API_INTERNAL_URL` | frontend | 서버 측 호출용 클러스터 내부 API 주소 |
| `NEXT_PUBLIC_KAKAO_CLIENT_ID`, `NEXT_PUBLIC_KAKAO_REDIRECT_URI` | frontend (빌드 시점) | 카카오 인가 |

비밀값은 k8s Secret으로 수동 생성하고 저장소에는 `.env.example`과 Secret 템플릿만 커밋한다.

### 15.9 운영 체크리스트
- Oracle VCN 보안 목록과 VM 내부 iptables 양쪽에서 80, 443을 연다. Oracle 기본 이미지의 iptables 규칙이 k3s 통신을 막지 않는지 확인한다.
- A1 인스턴스 생성 시 용량 부족 오류가 나면 시간을 두고 재시도한다. 무료 블록 스토리지는 홈 리전에서만 적용된다.
- 유휴 회수 기준(7일간 CPU 95퍼센타일, 네트워크, 메모리 사용률이 모두 20% 미만)을 알고 있는다.
- PostgreSQL 백업은 **VM 밖**의 저장소로 매일 보낸다 (§16.2). 노드가 하나라 VM을 잃으면 클러스터 안 데이터는 모두 사라진다.
- 실제 행사 전후 3일은 부하 테스트와 큰 배포를 하지 않고, 행사 전에 리허설을 1회 한다.
- 부하 테스트는 테스트용 이벤트로 하고 끝나면 데이터를 정리한다.

## 16. 결정 로그와 열린 질문

### 16.1 결정 로그
| 날짜 | 결정 | 이유 |
| --- | --- | --- |
| 2026-11-02 | 웹 우선, 앱은 이후 | 링크 공유 유입, 안드로이드 사용자 포함 |
| 2026-11-02 | 모듈러 모놀리스 | 2인 팀 운영 부담 |
| 2026-11-02 | 결제 미처리 | 전자금융 규제와 사업자 부담 회피 |
| 2026-11-02 | 1인 1이벤트 평생 1회 신청 | 취소 후 재신청 어뷰징 방지, `UNIQUE(event_id, user_id)`로 단순화 |
| 2026-11-02 | `confirmed_count` 대신 `reserved_count` | 승계 제안(`OFFERED`)도 자리를 점유하므로 |
| 2026-11-02 | 선착순 1차 판정은 Redis Lua, DB CHECK는 2차 방어 | 처리량과 정합성 동시 확보 |
| 2026-11-02 | 서비스 이름 커밍 → 호다닥, 설정 접두사 `hodadak.`, 토픽 `hodadak.*` | 팀 결정 |
| 2026-11-03 | 기본 패키지 `com.hodadak.backend` (Spring Initializr 생성값), 모듈은 `com.hodadak.backend.<module>` | 메인 클래스 패키지 아래여야 컴포넌트 스캔에 포함됨 |
| 2026-11-03 | 모노레포, GitHub Flow, Squash merge | 2인 팀, API와 화면이 함께 바뀜 |
| 2026-11-03 | 운영: Oracle Cloud Always Free A1 VM 1대에 k3s, 프론트·백엔드·DB·Redis·Kafka·오브젝트 스토리지 모두 클러스터 안에. 관리형 클라우드 서비스는 쓰지 않고, VM 밖에는 DB 백업만 둔다 | 비용 0원, 쿠버네티스 운영 경험 |
| 2026-11-03 | 도메인 하나에서 `/api`는 백엔드, 나머지는 프론트 (같은 오리진) | 쿠키·CORS 문제 제거, 배포 단일화 |
| 2026-11-03 | api/worker 프로필 분리, 스케줄 잡은 worker + ShedLock 필수 | 다중 파드에서 잡 중복 실행 방지 |
| 2026-11-03 | Redis 키에 해시태그 포함, 재구성은 worker에서 `RENAMENX`로 | 다중 파드에서 재고 덮어쓰기 방지 |
| 2026-11-03 | Kafka 파티션 3 → 12 | 소비자 확장 여지 |
| 2026-11-03 | 성능 목표를 "동시 1만 명 고정"에서 "한계 측정"으로 | 2 OCPU 단일 VM에 전 구성 동거 |
| 2026-11-03 | Bitnami 차트 미사용, PostgreSQL은 CloudNativePG, Kafka는 Strimzi | 2025년 Bitnami 무료 이미지 정책 변경 |
| 2026-11-03 | 운영 배포는 버전 태그로만, 스테이징 클러스터 없음 | 자원 부족, k3d로 사전 검증 |

### 16.2 열린 질문 (결정 전까지 기본값으로 진행)
- [ ] 첫 실제 행사 섭외처와 날짜 → 정해지면 M8 일정 조정
- [ ] 도메인 구매와 이름(`{DOMAIN}`) → M0-K 전에 필요
- [ ] VM 안에 띄울 오브젝트 스토리지 제품 → 후보: SeaweedFS, Garage. MinIO는 커뮤니티 배포 정책 변경으로 보류
- [ ] PostgreSQL 백업 저장 위치 (VM 밖, 예: Oracle Object Storage 무료 한도) → 외부 클라우드를 쓰는 유일한 곳
- [ ] 비회원 신청(전화번호 인증) 허용 여부 → 기본값: 불허, 카카오 로그인만
- [ ] 노쇼 제한 기준 → 기본값: 이후 단계에서 결정
- [ ] 영문 서비스명과 도메인 → 비슷한 이름의 기존 서비스(후다닥 등)와 겹치지 않는지 확인
