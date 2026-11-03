# 호다닥 (Hodadak)

동아리·학생회 같은 소규모 주최자가 한정 수량 이벤트를 열면, 참여자가 링크로 들어와 **선착순으로 신청**하고 현장에서 **QR로 수령·입장**하는 웹 서비스입니다.

- 무엇을, 왜 만드는지: [`docs/PRD.md`](docs/PRD.md)
- 어떻게 구현하는지 (도메인 규칙, DB, API, 인프라): [`docs/SPEC.md`](docs/SPEC.md)
- AI 코딩 도구와 사람이 함께 따르는 작업 규칙: [`AGENTS.md`](AGENTS.md), [`CLAUDE.md`](CLAUDE.md)

## 기술 스택

| 영역 | 사용 기술 |
| --- | --- |
| 백엔드 | Java 21, Spring Boot 4.1.2, Spring Data JPA, Flyway, Spring Security, ShedLock |
| 데이터 | PostgreSQL 17, Redis 7.4, Kafka 4.3.1 (KRaft) |
| 프론트엔드 | Next.js (App Router), TypeScript, Tailwind CSS, TanStack Query |
| 로컬 개발 | Docker Compose |
| 운영 | Oracle Cloud VM 1대 + k3s (쿠버네티스) |

## 저장소 구조

```
hodadak/
├─ backend/               Spring Boot 백엔드
├─ frontend/              Next.js 프론트엔드
├─ infra/
│   ├─ docker-compose.yml 로컬 개발용 PostgreSQL, Redis, Kafka
│   └─ k8s/               운영 쿠버네티스 매니페스트
├─ loadtest/              k6 부하 테스트
├─ docs/                  PRD, SPEC, ADR, 트러블슈팅 기록
├─ AGENTS.md              공통 작업 규칙 (Codex 등)
└─ CLAUDE.md              Claude Code 전용 규칙
```

---

## 처음 한 번만 하는 세팅

### 1. 필요한 프로그램 설치

| 프로그램 | 버전 | 확인 명령 |
| --- | --- | --- |
| Git | 최신 | `git --version` |
| JDK | **21** | `java -version` |
| Docker Desktop | 최신 | `docker version` |
| Node.js | 22 LTS 권장 | `node -v` (프론트 작업 시) |
| IntelliJ IDEA | Community 또는 Ultimate | |

- JDK는 IntelliJ에서도 받을 수 있습니다: **파일 → 프로젝트 구조 → SDK → JDK 다운로드** → 버전 21 선택.
- Gradle은 따로 설치하지 않습니다. 저장소에 들어 있는 `./gradlew`를 씁니다.
- Docker Desktop은 **Settings → Resources**에서 디스크 사용 한도를 넉넉하게(60GB 이상 권장) 잡아 두세요. 공간이 부족하면 Docker 디스크가 읽기 전용으로 바뀌는 문제가 생깁니다 (아래 문제 해결 참고).

### 2. 저장소 받기

```bash
git clone <저장소 주소> hodadak
cd hodadak
```

### 3. 환경 변수 파일 만들기

`.env` 파일은 커밋하지 않습니다. 예시 파일을 복사해서 씁니다.

```bash
cp infra/.env.example infra/.env           # 선택: DB 계정을 바꿀 때만 필요
cp backend/.env.example backend/.env       # 카카오 로그인 작업 시 필요
```

| 파일 | 지금 필요한가 | 내용 |
| --- | --- | --- |
| `infra/.env` | 아니요 | 없으면 기본값(`hodadak` / `hodadak`)으로 뜹니다. 바꾸면 `application-local.properties`도 같이 바꿔야 합니다 |
| `backend/.env` | 카카오 로그인 작업부터 | `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` (따옴표 없이) |
| `frontend/.env.local` | 프론트 작업부터 | `frontend/.env.example` 참고 |

실제 비밀값(카카오 키 등)은 팀 비밀번호 관리자에서 받습니다. 메신저나 커밋으로 주고받지 않습니다.

### 4. IntelliJ로 열기

1. **파일 → 열기** → `hodadak` 폴더 선택
2. 오른쪽 **Gradle** 탭에 `backend`가 보이는지 확인
   - 안 보이면 `backend/build.gradle.kts` 우클릭 → **Gradle 프로젝트 링크**
3. **파일 → 설정 → 에디터 → 파일 인코딩**
   - properties 파일의 디폴트 인코딩: **UTF-8**
   - 투명한 네이티브-ASCII 변환: **체크 해제**

### 5. 백엔드 실행 설정 만들기

`local,api,worker` 세 프로필을 켜고 실행합니다. 로컬에서는 한 프로세스가 요청 처리(api)와 백그라운드 작업(worker)을 모두 합니다.

**IntelliJ Ultimate**
1. **실행 → 구성 편집...**
2. **Spring Boot** 아래 `BackendApplication` 선택
3. **활성 프로필** 칸에 `local,api,worker`

**IntelliJ Community**
1. **실행 → 구성 편집...**
2. **애플리케이션** 아래 `BackendApplication` 선택
3. **프로그램 인수** 칸에 `--spring.profiles.active=local,api,worker`
   - 칸이 안 보이면 **옵션 수정 → 프로그램 인수** 체크

> 두 칸 중 **하나에만** 넣으세요. 활성 프로필 칸에 `--spring.profiles.active=...`를 통째로 넣으면 `Profile '--spring.profiles.active=local' must start and end with a letter or digit` 오류가 납니다.

**작업 디렉터리**는 `backend` 폴더여야 합니다 (`backend/.env`를 읽기 위해서). 기본값이 보통 맞습니다.

---

## 매일 개발할 때

### 1. 인프라 띄우기 (PostgreSQL, Redis, Kafka)

Docker Desktop을 켠 뒤:

```bash
cd infra
docker compose -f docker-compose.yml up -d
docker compose -f docker-compose.yml ps
```

정상 상태:

| 서비스 | 상태 | 노트북에서 접속 |
| --- | --- | --- |
| postgres | `healthy` | `localhost:5432` (DB `hodadak`, 계정 `hodadak` / `hodadak`) |
| redis | `healthy` | `localhost:6379` |
| kafka | `healthy` | `localhost:9092` |
| kafka-init | `exited (0)` | 토픽을 만들고 끝나는 컨테이너라 종료가 정상 |

Kafka는 healthy가 되기까지 20~30초 걸립니다.

### 2. 백엔드 실행

IntelliJ에서 `BackendApplication` 실행 (실행 설정은 위 5단계).

터미널로 실행하려면:
```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local,api,worker'
```

확인:
- 로그에 `The following 3 profiles are active: "local", "api", "worker"`
- 로그 끝에 `Started BackendApplication in ... seconds`
- http://localhost:8080/actuator/health → `{"status":"UP"}`
- API 문서: http://localhost:8080/api/docs/ui

DB 테이블은 앱이 시작할 때 Flyway가 `backend/src/main/resources/db/migration`의 SQL로 자동으로 만듭니다.

### 3. 프론트엔드 실행

```bash
cd frontend
npm install        # 처음 한 번, 또는 package.json이 바뀌었을 때
npm run dev
```
http://localhost:3000 에서 확인합니다. 브라우저의 `/api` 요청은 개발 서버가 백엔드(`localhost:8080`)로 넘겨 줍니다.

### 4. 끝낼 때

```bash
cd infra
docker compose -f docker-compose.yml down       # 컨테이너만 내림, 데이터는 유지
docker compose -f docker-compose.yml down -v    # 데이터까지 삭제 (DB 초기화)
```

---

## 자주 쓰는 명령

```bash
# 백엔드
cd backend && ./gradlew build          # 빌드 + 전체 테스트
cd backend && ./gradlew test           # 테스트만 (Testcontainers 사용, Docker 필요)

# 프론트엔드
cd frontend && npm run lint && npm run typecheck

# 인프라 로그
cd infra && docker compose -f docker-compose.yml logs -f postgres

# DB 직접 접속
cd infra && docker compose -f docker-compose.yml exec postgres psql -U hodadak -d hodadak
```

## 프로필 정리

| 프로필 | 파일 | 용도 |
| --- | --- | --- |
| (항상) | `application.properties` | 공통 설정 |
| `local` | `application-local.properties` | 노트북 개발. Docker Compose 주소와 계정이 들어 있음 |
| `prod` | `application-prod.properties` | 운영. 모든 값을 환경 변수에서 받음 |
| `api` | `application-api.properties` | 요청 처리 역할 |
| `worker` | `application-worker.properties` | 스케줄러, Outbox 릴레이, Kafka 소비자 역할 |

`application.properties`에 `spring.profiles.active`를 적지 마세요. 프로필은 항상 실행할 때 지정합니다.

---

## 문제 해결

### `docker compose up`이 `no such file or directory`
compose 파일은 `infra/` 안에 있습니다. 루트에서 실행하면 `-f infra/docker-compose.yml`, `infra` 안에서 실행하면 `-f docker-compose.yml`입니다.

### `refers to undefined volume`
`infra/` 안에 compose 파일이 여러 개 있거나, 서비스의 볼륨 이름과 맨 아래 `volumes:` 이름이 다른 경우입니다. `ls -a infra`로 확인하고 `docker-compose.yml` 하나만 남기세요.

### `input/output error` 또는 `Read-only file system`
Docker Desktop의 내부 디스크가 읽기 전용이 된 상태입니다. 앱 설정 문제가 아닙니다.
1. 맥 저장 공간 확인: `df -h ~` (10GB 이상 여유 필요)
2. Docker Desktop 완전히 종료(Quit) 후 다시 실행 → `docker run --rm alpine echo ok`로 확인
3. 그래도 안 되면 Docker Desktop → **Troubleshoot → Clean / Purge data** (이미지·볼륨이 모두 삭제됨)
4. **Settings → Resources**에서 디스크 사용 한도를 넉넉하게 설정

### `port is already allocated`
노트북에 PostgreSQL이나 Redis가 따로 실행 중입니다. 그 프로그램을 끄거나, compose의 왼쪽 포트(예: `"5433:5432"`)를 바꾸고 `application-local.properties` 주소도 같이 바꾸세요.

### `password authentication failed`
`infra/.env`의 DB 계정과 `application-local.properties`가 다르거나, 비밀번호를 바꾸기 전에 만든 DB가 남아 있는 경우입니다. PostgreSQL은 처음 만들 때만 비밀번호를 적용하므로 `docker compose -f docker-compose.yml down -v` 후 다시 띄우세요.

### `Profile '--spring.profiles.active=local' must start and end with a letter or digit`
활성 프로필 칸에 `--spring.profiles.active=...`를 통째로 넣은 경우입니다. 활성 프로필 칸에는 `local,api,worker`만 넣으세요.

### properties 파일의 한글 주석이 `\uXXXX`로 보임
IntelliJ 파일 인코딩 설정을 확인하세요 (처음 세팅 4단계).

새로운 문제를 해결했다면 `docs/troubleshooting/NNN-제목.md`에 **문제, 원인, 시도, 결과**를 남겨 주세요.

---

## 협업 규칙 요약

자세한 내용은 [`AGENTS.md`](AGENTS.md)에 있습니다.

- **브랜치**: `main`에 직접 push하지 않습니다. `feat/…`, `fix/…`, `chore/…`, `docs/…` 브랜치에서 작업합니다.
- **커밋**: `feat(apply): Lua 스크립트로 선착순 판정` 형식 (`type(scope): 요약`)
- **PR**: 상대방 승인 1명 + CI 통과 후 **Squash merge**
- **작업 단위**: SPEC §14의 마일스톤 단위. 작업 전에 SPEC의 관련 섹션을 읽습니다.
- **비밀값**: `.env`, 키, kubeconfig는 절대 커밋하지 않습니다. 커밋했다면 기록을 지우기 전에 **키부터 재발급**합니다.

### AI 코딩 도구
- **Claude Code**: `CLAUDE.md`를 읽고, 그 안에서 `AGENTS.md`를 불러옵니다.
- **Codex**: `AGENTS.md`를 읽습니다.
- 공통 규칙은 `AGENTS.md`에서만 고칩니다.
