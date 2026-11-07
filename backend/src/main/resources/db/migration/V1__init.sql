create table users(
    id               bigserial primary key,
    kakao_id         varchar(64) not null unique,
    nickname         varchar(50) not null,
    phone            varchar(20),
    role             varchar(20) not null default 'USER',
    no_show_count    int         not null default 0,
    restricted_until timestamptz,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

create table events(
    id                bigserial primary key,
    host_id           bigint       not null references users (id),
    share_code        varchar(16)  not null unique,
    title             varchar(100) not null,
    description       text         not null default '',
    image_url         varchar(500),
    type              varchar(20)  not null,
    price             numeric(12, 0),
    location          varchar(200) not null,
    capacity          int          not null,
    waitlist_capacity int          not null,
    reserved_count    int          not null default 0,
    per_user_limit    int          not null default 1,
    open_at           timestamptz  not null,
    close_at          timestamptz  not null,
    pickup_start_at   timestamptz  not null,
    pickup_end_at     timestamptz  not null,
    offer_window_min  int          not null default 30,
    status            varchar(20)  not null default 'DRAFT',
    version           bigint       not null default 0,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),

    constraint ck_capacity check (capacity between 1 and 10000),
    constraint ck_waitlist check (waitlist_capacity between 0 and 10000),
    constraint ck_reserved check (reserved_count >= 0 and reserved_count <= capacity),
    constraint ck_times check (open_at < close_at and close_at <= pickup_end_at and
                               pickup_start_at < pickup_end_at),
    constraint ck_price check ((type = 'FREE' and price is null) or
                               (type = 'ONSITE_PAY' and price is not null and price >= 0)),
    constraint ck_offer_window check (offer_window_min between 5 and 1440)
);

create index ix_events_status_open on events (status, open_at);
create index ix_events_status_close on events (status, close_at);
create index ix_events_status_pickup on events (status, pickup_end_at);
create index ix_events_host on events (host_id, created_at desc);

create table applications(
    id               bigserial primary key,
    event_id         bigint      not null references events (id),
    user_id          bigint      not null references users (id),
    status           varchar(20) not null,
    wait_no          int,
    offered_at       timestamptz,
    offer_expires_at timestamptz,
    confirmed_at     timestamptz,
    canceled_at      timestamptz,
    cancel_reason    varchar(20),
    used_at          timestamptz,
    idempotency_key  varchar(64) not null,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint uq_app_event_user unique (event_id, user_id),
    constraint uq_app_idem unique (user_id, idempotency_key)
);

create index ix_app_next_waiting on applications (event_id, wait_no) where status = 'WAITING';
create index ix_app_offer_expire on applications (offer_expires_at) where status = 'OFFERED';
create index ix_app_event_status on applications (event_id, status);
create index ix_app_user on applications (user_id, created_at desc);

create table tickets(
    id             bigserial primary key,
    application_id bigint      not null unique references applications (id),
    jti            varchar(64) not null unique,
    issued_at      timestamptz not null default now(),
    expires_at     timestamptz not null,
    used_at        timestamptz
);

create table check_in_logs(
    id         bigserial primary key,
    event_id   bigint      not null references events (id),
    ticket_id  bigint references tickets (id),
    scanned_by bigint      not null references users (id),
    method     varchar(10) not null,
    result     varchar(20) not null,
    scanned_at timestamptz not null default now()
);

create index ix_checkin_event on check_in_logs (event_id, scanned_at desc);

create table outbox_events(
    id             bigserial primary key,
    aggregate_type varchar(30) not null,
    aggregate_id   bigint      not null,
    event_type     varchar(50) not null,
    partition_key  varchar(50) not null,
    payload        jsonb       not null,
    status         varchar(20) not null default 'PENDING',
    attempts       int         not null default 0,
    created_at     timestamptz not null default now(),
    published_at   timestamptz
);

create index ix_outbox_pending on outbox_events (created_at) where status = 'PENDING';

create table processed_messages(
    consumer     varchar(50) not null,
    message_id   varchar(64) not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, message_id)
);

create table shedlock(
    name       varchar(64) primary key,
    lock_until timestamptz  not null,
    locked_at  timestamptz  not null,
    locked_by  varchar(255) not null
);