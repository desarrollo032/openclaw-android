# Honey Badger v2 설계 문서

작성일: 2026-04-03

> **이 문서의 대상은 구현을 수행할 에이전트다.**
> 배경, 목표, 아키텍처, 기술 결정 사항을 숙지한 뒤 11장의 작업 계획(Phase)에 따라 실행하라.
> 작업 지시 시 `v2/DESIGN.md 읽고 Phase N을 실행해`로 호출된다.

---

## 1. 배경

### 1.1 현재 프로젝트

이 저장소(`openclaw-android`)는 Android 기기에서 에이전트 런타임(OpenClaw 등)을 구동하기 위한 인프라 프로젝트다. 현재 두 가지 서비스 유형이 있다:

1. **스크립트 기반 (서비스 타입 1)**: 사용자가 Termux를 별도 설치 → 셸 스크립트(`oa --install`) 실행 → glibc-runner, Node.js, 에이전트 런타임 환경 구성.
2. **앱 기반 (v1)**: 사용자가 Claw 앱 설치 → 앱 내장 터미널(Termux Apache 2.0 라이브러리) + WebView 설치 UI → Termux bootstrap 다운로드 + 환경 구성.

### 1.2 문제점

- **서비스 타입 1**: Termux 별도 설치 필요, 진입 장벽 높음.
- **v1 앱**: WebView UI 레이어에 감싸진 제한적 터미널. Termux의 성숙한 기능(API 접근, 부팅 스크립트, 플러그인)이 없음.
- **두 방식 모두** 하나의 앱으로 완전한 리눅스 환경 + Android 연동을 제공하지 못함.

### 1.3 대상 사용자

Android에서 에이전트 런타임을 구동하려는 사용자. 필요한 것:
- 완전한 리눅스 환경 (터미널, 패키지 관리, 개발 도구)
- 에이전트를 통한 Android 기기 접근 (카메라, SMS, 센서, 위치, 클립보드, 알림)
- 에이전트 서비스 상시 구동을 위한 백그라운드/부팅 실행

## 2. 목표

**앱 하나 설치. 완전한 리눅스 환경. 즉시 에이전트 런타임 설치 가능.**

Honey Badger 앱은 Termux와 동일한 경험(완전한 리눅스 터미널 환경)을 제공하되, 첫 실행 시 glibc-runner가 자동 구성되어 사용자가 표준 리눅스용 에이전트 런타임을 바로 설치할 수 있게 한다.

### 2.1 앱이 하는 것

- Termux 포크로 Termux의 모든 기능을 그대로 제공
- 터미널 에뮬레이터, 패키지 관리(apt/pacman), 세션 관리
- Android API 접근(카메라, SMS, 센서 등)을 본체 앱에 내장
- 부팅 시 자동 시작 기능을 본체 앱에 내장
- 첫 실행 시 glibc-runner 자동 설치

### 2.2 앱이 하지 않는 것

- 커스텀 런처나 WebView 래퍼가 아님
- Node.js, OpenClaw 등 특정 에이전트 런타임 설치를 책임지지 않음
- 패키지 관리자를 대체하지 않음 — Termux의 기존 패키지 생태계를 그대로 사용

### 2.3 성공 기준

```
사용자가 Honey Badger APK 설치
  → 앱 실행
  → 첫 실행 설정 (bootstrap 추출 + glibc-runner 설치)
  → 완전한 터미널 준비 완료
  → 리눅스에서와 동일한 방식으로 에이전트 런타임 설치 가능
```

## 3. 아키텍처

```
+-----------------------------------------------+
|              Honey Badger APK                  |
|                                                |
|  +------------------------------------------+ |
|  |  Termux Core (포크, 최소 수정)             | |
|  |  - 터미널 에뮬레이터 (VT100/xterm)        | |
|  |  - 세션/프로세스 관리                      | |
|  |  - 패키지 관리 (apt/pacman)               | |
|  |  - 포그라운드 서비스 + 알림               | |
|  |  - 키보드 처리, 추가 키                    | |
|  |  - 텍스트 선택, 클립보드                   | |
|  +------------------------------------------+ |
|  +------------------------------------------+ |
|  |  Termux:API (본체에 통합)                  | |
|  |  - 카메라, SMS, 센서, 위치                | |
|  |  - 클립보드, 알림, 오디오                  | |
|  |  - 배터리, WiFi, 전화                     | |
|  +------------------------------------------+ |
|  +------------------------------------------+ |
|  |  Termux:Boot (본체에 통합)                 | |
|  |  - 기기 부팅 시 스크립트 자동 실행         | |
|  +------------------------------------------+ |
|  +------------------------------------------+ |
|  |  Honey Badger Layer (커스텀)               | |
|  |  - 브랜딩 (이름, 아이콘, 패키지명)        | |
|  |  - 첫 실행: glibc-runner 자동 설치        | |
|  |  - About 화면 (Termux 크레딧)             | |
|  +------------------------------------------+ |
+-----------------------------------------------+
         |
         v 첫 실행
+-----------------------------------------------+
|  Bootstrap 추출 → pacman -Sy glibc-runner     |
|  → 터미널 준비 완료                            |
|  → 사용자가 원하는 에이전트 런타임 설치         |
+-----------------------------------------------+
```

### 3.1 핵심 원칙

**1. Termux 코드 수정을 최소화한다.** 변경은 브랜딩, 플러그인 통합, 첫 실행 glibc-runner 설정에 한정한다. 이를 통해 Termux 업스트림 업데이트를 최소 충돌로 머지할 수 있다.

**2. 오버레이 원칙: 패키지 소유 파일을 수정하지 않는다.** 우리 커스텀은 `$PREFIX/local/`, `$HOME/.honeybadger/`에 배치하고, 환경변수/PATH/설정 파일로 적용한다. 이를 통해 `pkg upgrade`가 무엇을 갱신하든 우리 커스텀은 영향받지 않는다.

적용 수단:

| 수단 | 용도 |
|------|------|
| PATH 우선순위 (`$PREFIX/local/bin/` > `$PREFIX/bin/`) | CLI 명령어 래핑 (termux-api, dpkg 등) |
| 환경변수 오버라이드 (`APT_CONFIG`, `DPKG_ADMINDIR` 등) | 설정 파일 경로 지정 |
| LD_PRELOAD (`libtermux-exec`) | execve() 경로 런타임 리다이렉트 |
| 설정 파일의 바이너리 경로 (`apt.conf`의 `Dir::Bin::dpkg` 등) | 내부 호출 경로 오버라이드 |

예외: 컴파일된 바이너리의 hardcoded open()/opendir() 경로는 오버레이만으로 해결 불가. wrapper + 설정 조합으로 대응하며, 예외 목록을 SPEC.md에 기록한다.

## 4. 기술 결정 사항

| 항목 | 결정 | 근거 |
|------|------|------|
| 앱 이름 | Honey Badger | 관리자 결정 |
| 패키지명 | `com.honeybadger.terminal` | `com.termux.*` 네임스페이스 충돌 방지 |
| 포크 베이스 | termux-app `master` 브랜치 | 안정 릴리즈, Java |
| 언어 | Java (Termux 원본 그대로) | 리라이트 오버헤드 방지, 머지 호환성 유지 |
| targetSdk | 28 | W^X 제한 — Android 10+에서 targetSdk 29 이상이면 앱 데이터 디렉토리의 바이너리 실행(execve)이 SELinux로 차단됨 |
| 아키텍처 | arm64-v8a (aarch64) 전용 | glibc-runner가 aarch64 필요 |
| 플러그인 전략 | Termux:API + Termux:Boot을 본체 APK에 통합 | 앱 하나로 설치 완료. 패키지명이 다르면 기존 Termux 플러그인 APK가 동작하지 않으므로 통합 필수 |
| glibc-runner | 첫 실행 시 `pacman -Sy glibc-runner`로 자동 설치 | pacman을 통해 업데이트 가능, APK 번들링 불필요 |
| 배포 | GitHub Releases + F-Droid | Play Store는 targetSdk 29+를 요구하므로 불가 |
| 라이선스 | GPLv3 | Termux 포크 필수 조건 (termux-app이 GPLv3) |
| Termux 크레딧 | About 화면 + README | Termux 프로젝트에 대한 존중 표시 |
| 소스 관리 | clone 후 `.git/` 삭제, 메인 레포에서 직접 추적 | 중첩 git 저장소 방지. 업스트림 머지 시 별도 위치에 fresh clone해서 diff 적용 |
| 패키지명 치환 범위 | `app/` 모듈만 변경. `terminal-emulator/`(`com.termux.terminal`), `terminal-view/`(`com.termux.view`), `termux-shared/`(`com.termux.shared`)는 유지 | 업스트림 머지 용이. 라이브러리는 내부 패키지명과 무관하게 동작 |
| API CLI 호환 | wrapper 스크립트를 PATH 우선 경로에 배치 | `pkg install termux-api`의 CLI 스크립트가 `com.termux.api`를 explicit Intent 대상으로 하드코딩. `pkg upgrade` 시 원본이 복원되므로 sed 직접 패치는 부적합. 원본과 다른 경로에 wrapper를 배치하여 `pkg upgrade`에 영향받지 않도록 함. v1에서 npm/npx wrapper를 `BIN_DIR`에 배치한 패턴(`scripts/install-nodejs.sh` 참고)과 동일한 접근 |
| first-run.sh 배포 | GitHub에서 다운로드, 실패 시 APK 내장 폴백 | 변경 가능성 높음 (기기별 워크어라운드 등). v1의 post-setup.sh 배포 패턴과 동일 |
| SDK 자동 설치 | 빌드에 필요한 SDK/NDK가 없으면 `sdkmanager`로 자동 설치 허용 | 빌드 블로커 방지 |
| Bootstrap 출처 | Termux 공식 GitHub Releases의 bootstrap ZIP 그대로 사용 | 커스텀 빌드 불필요. 경로 패치(`com.termux` → 우리 패키지)는 Termux 앱 코드에 이미 포함된 기능. 버전은 Termux 최신 릴리즈 사용 |
| API CLI wrapper | 단일 디스패처 스크립트 + symlink | 개별 wrapper 대신 하나의 디스패처 스크립트를 `$PREFIX/local/bin/`에 배치하고, 각 `termux-*` 명령에 대해 symlink 생성. 새 명령어 추가 시 symlink만 추가하면 됨. `pkg upgrade`에 영향받지 않음 |
| 설정 파일 보호 | Termux 포크 코드에서 처리 방식 확인 후 따름 | bootstrap 추출 시 패치한 apt.conf, sources.list 등이 `pkg upgrade`로 덮어씌워질 가능성. Termux 자체도 같은 문제를 갖고 있으므로 포크 코드에 해결책이 있을 가능성 높음. Phase 6에서 확인 |

## 5. 첫 실행 플로우

```
앱 설치 후 최초 실행
  |
  v
[1] Bootstrap 추출
    - APK 내장(또는 다운로드) Termux bootstrap ZIP을 $PREFIX에 추출
    - 경로 패치 (com.termux → com.honeybadger.terminal)
    - apt/dpkg 설정
  |
  v
[2] glibc-runner 설치
    - pacman 설치: pkg install -y pacman
    - pacman 키링 초기화
    - glibc-runner 설치: pacman -Sy glibc-runner --noconfirm
    - 검증: $PREFIX/glibc/lib/ld-linux-aarch64.so.1 존재 확인
  |
  v
[3] 터미널 준비 완료
    - 사용자에게 터미널 표시
    - 완전한 리눅스 환경 + glibc 지원 사용 가능
    - 사용자가 원하는 에이전트 런타임 설치 가능 (예: openclaw, node, python)
```

1-2단계 진행 중 사용자에게 진행 상태를 표시한다. 완료 후 터미널을 보여준다.

## 6. 포크 수정 범위

### 6.1 수정 대상 (Termux Core)

| 영역 | 변경 내용 |
|------|-----------|
| `AndroidManifest.xml` | 패키지명, 앱 이름, API/Boot 관련 퍼미션 추가 |
| `build.gradle` | applicationId, versionName, 서명 설정 |
| `res/values/strings.xml` | 앱 이름 "Honey Badger", 설명 문구 |
| `res/mipmap-*` | 앱 아이콘 |
| Bootstrap 설치 로직 | bootstrap 추출 후 glibc-runner 자동 설치 스크립트 추가 |

### 6.2 통합할 코드

| 출처 | 대상 | 비고 |
|------|------|------|
| `termux-api` 앱 (Android 서비스) | 본체 앱 모듈에 머지 | API 호출을 처리하는 Android 쪽 서비스. 현재 별도 APK |
| `termux-api-package` (CLI 도구) | bootstrap에 포함 또는 첫 실행 시 설치 | `termux-camera-photo`, `termux-sms-send` 등 CLI 명령어 |
| `termux-boot` 앱 | 본체 앱 모듈에 머지 | 부팅 리시버 + 스크립트 실행 |

### 6.3 건드리지 않는 코드

- `terminal-emulator/` — 터미널 에뮬레이션 라이브러리
- `terminal-view/` — 터미널 렌더링
- 세션 관리 로직
- 패키지 관리 연동
- 키보드/입력 처리
- 알림/포그라운드 서비스

## 7. 저장소 구조

```
openclaw-android/
|-- v2/                          <-- 신규 개발
|   |-- DESIGN.md                <-- 이 문서
|   |-- termux-app/              <-- 포크한 termux-app (git clone)
|   |-- termux-api/              <-- 포크한 termux-api (통합용)
|   |-- termux-boot/             <-- 포크한 termux-boot (통합용)
|   |-- honeybadger/             <-- 커스텀 레이어
|   |   |-- first-run.sh         <-- glibc-runner 자동 설치 스크립트
|   |   |-- branding/            <-- 아이콘, 문자열
|   |   +-- about/               <-- 크레딧, 라이선스 정보
|   +-- docs/                    <-- 추가 문서
|
|-- android/                     <-- 기존 v1 앱 (v2 준비까지 유지, 수정하지 마라)
|-- scripts/                     <-- 기존 스크립트 (참고용)
|-- platforms/                   <-- 기존 플랫폼 플러그인
+-- ...
```

v2가 프로덕션 품질에 도달하면:
1. v1 관련 파일(`android/` 등)을 `v1-archive/`로 이동
2. `v2/` 내용을 프로젝트 루트로 승격
3. 메이저 버전 릴리즈

## 8. 빌드 및 배포

- **빌드**: Gradle (Java), Termux와 동일
- **서명**: Honey Badger 전용 새 키스토어 (v1 Claw 앱과 별도)
- **배포**: GitHub Releases (서명된 APK), F-Droid (빌드 레시피)
- **아키텍처**: arm64-v8a 전용
- **Min SDK**: 24 (Android 7.0, Termux와 동일)
- **Target SDK**: 28 (Android 9, W^X 제약)

## 9. 업스트림 머지 전략

```
termux/termux-app (업스트림)
    |
    +-- git remote add upstream
    |
    +-- 주기적으로:
        git fetch upstream
        git merge upstream/master
        충돌 해결 (변경이 브랜딩, 플러그인 통합,
        첫 실행 스크립트에 한정되므로 충돌 최소 예상)
```

머지 충돌 최소화 원칙:
- Termux 코어 코드 변경을 최소한으로 유지
- 커스텀 코드는 가능한 한 별도 파일/모듈로 분리
- Termux의 확장 포인트가 있으면 코어 수정 대신 활용

## 10. 알려진 제약 및 향후 대응

### 10.1 bionic↔glibc 경계 문제

glibc-runner 방식에서 Node.js(glibc 링크)가 bionic 링크된 `.so`를 `dlopen`할 수 없는 제약이 있다. glibc-runner의 구조적 한계이며, v2에서도 동일하게 적용된다.

### 10.2 이미 해결된 케이스

| 라이브러리 | 문제 | 해결 방법 |
|-----------|------|-----------|
| sharp (이미지 처리) | glibc .so를 bionic에서 dlopen 불가 | `@img/sharp-wasm32` WASM 폴백 |
| Chromium (브라우저 자동화) | - | exec으로 실행 (커널이 바이너리 로딩 독립 처리) |
| node:sqlite | 시스템 libsqlite 호환 | Node.js 내장 정적 번들 SQLite 사용 |

### 10.3 향후 예상되는 사용 케이스

PDF 생성/처리, 오디오/비디오 처리, 컴퓨터 비전은 모바일 에이전트에서 타당한 사용 케이스다 (폰의 카메라, 미디어 활용).

실행 경로별 호환성:

| 실행 경로 | bionic↔glibc 문제 | 비고 |
|-----------|-------------------|------|
| exec 기반 CLI 도구 (ffmpeg, wkhtmltopdf, pandoc 등) | 없음 | 커널이 바이너리 로딩을 독립 처리 |
| 클라우드 API (Claude Vision, Whisper API 등) | 없음 | HTTP 통신, libc 무관 |
| 네이티브 바인딩 dlopen (canvas/cairo, opencv 등) | 있음 | bionic .so를 glibc 프로세스에서 로드 불가 |

에이전트 런타임 특성상 **exec 기반 CLI 도구 + 클라우드 API** 패턴이 주류이므로 대부분 커버 가능하다. 네이티브 바인딩이 필요한 경우는 발생 시 케이스별 대응 (WASM 폴백, glibc 빌드 등).

### 10.4 대응 원칙

1. exec 기반 CLI 도구 우선 활용
2. 클라우드 API로 대체 가능하면 활용
3. 네이티브 바인딩 필요 시: WASM 폴백 → glibc 빌드 순으로 시도
4. 실제 사용자 케이스 축적 후 proot-distro 전환 또는 glibc 패키지 저장소 구축 검토

---

## 11. 작업 계획 (Phase별 에이전트 실행 단위)

각 Phase는 독립된 세션에서 하나의 에이전트가 완료할 수 있는 단위다.
Phase를 할당받으면 해당 섹션의 작업 내용을 순서대로 실행하고, 완료 기준을 모두 충족한 뒤 커밋하라.

### 에이전트 실행 규칙

- **승인 없이 진행하라.** 모든 Phase는 사람의 중간 승인 없이 완료까지 실행한다.
- **git commit**: 각 Phase 완료 시 커밋한다. **push는 하지 마라.** push는 관리자가 직접 수행한다.
- **판단이 필요한 상황**: 이 문서에 명시된 기술 결정(4장, 12장)을 따른다. 문서에 없는 판단은 "수정 최소화" 원칙에 따라 에이전트가 결정하고 커밋 메시지에 판단 근거를 남긴다.
- **v1 코드 보호**: `android/`, `scripts/`, `platforms/` 등 기존 v1 파일은 절대 수정하지 마라. 작업 범위는 `v2/` 내부로 한정한다.
- **빌드 환경**: Android SDK는 `/Users/aidan/Library/Android/sdk`에 설치되어 있다 (`ANDROID_HOME` 환경변수 설정됨). Java 21 (Temurin)이 시스템에 설치되어 있다.

### 검증-수정 루프

모든 Phase에서 작업 완료 후 아래 검증을 순서대로 실행한다. 실패 시 수정하고, 전체 검증을 다시 돌린다. **모든 검증이 통과할 때까지 커밋하지 마라.**

```
작업 수행
  |
  v
[검증 1] 빌드: ./gradlew assembleDebug
  | 실패 → 에러 수정 → 처음부터 다시 검증
  v
[검증 2] 패키지명 일관성: com.termux 잔존 확인
  | grep -r "com\.termux" --include="*.java" --include="*.xml" --include="*.gradle"
  | 의도하지 않은 잔존 발견 → 수정 → 처음부터 다시 검증
  | (예외: 주석, URL, 라이선스 문구 내 com.termux는 허용)
  v
[검증 3] 테스트: ./gradlew test (기존 단위 테스트가 있는 경우)
  | 실패 → 수정 → 처음부터 다시 검증
  | 테스트가 없으면 skip
  v
[검증 4] lint: ./gradlew lint (경고는 무시, 에러만 수정)
  | 에러 → 수정 → 처음부터 다시 검증
  v
모든 검증 통과 → 커밋
```

**검증 범위 제한:**
- debug build만 확인한다. release build, 서명은 하지 않는다.
- lint 경고(warning)는 무시한다. 에러(error)만 수정한다.

### E2E 검증 (디바이스 테스트)

**테스트 기기**: Galaxy S20+ (SM-G986N), Android 13 (SDK 33), 시리얼 `R3CN208X89Z`
ADB USB + 무선 디버깅 연결됨. 모든 adb 명령에 `-s R3CN208X89Z`를 지정하라.

**원칙**: 기기 접근이 가능하므로 **모든 Phase에서 가능한 한 최대한 기기 테스트를 수행**한다. 빌드 검증만으로 넘어가지 말고, APK 설치 + 실행 확인까지 하라.

**E2E 검증 절차:**

```
[1] APK 설치
    adb -s R3CN208X89Z install -r v2/termux-app/app/build/outputs/apk/debug/app-debug.apk

[2] 앱 실행
    adb -s R3CN208X89Z shell am start -n com.honeybadger.terminal/.app.TermuxActivity

[3] 앱 실행 확인
    adb -s R3CN208X89Z shell dumpsys activity activities | grep -i honeybadger
    - Activity 존재 → 통과

[4] 첫 실행 완료 대기 (Phase 6 이후, bootstrap + glibc-runner)
    마커 파일 polling으로 완료 감지:
    - 10초 간격으로 최대 10분간 확인:
      adb -s R3CN208X89Z shell run-as com.honeybadger.terminal ls files/home/.honeybadger/.glibc-ready
    - 마커 파일 존재 → 완료, 다음 단계로
    - 10분 초과 → 실패, adb logcat으로 로그 확인 후 원인 분석

[5] glibc-runner 설치 확인
    adb -s R3CN208X89Z shell run-as com.honeybadger.terminal ls files/usr/glibc/lib/ld-linux-aarch64.so.1
    - 파일 존재 → 통과
    - 파일 없음 → 실패, 로그 확인 후 수정

[6] 리눅스 환경 확인
    adb -s R3CN208X89Z shell run-as com.honeybadger.terminal ls files/usr/bin/bash
    - bash 존재 확인 (bootstrap 정상)
    adb -s R3CN208X89Z shell run-as com.honeybadger.terminal ls files/usr/bin/pacman
    - pacman 존재 확인 (glibc-runner 설치에 사용)
```

**Phase별 E2E 범위:**

| Phase | E2E 범위 |
|-------|----------|
| Phase 1 | APK 설치 + 앱 실행 확인 (Termux 원본 상태) |
| Phase 2 | APK 설치 + 앱 실행 확인 (패키지명 변경 후 정상 부팅) |
| Phase 3 | APK 설치 + 앱 실행 + API 퍼미션 등록 확인 (`dumpsys package`) |
| Phase 4 | APK 설치 + 앱 실행 + Boot 리시버 등록 확인 (`dumpsys package`) |
| Phase 5 | 빌드 검증만 (셸 스크립트 작업, APK 변경 없음) |
| Phase 6 | 전체 E2E — bootstrap + glibc-runner 자동 설치 + 마커 파일 확인 |
| Phase 7 | 최종 E2E — 클린 재설치 + About 화면 + `oa --install` 또는 수동으로 OpenClaw 설치까지 완료 확인 |

### 산출물: 앱 사양서 (`v2/SPEC.md`)

모든 Phase 작업 시 `v2/SPEC.md`에 수정 내역을 기록하라. 이 문서는 향후 유지보수를 위한 **앱 사양서**로, Termux 원본 대비 무엇을 어떻게 바꿨는지의 완전한 기록이다.

**Phase 2 완료 시 파일을 생성하고, 이후 Phase마다 해당 섹션을 추가한다.**

아래 구조를 따르라:

```markdown
# Honey Badger 앱 사양서

## 1. Termux 원본 대비 수정 목록

수정한 모든 파일을 나열한다. 파일별로:
- 원본 경로
- 수정 내용 (무엇을 왜 바꿨는지)
- 관련 Phase 번호

## 2. 모듈 간 의존성 맵

수정한 부분이 다른 컴포넌트에 미치는 영향을 기록한다.
예: "패키지명 변경 → AndroidManifest, build.gradle, Java 소스, 
     bootstrap 경로 패치, 환경변수, ContentProvider authority에 영향"

## 3. 패키지명 치환 맵

치환한 문자열과 치환하지 않은 문자열을 전수 기록한다.
향후 업스트림 머지 시 새로운 com.termux 참조가 추가되었을 때 
이 맵을 기준으로 치환 여부를 판단한다.

## 4. 통합된 플러그인 (API, Boot)

각 플러그인별로:
- 가져온 소스 파일 목록
- 수정한 부분과 이유
- AndroidManifest에 추가한 항목 (서비스, 리시버, 퍼미션)
- 원본 플러그인과의 동작 차이점

## 5. 첫 실행 플로우 상세

first-run.sh의 각 단계별:
- 실행하는 명령어
- 실패 가능 지점과 대응 방법
- 의존하는 외부 서비스 (패키지 저장소 등)

## 6. 오버레이 맵

오버레이 원칙(DESIGN.md 3.1장)에 따라 구현한 모든 오버레이를 기록한다.

### 6.1 오버레이 목록
각 오버레이별로:
- 대상: 어떤 패키지 소유 파일/기능을 오버레이하는지
- 방식: PATH/환경변수/설정 파일/LD_PRELOAD 중 무엇을 사용하는지
- 우리 파일 위치: $PREFIX/local/ 또는 $HOME/.honeybadger/ 내 경로
- 동작 원리: 왜 이 방식으로 원본을 건드리지 않고 커스텀이 적용되는지

### 6.2 오버레이 예외 목록
오버레이만으로 해결 불가능하여 예외적으로 패키지 소유 파일을 수정한 경우:
- 대상 파일과 수정 내용
- 왜 오버레이로 해결 불가능한지 (예: hardcoded open() 경로)
- pkg upgrade 시 깨지는지, 깨진다면 복구 방법

### 6.3 환경변수 전체 목록
앱이 설정하는 모든 환경변수와 용도.
오버레이 적용에 사용되는 변수를 명시하라.

## 7. 유지보수 주의사항

### 7.1 업스트림 머지 시 체크리스트
- 새로운 com.termux 참조 확인 (3장 치환 맵 기준)
- 라이브러리 모듈 변경 시 호환성 확인
- AndroidManifest 변경 시 우리가 추가한 항목과 충돌 확인
- 새로운 바이너리/스크립트가 com.termux 경로를 사용하는지 확인 → 오버레이 필요 여부 판단

### 7.2 수정 시 연쇄 영향 맵
특정 파일을 수정할 때 함께 확인/수정해야 하는 파일 목록.
예: "build.gradle의 applicationId 변경 시 
     → AndroidManifest, 환경변수 설정, first-run.sh, 
        오버레이 설정 파일, dispatcher 스크립트도 함께 변경"

### 7.3 hook 작업 (특정 변경 시 필수 후속 작업)
예: "bootstrap ZIP 교체 시 → 오버레이 목록 재확인"
    "새로운 CLI 도구 추가 시 → dispatcher symlink 추가 필요 여부 확인"

### 7.4 pkg upgrade 영향 분석
pkg upgrade 후에도 정상 동작하는지 확인하는 체크리스트.
오버레이 맵(6장)의 각 항목이 pkg upgrade 후에도 유효한지 검증 방법.
```

**작성 규칙:**
- 각 Phase 완료 시 해당 섹션을 작성 또는 업데이트한다.
- 추상적 설명 금지. 파일 경로, 라인 번호, 구체적 변경 내용을 적어라.
- "나중에 정리" 금지. Phase 커밋 전에 SPEC.md도 함께 커밋하라.

### 기존 코드 참조 (필수)

기존 v1의 스크립트와 앱 코드에는 다수의 시행착오를 통해 축적된 노하우가 담겨 있다. 각 Phase 작업 시 아래 파일을 **반드시 먼저 읽고** 동일한 문제에 대해 동일한 해결 방식을 적용하라.

| Phase | 참조 파일 | 참조 이유 |
|-------|----------|----------|
| 2 | `android/app/src/.../BootstrapManager.kt` | bootstrap 추출, 경로 패치(`com.termux` → 우리 패키지), apt/dpkg 설정, symlink 처리, dpkg wrapper |
| 2 | `android/app/src/.../EnvironmentBuilder.kt` | 환경변수 구성. 어떤 `com.termux` 문자열을 유지해야 하는지 판별하는 핵심 참고 자료 |
| 5 | `scripts/install-glibc.sh` | glibc-runner 설치 절차, SigLevel 워크어라운드, `--assume-installed` 옵션, 검증 로직 |
| 5 | `scripts/install-nodejs.sh` (228행~) | npm/npx wrapper 패턴 — `pkg upgrade`에 영향받지 않도록 원본과 다른 경로에 wrapper 배치하는 방식. termux-api CLI wrapper도 이 패턴으로 구현하라 |
| 5 | `scripts/lib.sh` | 공유 유틸리티, 에러 처리 패턴, 컬러 출력 |
| 5 | `post-setup.sh` | 전체 초기 설정 플로우, 패키지 설치 순서 |
| 5 | `install.sh`, `bootstrap.sh` | 설치 오케스트레이션, bootstrap 관련 설정 |
| 6 | `scripts/install-glibc.sh` | first-run.sh에서 bootstrap 연결 로직 작성 시 참고 |
| 6 | `android/app/src/.../BootstrapManager.kt` | v1의 첫 실행 트리거 방식 참고 (`copyPostSetupScript`, `needsPostSetup`) |
| 7 | `android/app/src/.../MainActivity.kt` | v1의 앱 구조, 터미널 세션 관리, extra keys 구현 |

**주의**: 위 파일들은 읽기 전용 참고다. 수정하지 마라.

### Phase 의존성

```
Phase 1 (소스 가져오기)
    |
    v
Phase 2 (패키지명 치환 + 빌드)
    |
    +→ Phase 3 (API 통합) → Phase 4 (Boot 통합)
    |
    +→ Phase 5 (first-run 스크립트 작성) → Phase 6 (bootstrap 연결 + 배포)
    |
    +→ Phase 3,4 완료 + Phase 6 완료 → Phase 7 (About + README + E2E)
```

- **모든 Phase는 한 세션씩 순차 실행한다.** 같은 로컬 저장소에서 동시 작업하면 git 충돌이 발생하므로 병렬 실행하지 마라.
- Phase 3→4는 순차 (같은 AndroidManifest.xml 수정).
- Phase 5는 Phase 3/4와 의존성 없음 — Phase 2 완료 후 어떤 순서로든 실행 가능하나, 반드시 이전 Phase가 커밋된 후 시작하라.
- Phase 6은 Phase 2 + Phase 5 완료 후 실행.
- Phase 7은 모든 Phase 완료 후 실행.
- **권장 실행 순서**: 1 → 2 → 3 → 4 → 5 → 6 → 7

---

### Phase 1: 소스 가져오기 + 빌드 환경 준비

**목표**: Termux 소스를 v2/ 아래에 가져오고, 빌드 환경을 준비한다.

**선행 조건**: 없음 (최초 작업)

**작업**:

1. Termux 소스를 클론한다
   ```
   git clone https://github.com/termux/termux-app.git v2/termux-app
   git clone https://github.com/termux/termux-api.git v2/termux-api
   git clone https://github.com/termux/termux-boot.git v2/termux-boot
   ```
2. 중첩 git 저장소를 제거한다
   ```
   rm -rf v2/termux-app/.git v2/termux-api/.git v2/termux-boot/.git
   ```
3. 빌드에 필요한 SDK를 확인하고 설치한다
   - `v2/termux-app/build.gradle`에서 `compileSdk` 버전 확인
   - 없으면 `sdkmanager "platforms;android-<version>"` 실행
4. Termux 원본 빌드를 확인한다
   - `cd v2/termux-app && ./gradlew assembleDebug`
   - 원본 상태에서 빌드가 성공하는지 확인 (실패하면 SDK/NDK 문제)
5. 변경사항을 커밋한다

**완료 기준**:
- [ ] `v2/termux-app/`, `v2/termux-api/`, `v2/termux-boot/`에 소스 존재 (`.git/` 없음)
- [ ] `./gradlew assembleDebug` 성공 (Termux 원본 상태)
- [ ] 변경사항 커밋됨

---

### Phase 2: 패키지명 변경 + 브랜딩 + 빌드 확인

**목표**: app/ 모듈의 패키지명을 `com.honeybadger.terminal`로 변경하고, 앱 이름을 "Honey Badger"로 바꾸고, 빌드 성공을 확인한다.

**선행 조건**: Phase 1 완료

**참조 필수**: `android/app/src/.../BootstrapManager.kt`, `android/app/src/.../EnvironmentBuilder.kt`

**작업**:

1. `v2/termux-app/app/` 모듈의 패키지명을 변경한다
   - `applicationId` 변경 (`app/build.gradle`)
   - `AndroidManifest.xml` 패키지명 변경
   - `app/src/` 내 Java 소스 디렉토리 구조 변경 (`com/termux/` → `com/honeybadger/terminal/`)
   - `app/src/` 내 Java 파일의 `package`/`import` 문 치환
   - **`terminal-emulator/`, `terminal-view/`, `termux-shared/`의 패키지명은 변경하지 마라**

   **패키지명 치환 규칙 (중요):**

   `com.termux` 문자열이 여러 맥락에서 사용된다. 단순 전체 치환하면 고장난다. 아래 규칙을 따르라:

   | 맥락 | 예시 | 치환 여부 |
   |------|------|----------|
   | Java package/import (`app/` 모듈) | `package com.termux.app` | O → `com.honeybadger.terminal.app` |
   | applicationId | `com.termux` | O → `com.honeybadger.terminal` |
   | 파일 경로 문자열 | `/data/data/com.termux/files/usr` | O → `/data/data/com.honeybadger.terminal/files/usr` |
   | 퍼미션 이름 | `com.termux.permission.RUN_COMMAND` | O → `com.honeybadger.terminal.permission.RUN_COMMAND` |
   | ContentProvider authority | `com.termux.filepicker` | O → `com.honeybadger.terminal.filepicker` |
   | 라이브러리 import | `import com.termux.terminal.*` | **X** — 라이브러리 모듈은 패키지명 유지 |
   | 라이브러리 import | `import com.termux.view.*` | **X** |
   | 라이브러리 import | `import com.termux.shared.*` | **X** |
   | LEGACY_DATA_DIR 환경변수 | `"/data/data/com.termux"` | **X** — bootstrap 경로 리다이렉트용, 원본 경로 유지 필수 |
   | 주석, URL, 라이선스 문구 | `// from com.termux project` | **X** |

   **치환 전략**: 단계적으로 치환하라. (1) Java 소스 디렉토리 이동 → (2) package/import 문 치환 → (3) 설정 파일(manifest, gradle) 수정 → (4) 문자열 리소스/경로 수정. 각 단계마다 빌드해서 확인하라.

2. 앱 이름을 변경한다
   - `res/values/strings.xml`: `app_name` → "Honey Badger"
3. APK 빌드를 확인한다
   - `./gradlew assembleDebug`
4. `v2/SPEC.md`를 생성한다 (앱 사양서)
   - 1장(수정 목록), 3장(치환 맵) 작성
5. 변경사항을 커밋한다

**주의사항**:
- targetSdk 28을 유지하라. 변경하지 마라.
- `app/` 모듈에서 `com.termux.terminal`, `com.termux.view`, `com.termux.shared`를 import하는 부분은 그대로 유지하라.
- `ndk.abiFilters`에 `arm64-v8a`만 포함되어 있는지 확인하라.

**완료 기준**:
- [ ] 패키지명이 `com.honeybadger.terminal`로 변경됨
- [ ] 앱 이름이 "Honey Badger"로 변경됨
- [ ] `./gradlew assembleDebug` 성공
- [ ] `v2/SPEC.md` 생성됨 (1장, 3장 작성)
- [ ] 변경사항 커밋됨

---

### Phase 3: Termux:API 본체 통합

**목표**: Termux:API의 Android 서비스 코드를 본체 앱에 머지하여, 별도 APK 없이 API 기능을 사용할 수 있게 한다.

**선행 조건**: Phase 2 완료

**작업**:

1. `v2/termux-api/` 소스를 분석한다
   - Android 서비스 코드 구조 파악
   - 필요한 퍼미션 목록 확인 (카메라, SMS, 위치, 센서 등)
   - 본체 앱과의 통신 방식 파악 (Intent 기반)
   - `termux-shared` 의존 여부 확인
2. API 서비스 코드를 본체 앱에 머지한다
   - `termux-api`의 서비스 클래스들을 `v2/termux-app/app/` 소스에 복사
   - 패키지명을 `com.honeybadger.terminal`에 맞게 수정
   - `AndroidManifest.xml`에 서비스, 리시버, 퍼미션 추가
   - Intent 필터를 본체 앱 패키지명에 맞게 수정
3. 빌드를 확인한다
4. `v2/SPEC.md` 4장(통합된 플러그인) API 섹션 작성
5. 변경사항을 커밋한다

**주의사항**:
- **Intent 구조 유지**: 패키지명만 바꾸고, 자기 자신에게 Intent를 보내는 형태로 동작시킨다. 내부 메서드 호출로 전환하지 마라.
- **CLI 호환성**: CLI 스크립트의 패키지명 패치는 Phase 5의 first-run.sh에서 처리한다. 이 Phase에서는 Android 서비스 쪽만 다룬다.
- 퍼미션은 런타임 퍼미션 요청이 필요하다 (Android 6.0+).

**완료 기준**:
- [ ] API 서비스 코드가 본체 앱 소스에 포함됨
- [ ] `AndroidManifest.xml`에 필요한 퍼미션/서비스 등록됨
- [ ] `./gradlew assembleDebug` 성공
- [ ] `v2/SPEC.md` 4장 API 섹션 작성됨
- [ ] 변경사항 커밋됨

---

### Phase 4: Termux:Boot 본체 통합

**목표**: Termux:Boot의 부팅 리시버 코드를 본체 앱에 머지한다.

**선행 조건**: Phase 3 완료 (Phase 3과 같은 AndroidManifest.xml을 수정하므로 순차 실행)

**작업**:

1. `v2/termux-boot/` 소스를 분석한다
   - `BootReceiver` 구조 파악
   - 부팅 시 실행할 스크립트 경로 규칙 확인 (`~/.termux/boot/`)
   - 필요한 퍼미션 확인 (`RECEIVE_BOOT_COMPLETED`)
   - `termux-shared` 의존 여부 확인
2. Boot 코드를 본체 앱에 머지한다
   - `BootReceiver`를 `v2/termux-app/app/` 소스에 복사
   - 패키지명을 `com.honeybadger.terminal`에 맞게 수정
   - `AndroidManifest.xml`에 리시버, 퍼미션 추가
3. 빌드를 확인한다
4. `v2/SPEC.md` 4장 Boot 섹션 작성
5. 변경사항을 커밋한다

**완료 기준**:
- [ ] Boot 리시버 코드가 본체 앱 소스에 포함됨
- [ ] `AndroidManifest.xml`에 `RECEIVE_BOOT_COMPLETED` 퍼미션/리시버 등록됨
- [ ] `./gradlew assembleDebug` 성공
- [ ] `v2/SPEC.md` 4장 Boot 섹션 작성됨
- [ ] 변경사항 커밋됨

---

### Phase 5: first-run.sh 스크립트 작성

**목표**: 첫 실행 시 glibc-runner를 자동 설치하고 환경을 준비하는 스크립트를 작성한다.

**선행 조건**: Phase 2 완료 (Phase 3, 4와 병렬 실행 가능 — 앱 코드가 아닌 셸 스크립트 작업)

**참조 필수**: `scripts/install-glibc.sh`, `scripts/install-nodejs.sh` (228행~ npm/npx wrapper 패턴), `scripts/lib.sh`, `post-setup.sh`, `install.sh`, `bootstrap.sh`

**작업**:

1. 기존 스크립트를 분석한다
   - `scripts/install-glibc.sh`: glibc-runner 설치 절차, SigLevel 워크어라운드, 검증 로직
   - `post-setup.sh`: 전체 초기 설정 플로우, 패키지 설치 순서
   - `scripts/lib.sh`: 에러 처리, 컬러 출력 패턴
2. `v2/honeybadger/first-run.sh`를 작성한다
   - glibc-runner 설치 (install-glibc.sh 로직 기반)
   - termux-api CLI 디스패처 + symlink 생성:
     ```bash
     # 단일 디스패처 스크립트
     mkdir -p $PREFIX/local/bin
     cat > $PREFIX/local/bin/termux-dispatch << 'DISPATCH'
     #!/bin/bash
     cmd=$(basename "$0")
     exec bash -c "$(sed 's/com.termux.api/com.honeybadger.terminal/g' $PREFIX/bin/$cmd)" "$@"
     DISPATCH
     chmod +x $PREFIX/local/bin/termux-dispatch

     # 모든 termux-* 명령에 대해 symlink 생성
     for cmd in $PREFIX/bin/termux-*; do
       name=$(basename "$cmd")
       ln -sf termux-dispatch "$PREFIX/local/bin/$name"
     done
     ```
   - `$PREFIX/local/bin`이 PATH에서 `$PREFIX/bin`보다 앞에 오도록 환경변수 설정 확인
   - 이 방식은 `pkg upgrade`로 원본이 갱신되거나 새 명령어가 추가되어도 wrapper가 유지됨
   - v1의 npm/npx wrapper 패턴(`scripts/install-nodejs.sh` 228행~) 참고
   - 검증 로직 (`$PREFIX/glibc/lib/ld-linux-aarch64.so.1` 존재 확인)
   - 완료 마커 생성 (`$HOME/.honeybadger/.glibc-ready`)
   - 에러 처리, 진행 상태 출력
3. `v2/SPEC.md` 작성
   - 5장(첫 실행 플로우 상세)
   - 6장(오버레이 맵) — 디스패처 스크립트, symlink, PATH 오버라이드를 오버레이 항목으로 기록
4. 변경사항을 커밋한다

**주의사항**:
- pacman SigLevel 워크어라운드가 필요할 수 있다 (install-glibc.sh 참고).
- `--assume-installed bash,patchelf,resolv-conf` 옵션 필수.
- 비대화형(unattended) 실행에 맞게 조정하라. 사용자 입력을 요구하지 마라.

**완료 기준**:
- [ ] `v2/honeybadger/first-run.sh` 작성됨
- [ ] 기존 install-glibc.sh의 주요 로직이 반영됨
- [ ] `v2/SPEC.md` 5장 작성됨
- [ ] 변경사항 커밋됨

---

### Phase 6: first-run.sh 앱 연결 + 배포 로직

**목표**: first-run.sh를 APK에 번들하고, bootstrap 추출 후 자동 실행되도록 앱 코드를 수정한다.

**선행 조건**: Phase 2 + Phase 5 완료

**참조 필수**: `android/app/src/.../BootstrapManager.kt` (v1의 `copyPostSetupScript`, `needsPostSetup` 패턴)

**작업**:

1. Termux의 bootstrap 설치 플로우를 파악한다
   - `v2/termux-app/`에서 첫 실행 시 bootstrap 추출 로직을 읽고 이해한다
   - 추출 완료 후 실행되는 콜백/이벤트를 확인한다
   - **설정 파일 보호**: bootstrap 추출 시 패치한 apt.conf, sources.list 등이 `pkg upgrade`로 덮어씌워지는지 확인하라. Termux가 이 문제를 어떻게 처리하는지 파악하고, 동일한 방식을 적용하라. 확인 결과를 SPEC.md 6장에 기록하라.
2. first-run.sh 배포 로직을 구현한다
   - `first-run.sh`를 APK assets에 포함 (폴백용)
   - 앱이 첫 실행 시 GitHub에서 최신 버전 다운로드 시도, 실패 시 APK 내장 버전 사용
   - 다운로드 URL: `https://raw.githubusercontent.com/AidanPark/openclaw-android/main/v2/honeybadger/first-run.sh`
   - **개발 중에는 push 전이므로 GitHub URL이 동작하지 않는다. APK 내장 버전이 사용된다. GitHub URL은 관리자가 push한 후 활성화된다.**
   - v1의 `BootstrapManager.copyPostSetupScript()` 패턴 참고
3. bootstrap 추출 완료 후 자동 실행을 연결한다
   - bootstrap 완료 시점에 `first-run.sh`를 터미널에서 실행하도록 트리거
   - 진행 상태를 사용자에게 표시 (터미널 출력)
4. PATH에 `$PREFIX/local/bin`을 추가한다
   - `$PREFIX/local/bin`이 `$PREFIX/bin`보다 앞에 오도록 PATH 설정
   - 앱의 환경변수 설정 코드(Termux의 TermuxConstants 또는 환경 빌더)에서 수정
   - 이를 통해 termux-api CLI 디스패처가 원본보다 우선 실행됨
5. 재실행 방지를 구현한다
   - 마커 파일 `$HOME/.honeybadger/.glibc-ready`로 첫 실행 완료 여부 판별
   - 앱 시작 시 마커 존재하면 first-run.sh 실행 skip
6. 빌드를 확인한다
7. `v2/SPEC.md` 업데이트
   - 2장(의존성 맵)
   - 6장(오버레이 맵) — 설정 파일 오버레이, PATH 추가를 기록
   - 7장(유지보수 주의사항) — 설정 파일 보호 확인 결과 포함
8. 변경사항을 커밋한다

**완료 기준**:
- [ ] first-run.sh가 APK assets에 포함됨
- [ ] GitHub 다운로드 + APK 폴백 로직 구현됨
- [ ] bootstrap 추출 후 자동 실행 연결됨
- [ ] PATH에 `$PREFIX/local/bin` 추가됨
- [ ] 재실행 시 skip 로직 동작
- [ ] 설정 파일 보호 방식 확인 및 SPEC.md에 기록됨
- [ ] `./gradlew assembleDebug` 성공
- [ ] `v2/SPEC.md` 업데이트됨
- [ ] 변경사항 커밋됨

---

### Phase 7: About 화면 + README + E2E 검증 + SPEC.md 최종화

**목표**: Termux 크레딧 About 화면 추가, README 작성, E2E 검증, 앱 사양서 최종화.

**선행 조건**: Phase 1~6 모두 완료

**참조 필수**: `android/app/src/.../MainActivity.kt`

**작업**:

1. About 화면을 추가한다
   - Termux 프로젝트 크레딧 및 라이선스(GPLv3) 표시
   - "Built on Termux (https://github.com/termux/termux-app)" 문구 포함
   - Honey Badger 버전 정보
   - 링크: Termux GitHub, 이 프로젝트 GitHub (`https://github.com/AidanPark/openclaw-android`)
2. `v2/README.md`를 작성한다
   - Honey Badger가 무엇인지
   - 설치 방법, 사용 방법
   - Termux 크레딧 및 라이선스 (GPLv3)
3. 전체 통합 빌드를 확인한다
   - `./gradlew clean assembleDebug`
4. E2E 검증을 수행한다 (기기 연결 시)
5. `v2/SPEC.md` 최종화
   - 모든 장이 완성되어 있는지 확인
   - 6장(유지보수 주의사항) hook 작업 목록 최종 정리
6. 변경사항을 커밋한다

**완료 기준**:
- [ ] About 화면에 Termux 크레딧 표시됨
- [ ] `v2/README.md` 작성됨
- [ ] `./gradlew clean assembleDebug` 성공
- [ ] E2E 검증 통과 (기기 연결 시) 또는 skip 사유 기록
- [ ] `v2/SPEC.md` 최종화됨
- [ ] 변경사항 커밋됨

---

## 12. 결정된 사항 (구현 시 참고)

| 항목 | 결정 |
|------|------|
| 커스텀 코드 방식 | 실용적 절충 — 가능하면 별도 파일로 분리, 훅이 필요한 곳은 Termux 원본 최소 수정. 엄격한 레이어 분리보다 실용성 우선 |
| Termux:Widget 통합 | 불필요 — Boot으로 자동 시작하면 홈 화면 위젯 불필요 |
| Termux:Tasker 통합 | 불필요 — 에이전트 자체가 자동화 역할을 하므로 Tasker 연동 불필요 |
| Termux:Float 통합 | 불필요 — 에이전트는 백그라운드 실행이 주 사용 패턴 |
| 앱 아이콘 | Termux 원본 아이콘 그대로 사용 (추후 교체) |

## 13. 미결 사항

| 항목 | 상태 |
|------|------|
| 서비스 타입 1 (Termux + 스크립트)의 향후 방향 | 추후 결정 |
| F-Droid 제출 절차 및 일정 | 앱 완성 후 조사 |

## 14. 용어집

| 용어 | 정의 |
|------|------|
| glibc-runner | Termux pacman 패키지. glibc 동적 링커 + 라이브러리를 제공하여 표준 리눅스(glibc 링크) 바이너리를 Android에서 실행 가능하게 함 |
| Bootstrap | 앱 첫 실행 시 추출되는 최소 패키지 세트 (bash, coreutils, apt 등). 리눅스 환경의 기반 |
| W^X | Write XOR Execute — Android SELinux 정책 (targetSdk 29+). 앱 데이터 디렉토리에서 바이너리 실행을 차단 |
| Termux:API | Termux 플러그인. Android 기기 기능(카메라, SMS, 센서)을 CLI 명령어로 노출 |
| Termux:Boot | Termux 플러그인. 기기 부팅 시 사용자 스크립트를 자동 실행 |
| bionic | Android의 C 라이브러리. 표준 Linux의 glibc와 호환되지 않음 |
| $PREFIX | Termux 환경의 루트 디렉토리. `/data/data/<package>/files/usr/` |
