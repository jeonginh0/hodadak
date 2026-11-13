package com.hodadak.backend.event.domain;

// SPEC 6.1. ENDED, CANCELED는 종착 상태
public enum EventStatus {
    DRAFT, SCHEDULED, OPEN, CLOSED, ENDED, CANCELED
}
