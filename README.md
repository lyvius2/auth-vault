# auth-vault

개인 AI 비서 **Cena AI**(OpenClaw + Slack 봇)의 보조 서비스. 비서가 캐치테이블 같은 웹사이트에 **사용자별 계정**으로
로그인할 수 있게 해 주되, 비밀번호는 LLM에 절대 넘기지 않는다.

1. 비서가 MCP 도구 `request_credentials`를 호출하면 1회용 HTTPS 링크가 발급된다.
2. 사용자가 링크를 열어 ID/PW를 입력하면 AES-256-GCM으로 암호화되어 `/data/vault.json`에 저장된다.
3. 비서가 `login`을 호출하면 이 서비스가 그 사용자의 전용 Chrome 프로필(OpenClaw 브라우저)에 CDP로 붙어
   로그인 폼을 채워 제출하고, **상태값만** 돌려준다.

## 원칙

- 평문 ID/PW는 MCP 도구 응답·로그·예외 메시지·Slack 감사 로그 어디에도 남기지 않는다.
- 자격증명은 AES-256-GCM으로 암호화해 저장한다. 마스터 키는 환경변수로만 받고 파일에 쓰지 않는다.
- 입력 링크 토큰은 1회용, 10분 TTL, 메모리 보관. 사용 즉시 폐기(재시작하면 미사용 링크도 무효).
- 결제 비밀번호·카드번호는 범위 밖이다. 저장하지 않는다.
- 소스는 서버에 두지 않는다. 서버는 Docker Hub 이미지를 pull만 한다.
- GraalVM 네이티브 이미지로 동작해야 한다(리플렉션은 힌트 등록, 동적 프록시·런타임 클래스 생성 금지).
  같은 이유로 Playwright/Selenium 대신 CDP를 직접 호출한다.

## 구성

| 항목 | 값 |
|---|---|
| 스택 | Spring Boot 3.5.7, Spring AI 1.1.0 MCP Server(WebMVC, streamable-http), Java 21, GraalVM native |
| 포트 | 8020 (컨테이너는 `127.0.0.1:8020`에만 바인딩) |
| MCP 엔드포인트 | `http://127.0.0.1:8020/mcp` — OpenClaw 전용, 외부 노출 금지 |
| 입력 폼 | `https://cena-ai.duckdns.org/vault/f/<token>` — nginx가 `/vault/` → 8020 프록시 |
| 저장소 | `/data/vault.json` (볼륨) |
| 레시피 | `/recipes/<site>.yml` (호스트에서 편집, 읽기전용 마운트) |
| 사용자 → CDP 포트 | `yunson=18800`, `hyemi=18801` (`application.yaml`의 `vault.profiles`) |

컨테이너에서 호스트의 OpenClaw Chrome으로는 `host.docker.internal:<port>`로 접속한다. Chrome DevTools는 Host 헤더가
localhost/IP가 아니면 거부하므로 `Host: localhost`를 명시하며, 이를 위해
`-Djdk.httpclient.allowRestrictedHeaders=host`가 빌드 인자와 ENTRYPOINT에 들어 있다.

### MCP 도구

OpenClaw에서는 `vault__<이름>`으로 보인다. 모든 도구는 상태값만 반환한다.

| 도구 | 설명 |
|---|---|
| `login(site, user)` | 저장된 자격증명으로 로그인. `ok` / `already_logged_in` / `otp_required` / `no_credentials` / `failed` |
| `request_credentials(site, user)` | ID/PW 입력용 1회용 링크 발급 |
| `submit_otp(site, user, code)` | `otp_required` 이후 사용자가 받은 인증번호 제출 |
| `list_sites(user)` | 자격증명이 저장된 사이트와 레시피가 있는 사이트 목록 |
| `forget(site, user)` | 저장된 자격증명 삭제 |

## 환경변수

| 변수 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `VAULT_MASTER_KEY` | ✅ | – | base64 인코딩된 32바이트 키. `openssl rand -base64 32`. **분실 시 저장된 자격증명 전부 복구 불가** |
| `VAULT_BASE_URL` |  | (빈 값) | 입력 링크의 호스트. 예: `https://cena-ai.duckdns.org` |
| `VAULT_CDP_HOST` |  | `host.docker.internal` | OpenClaw Chrome이 떠 있는 호스트 |
| `VAULT_DATA_FILE` |  | `/data/vault.json` | 암호화된 자격증명 파일 |
| `VAULT_RECIPES_DIR` |  | `/recipes` | 사이트 레시피 디렉터리 |
| `SLACK_BOT_TOKEN` |  | (빈 값) | 감사 로그용. 없으면 stdout 로그만 남는다 |
| `SLACK_ALERT_CHANNEL` |  | (빈 값) | 감사 로그를 보낼 채널 |

## 레시피 형식

`recipes/<site>.yml` 파일 하나가 사이트 하나다. 파일 이름이 사이트 키가 된다. 셀렉터는 실제 DOM을 보고 맞춘다
(현재 `catchtable.yml`의 값은 자리표시자다).

```yaml
site: catchtable
loginUrl: https://app.catchtable.co.kr/ct/login
idSelector: 'input[name="id"]'
passwordSelector: 'input[type="password"]'
submitSelector: 'button[type="submit"]'
submitText: 로그인                          # 선택. 있으면 submitSelector 대신, 글자가 정확히 일치하고 화면에 보이는 첫 button을 클릭
successSelector: 'a[href*="/my"]'          # 선택. 로그인 후에만 보이는 요소
successUrlContains: /home                  # 선택. 제출 후 URL에 이 문자열이 있으면 성공
otpSelector: 'input[name="authCode"]'      # 선택. 제출 후 이 요소가 보이면 otp_required
otpSubmitSelector: 'button[type="submit"]' # 선택
otpSubmitText: 확인                         # 선택. 있으면 otpSubmitSelector 대신
waitMs: 2500                               # 제출 후 대기(최소 1500)
```

성공 판정은 `successSelector` → `successUrlContains` 순서로 확인하며 하나라도 맞으면 성공이다. 둘 다 없으면
현재 URL이 `loginUrl`로 시작하지 않는지(로그인 페이지를 벗어났는지)로 판정한다. 실패하면 응답 `detail`에 현재
URL(쿼리스트링 제외)과 페이지 제목이 붙는다.

## 개발

```sh
./gradlew build          # 컴파일 + 테스트 (환경변수 불필요)
./gradlew nativeCompile  # GraalVM 필요. 결과물: build/native/nativeCompile/auth-vault
docker build --platform linux/arm64 -t auth-vault .
```

## 배포

GitHub → GitHub Actions(`ubuntu-24.04-arm`) → GraalVM 네이티브 빌드 → Docker Hub(arm64) → 서버는 pull만.

1. GitHub 저장소의 Settings → Secrets and variables → Actions에 시크릿 두 개를 등록한다(최초 1회).
   - `DOCKERHUB_USERNAME`: Docker Hub 계정명. `deploy/docker-compose.yml`의 이미지 경로(`lyvius2/auth-vault`)와 같아야 한다.
   - `DOCKERHUB_TOKEN`: Docker Hub → Account settings → Personal access tokens에서 발급한 Read & Write 토큰(비밀번호 아님).

   터미널에서는 `gh secret set DOCKERHUB_USERNAME`, `gh secret set DOCKERHUB_TOKEN`으로도 등록할 수 있다.
2. 태그를 푸시하면 `.github/workflows/publish.yml`이 `<DOCKERHUB_USERNAME>/auth-vault:<tag>`와 `:latest`를 올린다.
   ```sh
   git tag v0.1.1 && git push origin v0.1.1
   ```
3. 서버에서 `deploy/docker-compose.yml`, `deploy/.env.example`(→ `.env`), `recipes/`를 한 디렉터리에 두고:
   ```sh
   # .env 의 IMAGE_TAG 를 올린 뒤
   docker compose pull && docker compose up -d
   ```

`.env`는 커밋하지 않는다(`.gitignore`에 포함).
