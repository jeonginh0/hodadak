# AGENTS.md — 호다닥(Hodadak)

AI 코딩 에이전트(Claude Code, Codex, Cursor 등)가 이 저장소에서 작업할 때 따르는 공통 규칙이다.
사람 팀원도 같은 규칙을 따른다.

## 프로젝트 한 줄 요약
동아리·학생회 같은 소규모 주최자가 한정 수량 이벤트를 열면, 참여자가 링크로 들어와 선착순으로 신청하고 현장에서 QR로 수령·입장하는 웹 서비스.

## 먼저 읽을 문서
| 문서 | 내용 | 언제 |
| --- | --- | --- |
| `docs/PRD.md` | 무엇을, 왜, 화면 목록, 역할 분담, 일정 | 기능의 의도가 궁금할 때 |
| `docs/SPEC.md` | 도메인 규칙, 상태 머신, DDL, Redis, API, 메시징, 인프라 | **모든 구현 작업 전에 관련 섹션을 반드시 읽는다** |
| `docs/adr/` | 주요 기술 결정 기록 | 설계를 바꾸고 싶을 때 |
| `docs/troubleshooting/` | 겪은 문제와 해결 기록 | 비슷한 문제를 만났을 때 |

SPEC과 코드가 다르면 SPEC이 기준이다. SPEC을 바꿔야 한다면 코드보다 SPEC을 먼저 고치고 §16 결정 로그에 남긴다.

## 작업 원칙
1. **한 번에 마일스톤 하나**(SPEC §14). 완료 조건(DoD)을 모두 만족해야 다음으로 넘어간다.
2. **SPEC에 없는 것은 만들지 않는다.** 기능, 테이블, 컬럼, 엔드포인트, 토픽, 설정 키를 추가해야 하면 먼저 제안하고 확인받는다.
3. **모호하면 질문한다.** SPEC이 비어 있거나 서로 모순되면 추측해서 구현하지 않는다.
4. **도메인 규칙은 테스트 먼저.** SPEC §5(도메인 규칙), §6(상태 머신)은 실패하는 테스트를 먼저 쓰고 구현한다.
5. **라이브러리는 호환성 확인 후.** Spring Boot 4.1.0, Java 21, arm64 이미지 지원 여부를 확인한다. 불확실하면 알리고 대안을 제시한다.
6. **작게 나눠서.** 한 PR은 하루 이틀 안에 리뷰할 수 있는 크기로 만든다.

## 작업 흐름

### 시작 전
1. 요청받은 작업이 SPEC §14의 어느 마일스톤에 속하는지 확인한다. 범위를 벗어나면 진행 전에 알린다.
2. `docs/SPEC.md`에서 관련 섹션을 읽고, 읽은 섹션 번호를 작업 계획에 적는다.
3. 현재 브랜치가 `main`이면 작업 브랜치(`feat/...` 등)를 먼저 만든다. `main`에서 직접 커밋하지 않는다.
4. 여러 파일에 걸친 작업은 먼저 계획(바꿀 파일, 추가할 테스트, 확인 방법)을 보여 주고 확인받는다.

### 작업 중
- 도메인 규칙은 실패하는 테스트 → 구현 → 테스트 통과 순서로 진행한다.
- 의존성을 추가하기 전에 Spring Boot 4.1.0 호환성과 arm64 지원을 확인하고, 추가 이유를 알려 준다.
- SPEC과 다르게 구현해야 할 이유가 생기면 멈추고 질문한다. 임의로 SPEC을 고치지 않는다.
- 긴 출력(테스트 로그, 빌드 로그)은 실패한 부분만 요약해서 보여 준다.

### 마무리
1. 바꾼 영역의 테스트를 실행한다.
   - 백엔드: `cd backend && ./gradlew test`
   - 프론트: `cd frontend && npm run lint && npm run typecheck`
   - `apply`, `queue`, `ticket`, `outbox`를 바꿨다면 동시성 테스트(SPEC §13.2) 결과를 함께 보고한다.
   - `infra/k8s`를 바꿨다면 `kubectl kustomize`로 렌더링되는지 확인한다.
2. 커밋은 아래 Git 규칙을 따르고 논리적 단위로 나눈다.
3. 마일스톤 DoD 중 이번 작업으로 충족된 항목과 남은 항목을 정리해서 알려 준다.
4. 작업 중 겪은 문제가 있으면 `docs/troubleshooting/`에 기록할지 묻는다.
5. PR 본문 초안(무엇을, 왜, 테스트, 관련 SPEC 섹션)을 함께 제시한다.

## 저장소 구조 (모노레포)
```
hodadak/
├─ backend/               Spring Boot (Gradle Kotlin DSL)
├─ frontend/              Next.js (App Router, TypeScript)
├─ infra/
│   ├─ docker-compose.yml 로컬 개발용 인프라
│   └─ k8s/               운영 k3s 매니페스트 (Kustomize)
├─ loadtest/              k6 시나리오
├─ docs/                  PRD.md, SPEC.md, adr/, troubleshooting/, loadtest/
├─ .github/               워크플로, PR 템플릿, CODEOWNERS
├─ AGENTS.md
└─ CLAUDE.md
```
`backend/`와 `frontend/` 안에 `.git` 폴더가 있으면 안 된다. 저장소는 루트 하나다.

## 자주 쓰는 명령
```bash
# 로컬 인프라
docker compose -f infra/docker-compose.yml up -d

# 백엔드
cd backend && ./gradlew build                 # 빌드 + 테스트
cd backend && ./gradlew test                  # Testcontainers 사용, Docker 필요
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local,api,worker'

# 프론트엔드
cd frontend && npm run dev
cd frontend && npm run lint && npm run typecheck
cd frontend && npm run gen:api                # 백엔드 OpenAPI로 타입 생성

# 매니페스트 검증 (로컬 k3d)
kubectl kustomize infra/k8s/overlays/prod > /dev/null

# 부하 테스트 (운영 VM 밖에서 실행)
k6 run loadtest/apply-burst.js
```

## 백엔드 규칙
- Java 21, Spring Boot 4.1.0, Spring Data JPA, Flyway, PostgreSQL, Redis, Kafka.
- 패키지: `com.hodadak.backend.<module>.{api, application, domain, infra}`
  - 모듈: `auth, user, event, apply, queue, ticket, outbox, common`
  - 메인 클래스는 `com.hodadak.backend.BackendApplication`이다. 모든 코드는 반드시 `com.hodadak.backend` 아래에 둔다. 바깥 패키지(예: `com.hodadak.apply`)에 두면 컴포넌트 스캔에서 빠져 빈이 조용히 등록되지 않는다.
  - 예: `com.hodadak.backend.apply.application.ApplyService`, `com.hodadak.backend.queue.infra.RedisQueueRepository`
- **모듈 경계**: 다른 모듈의 Repository나 Entity를 직접 쓰지 않는다. 상대 모듈의 `application` 계층 공개 서비스로만 호출한다.
- **상태 전이**는 도메인 엔티티 메서드로만 한다 (예: `application.cancel(now)`). 서비스에서 `setStatus`를 호출하지 않는다.
- DTO는 `record`, 요청 검증은 Bean Validation.
- 시각은 `Instant`, DB는 `timestamptz`. 현재 시각은 주입받은 `Clock`으로만 얻는다.
- 금액은 `BigDecimal`, DB는 `numeric`.
- 스키마 변경은 Flyway 마이그레이션(`V{n}__{설명}.sql`)으로만. `ddl-auto`는 `validate`. 머지된 마이그레이션 파일은 수정하지 않고 새 버전을 추가한다.
- 오류는 `BusinessException(ErrorCode)`로 던지고 전역 핸들러가 SPEC §9.1 형식으로 바꾼다. 오류 코드는 SPEC §9.2에 있는 것만 쓴다.
- **프로필로 역할을 나눈다** (SPEC §15.4).
  - `api`: HTTP 요청 처리만.
  - `worker`: 스케줄러, Outbox 릴레이, Kafka 소비자, Redis 재구성.
  - 스케줄 잡과 소비자 빈에는 반드시 `@Profile("worker")`를 붙이고, 잡에는 ShedLock을 건다.
- Redis 키는 SPEC §8 표기를 따르며 중괄호 해시태그를 실제 키에 포함한다 (`ev:{12}:stock`).
- 로그에 개인정보(전화번호, 이름)와 토큰을 남기지 않는다.

## 프론트엔드 규칙
- Next.js App Router, TypeScript strict, Tailwind CSS, TanStack Query.
- 모바일 우선(360px 기준). 참여자 화면은 카카오톡 인앱 브라우저에서 동작해야 한다.
- API 타입은 백엔드 OpenAPI에서 생성한다. 손으로 타입을 중복 정의하지 않는다.
- 카운트다운은 서버 시각 기준(SPEC §11.3). 클라이언트 시계를 믿지 않는다.
- 브라우저에서는 같은 오리진의 `/api`로 호출한다 (API 주소를 하드코딩하지 않는다). 서버 컴포넌트와 `generateMetadata`는 클러스터 내부 주소 `API_INTERNAL_URL`로 호출한다 (SPEC §15.4).
- `next.config`는 `output: 'standalone'`. 런타임에 바뀌어야 하는 값을 `NEXT_PUBLIC_*`에 넣지 않는다 (빌드 시점에 고정되기 때문).
- 라우트와 화면별 상태는 SPEC §12를 따른다.
- 모든 데이터 화면에 로딩, 빈 상태, 오류(재시도) 상태를 둔다.

## 인프라 규칙
- 로컬은 Docker Compose, 운영은 Oracle Cloud A1 VM 1대(arm64, 2 OCPU, 12GB)의 k3s다. 백엔드, 프론트엔드, 데이터 저장소가 모두 이 클러스터에 있다 (SPEC §15).
- 하나의 도메인에서 Ingress가 `/api`는 api, 나머지는 frontend로 보낸다. `/actuator`는 외부에 열지 않는다.
- 컨테이너 이미지(backend, frontend)는 **linux/arm64**로 빌드한다.
- Bitnami 차트와 이미지는 쓰지 않는다. PostgreSQL은 CloudNativePG, Kafka는 Strimzi, Redis는 공식 이미지를 쓴다.
- 모든 워크로드에 requests와 memory limit을 둔다. 자원 예산은 SPEC §15.5를 넘기지 않는다.
- 비밀값은 커밋하지 않는다. `.env.example`과 Secret 템플릿만 커밋한다.

## 테스트
- 단위: 도메인 상태 전이(허용·금지 모두), 계산 로직.
- 통합: Testcontainers(PostgreSQL, Redis, Kafka).
- **동시성 테스트(SPEC §13.2)**: `apply`, `queue`, `ticket`, `outbox` 모듈을 바꾸면 반드시 실행하고 PR에 결과를 적는다.
- 테스트가 실패하는 상태로 커밋하지 않는다. 테스트를 지우거나 `@Disabled`로 넘어가지 않는다.

## Git 규칙
- 전략: **GitHub Flow**. `main`은 항상 배포 가능한 상태이며 직접 push하지 않는다.
- 브랜치: `feat/<요약>`, `fix/<요약>`, `refactor/<요약>`, `chore/<요약>`, `docs/<요약>` (예: `feat/apply-lua`)
- 커밋 메시지: `<type>(<scope>): 요약`
  - type: `feat | fix | refactor | test | docs | chore`
  - scope: 백엔드 모듈명(`apply`, `queue` …), `frontend`, `infra`, `loadtest`, `docs`
  - 예: `feat(apply): Lua 스크립트로 선착순 판정`
- PR: 제목은 커밋 규칙과 같게, 본문은 PR 템플릿(무엇을, 왜, 테스트, 관련 SPEC 섹션). 상대방 승인 1명과 CI 통과 후 **Squash merge**.
- 이슈와 연결: PR 본문에 `Closes #번호`.
- 운영 배포는 `v<major>.<minor>.<patch>` 태그로만 한다 (SPEC §15.6).

## 기록
- 문제가 생기면 `docs/troubleshooting/NNN-제목.md`에 **문제, 원인, 시도, 결과, 수치**를 남긴다.
- 설계 결정은 `docs/adr/NNN-제목.md`에 남기고 SPEC §16 결정 로그에 한 줄 추가한다.
- 부하 테스트 결과는 `docs/loadtest/YYYY-MM-DD-시나리오.md`에 남긴다.

## 하지 말 것
- 사용자 확인 없이 `git push`, PR 생성, 운영 클러스터(kubectl 컨텍스트가 운영)에 적용하기
- `.env`, Secret, kubeconfig 내용을 출력하거나 커밋하기
- 테스트를 통과시키려고 테스트 기대값을 바꾸기 (SPEC이 바뀐 경우만 예외)
- SPEC에 없는 의존성, 테이블, API를 확인 없이 추가하기
- api 프로필에서 스케줄 잡이나 Kafka 소비자 실행하기
- 서버 메모리에 상태 저장하기 (세션, 레이트 리밋 카운터, 대기열 등은 Redis)
- 머지된 Flyway 마이그레이션 수정하기
- `main`에 직접 push, 비밀값 커밋, 테스트 무시하고 커밋하기
