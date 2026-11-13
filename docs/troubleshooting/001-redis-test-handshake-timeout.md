# Testcontainers Redis 최초 연결 시간 초과

- 발생일: 2026-11-13
- 환경: Windows, Docker Desktop, Java 21, Spring Boot 4.1.2
- 관련 코드: `backend/src/test/java/com/hodadak/backend/auth/AuthIntegrationTest.java`

## 문제

전체 백엔드 테스트 실행 중 `loginMeRefreshAndLogoutLifecycle()`에서 Redis 최초 연결에 실패했다.

오류 체인은 `RedisConnectionFailureException` → `RedisConnectionException` → `RedisCommandTimeoutException`이며, 마지막 예외는 Lettuce의 `RedisHandshakeHandler`에서 발생했다.

## 원인

테스트가 공통 설정의 `spring.data.redis.timeout=2s`를 그대로 사용했다. 최초 Redis 핸드셰이크가 이 제한을 넘었다. 같은 실행에서 뒤이어 수행한 토큰 회전·만료·소유자 검증 테스트는 통과했다.

Docker Desktop 및 테스트 JVM 초기화 지연의 영향으로 추정한다. CPU·메모리 지표를 측정하지 않았으므로 자원 부족을 확정 원인으로 단정하지 않는다.

## 시도

1. Docker 엔진 접근과 Testcontainers 기동 상태를 확인했다.
2. 최초 전체 실행은 15개 테스트가 모두 통과했다.
3. 검증 사례를 추가한 다음 전체 실행에서는 17개 중 로그인 생명주기 1개가 실패했다. 리프레시 동시 요청 및 동시 최초 로그인 테스트는 통과했다.
4. `AuthIntegrationTest`에만 `spring.data.redis.timeout=10s`를 지정했다. 운영·로컬 애플리케이션의 공통 2초 제한과 테스트 기대값은 변경하지 않았다.
5. 테스트 클래스 종료 후 Spring 컨텍스트를 닫도록 `@DirtiesContext(AFTER_CLASS)`를 추가했다. 컨테이너 종료 후 캐시된 Redis 클라이언트가 재접속하는 경고도 줄이기 위한 조치다.

## 결과와 수치

- 최초 검증: 15개 성공, 0개 실패.
- 추가 테스트 검증: 17개 중 16개 성공, 1개 Redis 핸드셰이크 시간 초과.
- 대기 시간 조정: 통합 테스트에 한해 2초 → 10초. 서비스 설정은 2초 유지.
- 최종 재검증: 전체 17개 성공, 실패·오류·건너뜀 0개. `BUILD SUCCESSFUL`, 4분 2초.
- 리프레시 동시 요청 10건: 성공 정확히 1건. 동일 카카오 ID의 동시 최초 로그인 10건: 사용자 ID 1개.

## 확인 명령

Java 21과 실행 중인 Docker Desktop이 필요하다.

```powershell
cd backend
.\gradlew.bat test --console=plain
```

같은 문제가 반복되면 단순히 제한 시간을 더 늘리지 말고 Docker 상태, 테스트 JVM 부하, Redis 연결 지연을 측정한다.
