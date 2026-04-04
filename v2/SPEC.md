# Honey Badger 앱 사양서

## 1. Termux 원본 대비 수정 목록

### Phase 2: 패키지명 변경 + 브랜딩 + 빌드 확인

#### 1.1 `app/build.gradle`
- `namespace`를 `"com.termux"`에서 `"com.honeybadger.terminal"`로 변경
- `applicationId "com.honeybadger.terminal"` 추가
- `manifestPlaceholders.TERMUX_PACKAGE_NAME`을 `"com.honeybadger.terminal"`로 변경
- `manifestPlaceholders.TERMUX_APP_NAME`을 `"Honey Badger"`로 변경
- `ndk { abiFilters 'arm64-v8a' }` 추가 (arm64 전용)

#### 1.2 `app/src/main/AndroidManifest.xml`
- `android:sharedUserId` 및 `android:sharedUserLabel` 속성 제거 (플러그인을 본체에 통합하므로 불필요. 또한 다른 UID를 가진 com.termux 앱과 충돌 방지)
- `.shared.activities.ReportActivity` → `com.termux.shared.activities.ReportActivity` (fully qualified name으로 변경. namespace 변경 후 상대 경로가 잘못된 모듈을 가리키는 문제 수정)
- `.shared.activities.ReportActivity$ReportActivityBroadcastReceiver` → `com.termux.shared.activities.ReportActivity$ReportActivityBroadcastReceiver` (동일 이유)

#### 1.3 Java 소스 디렉토리 구조 변경
- `app/src/main/java/com/termux/app/` → `app/src/main/java/com/honeybadger/terminal/app/`
- `app/src/main/java/com/termux/filepicker/` → `app/src/main/java/com/honeybadger/terminal/filepicker/`
- `app/src/test/java/com/termux/app/` → `app/src/test/java/com/honeybadger/terminal/app/`

#### 1.4 Java 소스 파일 package/import 변경 (35개 파일)
모든 `app/src/` 내 Java 파일에서:
- `package com.termux.app.*` → `package com.honeybadger.terminal.app.*`
- `package com.termux.filepicker` → `package com.honeybadger.terminal.filepicker`
- `import com.termux.app.*` → `import com.honeybadger.terminal.app.*`
- `import com.termux.filepicker.*` → `import com.honeybadger.terminal.filepicker.*`
- `import com.termux.R` → `import com.honeybadger.terminal.R`
- `import com.termux.BuildConfig` → `import com.honeybadger.terminal.BuildConfig`
- 코드 내 fully qualified 참조: `com.termux.app.fragments.*` → `com.honeybadger.terminal.app.fragments.*`

유지된 import:
- `import com.termux.shared.*` — termux-shared 라이브러리 모듈
- `import com.termux.terminal.*` — terminal-emulator 라이브러리 모듈
- `import com.termux.view.*` — terminal-view 라이브러리 모듈

#### 1.5 XML 리소스 파일 변경
- `app/src/main/res/values/strings.xml`: ENTITY `TERMUX_PACKAGE_NAME` → `"com.honeybadger.terminal"`, `TERMUX_APP_NAME` → `"Honey Badger"`
- `app/src/main/res/layout/activity_termux.xml`: 커스텀 뷰 참조 `com.termux.app.terminal.TermuxActivityRootView` → `com.honeybadger.terminal.app.terminal.TermuxActivityRootView` (`com.termux.view.TerminalView`는 유지)
- `app/src/main/res/xml/shortcuts.xml`: `targetPackage`, `targetClass`, extra name의 `com.termux` → `com.honeybadger.terminal`
- `app/src/main/res/xml/root_preferences.xml`: fragment 참조 `com.termux.app.fragments.*` → `com.honeybadger.terminal.app.fragments.*`
- `app/src/main/res/xml/termux_preferences.xml`: 동일 fragment 참조 변경
- `app/src/main/res/xml/termux_widget_preferences.xml`: 동일
- `app/src/main/res/xml/termux_tasker_preferences.xml`: 동일
- `app/src/main/res/xml/termux_float_preferences.xml`: 동일

#### 1.6 JNI 네이티브 코드 변경
- `app/src/main/cpp/termux-bootstrap.c`: JNI 함수명 `Java_com_termux_app_TermuxInstaller_getZip` → `Java_com_honeybadger_terminal_app_TermuxInstaller_getZip`

#### 1.7 termux-shared 모듈 상수 변경
- `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java`:
  - `TERMUX_APP_NAME`: `"Termux"` → `"Honey Badger"`
  - `TERMUX_PACKAGE_NAME`: `"com.termux"` → `"com.honeybadger.terminal"`
  - 이 변경으로 `TERMUX_FILES_DIR_PATH`, `TERMUX_PREFIX_DIR_PATH` 등 파생 상수도 자동으로 새 패키지명을 사용
- `termux-shared/src/main/res/values/strings.xml`:
  - ENTITY `TERMUX_PACKAGE_NAME` → `"com.honeybadger.terminal"`
  - ENTITY `TERMUX_APP_NAME` → `"Honey Badger"`
  - ENTITY `TERMUX_PREFIX_DIR_PATH` → `"/data/data/com.honeybadger.terminal/files/usr"`

#### 1.8 Lint 수정
- `app/src/main/java/com/honeybadger/terminal/app/TermuxActivity.java`: `onBackPressed()` 메서드에 `@SuppressLint("MissingSuperCall")` 추가 (Termux 원본의 의도적 동작, super 호출 생략)

### Phase 6: first-run.sh 앱 연결 + 배포 로직

#### 1.9 `app/src/main/java/com/honeybadger/terminal/app/TermuxInstaller.java`
- Bootstrap 추출 후 com.termux 경로 패치 추가 (`fixBootstrapPaths()`)
- SYMLINKS.txt 처리 시 com.termux 경로 치환 추가
- apt.conf 생성 (`configureApt()`)
- dpkg wrapper/binary patch 생성 (`createDpkgWrapper()`)
- tar wrapper 생성 (경로 rewriting)
- bpatch 도구 assets에서 복사
- first-run.sh GitHub 다운로드 + assets 폴백 (`copyFirstRunScript()`)
- `.bash_profile` first-run 트리거 설치 (`installFirstRunProfile()`)
- `@SuppressLint("NewApi")` 클래스 레벨 추가 (java.nio.file.Files API 26+ 사용)

#### 1.10 `app/src/main/java/com/honeybadger/terminal/app/TermuxActivity.java`
- `onServiceConnected()`의 bootstrap 콜백에서 first-run 체크 및 `.bash_profile` 설치 추가
- `TermuxSession` import 제거 (사용하지 않음)

#### 1.11 `termux-shared/.../TermuxShellEnvironment.java`
- PATH에 `$PREFIX/local/bin` 추가 (overlay bin 우선순위)
- `LD_LIBRARY_PATH` 설정 추가 (DT_RUNPATH com.termux 문제 해결)
- `APT_CONFIG` 환경변수 설정 추가

#### 1.12 `terminal-view/build.gradle`, `termux-shared/build.gradle`
- `lint { abortOnError false }` 추가 (라이브러리 모듈의 기존 lint 에러 억제)

#### 1.13 APK assets
- `first-run.sh` — glibc-runner 자동 설치 스크립트
- `bpatch` — ARM64 바이너리 패치 도구 (NDK 크로스 컴파일)

### Phase 7: About 화면 + README

#### 1.14 `app/src/main/java/com/honeybadger/terminal/app/activities/AboutActivity.java` (신규)
- Termux 프로젝트 크레딧 및 GPLv3 라이선스 표시 Activity

#### 1.15 `app/src/main/res/layout/activity_about.xml` (신규)
- About 화면 레이아웃 (ScrollView, 다크 테마)

#### 1.16 `app/src/main/res/values/strings.xml` (추가)
- About 화면 관련 문자열 12개 추가

#### 1.17 `app/src/main/AndroidManifest.xml` (추가)
- `AboutActivity` 등록

#### 1.18 `app/src/main/java/com/honeybadger/terminal/app/TermuxActivity.java` (수정)
- Context 메뉴에 "About" 항목 추가 (`CONTEXT_MENU_ABOUT_ID = 12`)

## 2. 모듈 간 의존성 맵

### Phase 6 의존성

- `TermuxInstaller.java` → `TermuxConstants.TERMUX_PACKAGE_NAME` (패키지명으로 경로 치환)
- `TermuxInstaller.java` → APK assets (`first-run.sh`, `bpatch`)
- `TermuxActivity.java` → `TermuxInstaller.needsFirstRun()`, `copyFirstRunScript()`, `installFirstRunProfile()`
- `TermuxShellEnvironment.java` → `TermuxConstants.TERMUX_PREFIX_DIR_PATH` (PATH, LD_LIBRARY_PATH, APT_CONFIG 경로)
- `first-run.sh` → `bpatch` (pacman/libalpm 바이너리 패치)
- `first-run.sh` → `tar.real` (패키지 추출 시 경로 변환)
- `first-run.sh` → `.bash_profile` (로그인 셸 시작 시 자동 실행)

### Phase 7 의존성

- `AboutActivity.java` → `BuildConfig.VERSION_NAME` (앱 버전 표시)
- `AboutActivity.java` → `R.layout.activity_about`, `R.string.about_*` (레이아웃, 문자열)
- `TermuxActivity.java` → `AboutActivity.class` (메뉴에서 About 화면 실행)
- `AndroidManifest.xml` → `AboutActivity` (Activity 등록)

## 3. 패키지명 치환 맵

### 3.1 치환한 문자열

| 원본 | 치환 후 | 맥락 |
|------|---------|------|
| `com.termux` (applicationId) | `com.honeybadger.terminal` | build.gradle applicationId |
| `com.termux` (namespace) | `com.honeybadger.terminal` | build.gradle namespace |
| `com.termux` (manifestPlaceholders) | `com.honeybadger.terminal` | build.gradle manifestPlaceholders |
| `package com.termux.app.*` | `package com.honeybadger.terminal.app.*` | Java package 선언 |
| `package com.termux.filepicker` | `package com.honeybadger.terminal.filepicker` | Java package 선언 |
| `import com.termux.app.*` | `import com.honeybadger.terminal.app.*` | Java import 문 |
| `import com.termux.filepicker.*` | `import com.honeybadger.terminal.filepicker.*` | Java import 문 |
| `import com.termux.R` | `import com.honeybadger.terminal.R` | 앱 모듈 R 클래스 |
| `import com.termux.BuildConfig` | `import com.honeybadger.terminal.BuildConfig` | 앱 모듈 BuildConfig |
| `com.termux.app.fragments.*` (fully qualified) | `com.honeybadger.terminal.app.fragments.*` | Java 코드 및 XML fragment 참조 |
| `com.termux.app.terminal.TermuxActivityRootView` | `com.honeybadger.terminal.app.terminal.TermuxActivityRootView` | XML 레이아웃 커스텀 뷰 |
| `android:targetPackage="com.termux"` | `android:targetPackage="com.honeybadger.terminal"` | shortcuts.xml |
| `android:targetClass="com.termux.app.*"` | `android:targetClass="com.honeybadger.terminal.app.*"` | shortcuts.xml |
| `android:name="com.termux.app.*"` | `android:name="com.honeybadger.terminal.app.*"` | shortcuts.xml extra |
| `TERMUX_PACKAGE_NAME = "com.termux"` | `TERMUX_PACKAGE_NAME = "com.honeybadger.terminal"` | TermuxConstants.java 상수 |
| `TERMUX_APP_NAME = "Termux"` | `TERMUX_APP_NAME = "Honey Badger"` | TermuxConstants.java 상수 |
| `ENTITY TERMUX_PACKAGE_NAME "com.termux"` | `ENTITY TERMUX_PACKAGE_NAME "com.honeybadger.terminal"` | strings.xml (app, termux-shared) |
| `ENTITY TERMUX_APP_NAME "Termux"` | `ENTITY TERMUX_APP_NAME "Honey Badger"` | strings.xml (app, termux-shared) |
| `ENTITY TERMUX_PREFIX_DIR_PATH "/data/data/com.termux/files/usr"` | `ENTITY ... "com.honeybadger.terminal/files/usr"` | termux-shared strings.xml |
| `Java_com_termux_app_TermuxInstaller_getZip` | `Java_com_honeybadger_terminal_app_TermuxInstaller_getZip` | JNI 함수명 (C) |

### 3.2 치환하지 않은 문자열

| 문자열 | 위치 | 유지 이유 |
|--------|------|----------|
| `import com.termux.shared.*` | app 모듈 Java 파일 전체 | termux-shared 라이브러리 모듈 패키지명. DESIGN.md 규칙: 라이브러리 모듈 패키지명 유지 |
| `import com.termux.terminal.*` | app 모듈 Java 파일 (TermuxService 등) | terminal-emulator 라이브러리 모듈 패키지명 유지 |
| `import com.termux.view.*` | activity_termux.xml, Java 파일 | terminal-view 라이브러리 모듈 패키지명 유지 |
| `com.termux.view.TerminalView` | activity_termux.xml | terminal-view 모듈의 커스텀 뷰 |
| `com.termux.shared.R.string.*` | TermuxService.java (line 294) | termux-shared 모듈의 리소스 참조 |
| `// Create "Android/data/com.termux" symlinks` | TermuxInstaller.java (line 338) | 주석 |
| `// Create "Android/media/com.termux" symlinks` | TermuxInstaller.java (line 350) | 주석 |
| `// ... {com.termux/com.termux.app.TermuxActivity}` | TermuxTerminalSessionActivityClient.java (line 276) | 주석 (에러 메시지 예시) |
| `If applicationId ... changed from "com.termux"` | shortcuts.xml (line 6, 8) | XML 주석 |
| `com.termux.shared.termux.TermuxBootstrap.PackageVariant` | app/build.gradle (line 7) | 주석 |
| `package com.termux.shared.*` | termux-shared 모듈 전체 | 라이브러리 모듈 Java 패키지명 유지 |
| `package com.termux.terminal.*` | terminal-emulator 모듈 전체 | 라이브러리 모듈 Java 패키지명 유지 |
| `package com.termux.view.*` | terminal-view 모듈 전체 | 라이브러리 모듈 Java 패키지명 유지 |

### 3.3 TermuxConstants 파생 상수 (자동 변경)

`TERMUX_PACKAGE_NAME` 변경으로 다음 상수들이 런타임에 자동으로 새 경로를 사용:
- `TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH`: `/data/data/com.honeybadger.terminal`
- `TERMUX_FILES_DIR_PATH`: `/data/data/com.honeybadger.terminal/files`
- `TERMUX_PREFIX_DIR_PATH`: `/data/data/com.honeybadger.terminal/files/usr`
- `TERMUX_HOME_DIR_PATH`: `/data/data/com.honeybadger.terminal/files/home`
- 기타 모든 파생 경로 상수

## 4. 통합된 플러그인 (API, Boot)

### 4.1 Termux:API 통합 (Phase 3)

#### 4.1.1 가져온 소스 파일 목록

`v2/termux-api/app/src/main/java/com/termux/api/` → `v2/termux-app/app/src/main/java/com/honeybadger/terminal/api/`

| 원본 파일 | 대상 파일 | 비고 |
|-----------|-----------|------|
| `TermuxApiReceiver.java` | `api/TermuxApiReceiver.java` | API 호출 디스패처 (BroadcastReceiver) |
| `TermuxAPIConstants.java` | `api/TermuxAPIConstants.java` | 상수 정의 |
| `TermuxAPIApplication.java` | 삭제 (기능을 `TermuxApplication`에 통합) | `SocketListener` 초기화와 `ResultReturner.setContext()`를 `TermuxApplication.onCreate()`에 병합 |
| `SocketListener.java` | `api/SocketListener.java` | Unix 소켓 기반 API 리스너 |
| `KeepAliveService.java` | `api/KeepAliveService.java` | 백그라운드 유지 서비스 |
| `apis/*.java` (36개 파일) | `api/apis/*.java` | 개별 API 구현 (Audio, Battery, Camera, Clipboard 등) |
| `util/ResultReturner.java` | `api/util/ResultReturner.java` | 소켓 기반 결과 반환 |
| `util/PluginUtils.java` | `api/util/PluginUtils.java` | PendingIntent 유틸리티 |
| `util/PendingIntentUtils.java` | `api/util/PendingIntentUtils.java` | PendingIntent 플래그 헬퍼 |
| `util/JsonUtils.java` | `api/util/JsonUtils.java` | JSON 유틸리티 |
| `util/ViewUtils.java` | `api/util/ViewUtils.java` | UI 유틸리티 |
| `activities/TermuxApiPermissionActivity.java` | `api/activities/TermuxApiPermissionActivity.java` | 런타임 퍼미션 요청 Activity |
| `activities/TermuxAPIMainActivity.java` | `api/activities/TermuxAPIMainActivity.java` | API 메인 Activity (사용하지 않지만 코드 보존) |
| `settings/activities/TermuxAPISettingsActivity.java` | `api/settings/activities/TermuxAPISettingsActivity.java` | API 설정 Activity |
| `settings/fragments/termux_api_app/TermuxAPIPreferencesFragment.java` | `api/settings/fragments/termux_api_app/TermuxAPIPreferencesFragment.java` | 설정 Fragment |
| `settings/fragments/termux_api_app/debugging/DebuggingPreferencesFragment.java` | `api/settings/fragments/termux_api_app/debugging/DebuggingPreferencesFragment.java` | 디버깅 설정 Fragment |
| 신규: `TermuxAPIAppUtils.java` | `api/TermuxAPIAppUtils.java` | `TermuxAPIApplication.setLogConfig()` 대체 유틸리티 |

#### 4.1.2 수정한 부분과 이유

**패키지명 변경:**
- 모든 Java 파일: `package com.termux.api.*` → `package com.honeybadger.terminal.api.*`
- 모든 Java 파일: `import com.termux.api.*` → `import com.honeybadger.terminal.api.*`
- R 클래스 import: `import com.termux.api.R` → `import com.honeybadger.terminal.R` (본체 앱 모듈의 R 사용)

**라이브러리 import 유지 (변경하지 않음):**
- `import com.termux.shared.*` — termux-shared 라이브러리 모듈
- `TermuxConstants.TERMUX_PACKAGE_NAME`, `TermuxConstants.TERMUX_API_PACKAGE_NAME` 등 — 런타임에 올바른 패키지명 반환

**앱 초기화 통합:**
- `TermuxAPIApplication.java` 삭제
- `TermuxApplication.onCreate()`에 두 줄 추가:
  - `ResultReturner.setContext(this)` — API 결과 반환에 필요한 Context 설정
  - `SocketListener.createSocketListener(this)` — Unix 소켓 기반 API 리스너 시작
- `TermuxAPIAppUtils.java` 신규 생성 — `setLogConfig()` 정적 메서드 제공

**Lint 억제:**
- 12개 API 파일에 `@SuppressLint({"NewApi"})`, `@SuppressLint({"MissingPermission"})`, `@SuppressLint({"RestrictedApi"})` 추가
- 이유: termux-api 원본 코드가 minSdk 21 미만 API를 런타임 체크 없이 사용하는 부분 존재. 원본 동작 유지를 위해 변경하지 않고 lint만 억제.

#### 4.1.3 AndroidManifest에 추가한 항목

**퍼미션 (신규 추가):**
- `ACCESS_BACKGROUND_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `BODY_SENSORS`
- `CALL_PHONE`
- `CAMERA`
- `NFC`
- `READ_CALL_LOG`, `READ_CONTACTS`, `READ_PHONE_STATE`, `READ_PRIVILEGED_PHONE_STATE`
- `READ_SMS`, `SEND_SMS`
- `RECORD_AUDIO`
- `REQUEST_DELETE_PACKAGES`
- `SET_WALLPAPER`
- `TRANSMIT_IR`
- `USE_BIOMETRIC`
- `WRITE_SETTINGS`

**퍼미션 (이미 존재, 추가 불필요):**
- `INTERNET`, `ACCESS_NETWORK_STATE`, `VIBRATE`, `SYSTEM_ALERT_WINDOW`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `REQUEST_INSTALL_PACKAGES`
- `RECEIVE_BOOT_COMPLETED`, `DUMP`, `PACKAGE_USAGE_STATS`
- `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`

**커스텀 퍼미션:**
- `${TERMUX_PACKAGE_NAME}.sharedfiles.READ_WRITE` (protectionLevel="signature") — ShareAPI ContentProvider용

**하드웨어 Features (모두 `required="false"`):**
- `camera`, `camera.autofocus`, `location`, `location.gps`, `location.network`
- `microphone`, `telephony`, `wifi`, `usb.host`

**Activity:**
- `.api.activities.TermuxApiPermissionActivity` — 퍼미션 요청 (unexported)
- `.api.apis.DialogAPI$DialogActivity` — 대화상자 (theme: DialogTheme)
- `.api.apis.FingerprintAPI$FingerprintActivity` — 지문인식 (theme: TransparentTheme)
- `.api.apis.NfcAPI$NfcActivity` — NFC 태그 처리 (intent-filter: TAG/NDEF/TECH_DISCOVERED)
- `.api.apis.SAFAPI$SAFActivity` — Storage Access Framework
- `.api.apis.StorageGetAPI$StorageActivity` — 스토리지 선택

**Provider:**
- `.api.apis.ShareAPI$ContentProvider` — 파일 공유 (authority: `${TERMUX_PACKAGE_NAME}.sharedfiles`)

**Receiver:**
- `.api.TermuxApiReceiver` — API 호출 수신 BroadcastReceiver (unexported)

**Service:**
- `.api.KeepAliveService` — 백그라운드 유지
- `.api.apis.JobSchedulerAPI$JobSchedulerService` — 작업 스케줄러
- `.api.apis.MediaPlayerAPI$MediaPlayerService` — 미디어 재생
- `.api.apis.MicRecorderAPI$MicRecorderService` — 마이크 녹음
- `.api.apis.NotificationListAPI$NotificationService` — 알림 리스너 (exported, BIND_NOTIFICATION_LISTENER_SERVICE)
- `.api.apis.SensorAPI$SensorReaderService` — 센서 읽기
- `.api.apis.SpeechToTextAPI$SpeechToTextService` — 음성→텍스트
- `.api.apis.TextToSpeechAPI$TextToSpeechService` — 텍스트→음성
- `.api.apis.WallpaperAPI$WallpaperService` — 배경화면 설정
- `.api.apis.UsbAPI$UsbService` — USB 접근

#### 4.1.4 리소스 파일

**레이아웃 (신규):**
- `activity_termux_api_main.xml` — API 메인 Activity 레이아웃
- `activity_termux_api_settings.xml` — API 설정 Activity 레이아웃
- `dialog_counter.xml` — DialogAPI용 카운터
- `dialog_textarea_input.xml` — DialogAPI용 텍스트 입력
- `spinner_item.xml` — DialogAPI용 스피너 항목

**메뉴 (신규):**
- `menu/activity_termux_api_main.xml` — API 메인 Activity 메뉴

**XML 설정 (신규):**
- `xml/nfc_tech_filter.xml` — NFC 기술 필터
- `xml/sets__termux_api.xml` — API 설정 화면 (원본 `sets__termux.xml`에서 이름 변경)
- `xml/prefs__termux_api_app___prefs__app.xml` — API 앱 설정
- `xml/prefs__termux_api_app___prefs__app___prefs__debugging.xml` — API 디버깅 설정

**드로어블 (신규):**
- `drawable/ic_event_note_black_24dp.xml` — NotificationAPI에서 사용하는 아이콘

**값 (기존 파일에 추가):**
- `values/strings.xml` — API 관련 문자열 추가 (plugin_info, 퍼미션 관련 메시지, 설정 레이블 등)
- `values/styles.xml` — `DialogTheme`, `TransparentTheme`, `ButtonBar`, `ButtonBarButton` 스타일 추가
- `values/attrs.xml` — `ButtonBarContainerTheme` declare-styleable 추가

#### 4.1.5 build.gradle 변경

**의존성 추가:**
- `androidx.biometric:biometric:1.2.0-alpha05` — FingerprintAPI에서 사용
- `androidx.media:media:1.7.0` — MediaPlayerAPI에서 사용

#### 4.1.6 원본 플러그인과의 동작 차이점

| 항목 | 원본 (termux-api 별도 APK) | 통합 후 (Honey Badger 본체) |
|------|---------------------------|---------------------------|
| 설치 | 별도 APK 설치 필요 | 본체에 포함, 추가 설치 불필요 |
| sharedUserId | `com.termux` (본체와 공유) | 불필요 (같은 프로세스) |
| SocketListener 주소 | `com.termux.api://listen` | `com.honeybadger.terminal.api://listen` (TermuxConstants에서 자동 결정) |
| Intent 구조 | CLI → explicit broadcast to `com.termux.api` | CLI → explicit broadcast to `com.honeybadger.terminal` (패키지명만 변경, Intent 구조 유지) |
| Application 클래스 | `TermuxAPIApplication` | `TermuxApplication` (기존 앱 Application에 초기화 병합) |
| 런처 Activity | 별도 런처 아이콘 | 없음 (본체 앱의 런처만 존재) |
| API 설정 | 별도 앱 설정 | 본체 앱 설정에서 접근 가능 |

### 4.2 Termux:Boot 통합 (Phase 4)

#### 4.2.1 가져온 소스 파일 목록

`v2/termux-boot/app/src/main/java/com/termux/boot/` → `v2/termux-app/app/src/main/java/com/honeybadger/terminal/boot/`

| 원본 파일 | 대상 파일 | 비고 |
|-----------|-----------|------|
| `BootReceiver.java` | `boot/BootReceiver.java` | 부팅 시 `~/.termux/boot/` 스크립트를 JobScheduler로 실행 |
| `BootJobService.java` | `boot/BootJobService.java` | 개별 부트 스크립트를 TermuxService에 위임 실행 |
| `BootActivity.java` | 삭제 (통합 불필요) | WebView로 overview.html을 표시하는 단순 Activity. 본체 앱에 별도 런처 아이콘 불필요 |

#### 4.2.2 수정한 부분과 이유

**패키지명 변경:**
- 모든 Java 파일: `package com.termux.boot` → `package com.honeybadger.terminal.boot`

**하드코딩된 경로/상수를 TermuxConstants로 교체:**
- `BootReceiver.java`: 하드코딩된 `/data/data/com.termux/files/home/.termux/boot` → `TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/boot"` (패키지명 변경에 자동 대응)
- `BootJobService.java`: 하드코딩된 `"com.termux.boot.script_path"` → `TermuxConstants.TERMUX_PACKAGE_NAME + ".boot.script_path"`
- `BootJobService.java`: 하드코딩된 `"com.termux.app.TermuxService"`, `"com.termux.service_execute"`, `"com.termux.execute.background"` → `TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME`, `TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE`, `TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND`
- `BootJobService.java`: 하드코딩된 URI scheme `"com.termux.file"` → `TermuxConstants.TERMUX_PACKAGE_NAME + ".file"`

**라이브러리 import 유지 (변경하지 않음):**
- `import com.termux.shared.*` — termux-shared 라이브러리 모듈

#### 4.2.3 AndroidManifest에 추가한 항목

**퍼미션 (이미 존재, 추가 불필요):**
- `RECEIVE_BOOT_COMPLETED` — Phase 2에서 이미 선언됨
- `WAKE_LOCK` — Phase 2에서 이미 선언됨 (`WAKE_LOCK`이 아닌 `FOREGROUND_SERVICE`로 대체)

**Receiver:**
- `.boot.BootReceiver` — 부팅 완료 시 `~/.termux/boot/` 디렉토리의 스크립트를 탐색하여 JobScheduler로 실행 (unexported, intent-filter: `BOOT_COMPLETED`)

**Service:**
- `.boot.BootJobService` — JobService. 개별 부트 스크립트를 TermuxService에 위임하여 실행 (unexported, permission: `BIND_JOB_SERVICE`)

#### 4.2.4 부팅 스크립트 실행 흐름

```
기기 부팅
  → Android → BOOT_COMPLETED broadcast
  → BootReceiver.onReceive()
  → ~/.termux/boot/ 디렉토리 스캔
  → 파일명 알파벳순 정렬
  → 각 스크립트를 JobScheduler에 등록 (3초 deadline)
  → BootJobService.onStartJob()
  → TermuxService에 ACTION_SERVICE_EXECUTE intent 전송
  → TermuxService가 백그라운드에서 스크립트 실행
```

#### 4.2.5 원본 플러그인과의 동작 차이점

| 항목 | 원본 (termux-boot 별도 APK) | 통합 후 (Honey Badger 본체) |
|------|---------------------------|---------------------------|
| 설치 | 별도 APK 설치 필요 | 본체에 포함, 추가 설치 불필요 |
| sharedUserId | `com.termux` (본체와 공유) | 불필요 (같은 프로세스) |
| 패키지명/경로 | 하드코딩된 `com.termux` 경로 | `TermuxConstants`에서 동적 결정 |
| BootActivity | 별도 런처 아이콘 + WebView 도움말 | 없음 (본체 앱의 런처만 존재) |
| Intent 대상 | `com.termux` 패키지의 `TermuxService` | `com.honeybadger.terminal` 패키지의 `TermuxService` (같은 프로세스) |
| 스크립트 경로 | `/data/data/com.termux/files/home/.termux/boot/` | `/data/data/com.honeybadger.terminal/files/home/.termux/boot/` |

## 5. 첫 실행 플로우 상세 (Phase 5)

### 5.1 스크립트 개요

파일: `v2/honeybadger/first-run.sh`

Bootstrap 추출 완료 후 앱이 자동 실행하는 스크립트. 비대화형(unattended)으로 동작하며 사용자 입력을 요구하지 않는다. 4단계로 구성된다.

### 5.2 단계별 상세

#### [1/4] pacman 설치

- 명령어: `pkg install -y pacman`
- 전제: bootstrap 추출 완료 후 `pkg` 명령어 사용 가능
- 실패 가능 지점: 네트워크 불가, 패키지 저장소 미응답
- 대응: 에러 메시지 출력 후 exit 1. 사용자가 네트워크 확인 후 재실행 가능 (마커 파일 미생성 상태이므로 재실행 시 처음부터 재시도)
- 스킵 조건: `command -v pacman` 성공 시 설치 스킵

#### [2/4] glibc-runner 설치

- 명령어: `pacman -Sy glibc-runner --noconfirm --assume-installed bash,patchelf,resolv-conf`
- `--assume-installed`: bash, patchelf, resolv-conf는 Termux apt로 이미 설치되어 있으나 pacman이 인식하지 못함. 이 옵션으로 의존성 해결 실패를 방지
- SigLevel 워크어라운드: 일부 기기에서 GPGME 크립토 엔진 버그로 서명 검증 실패. `pacman.conf`의 `SigLevel`을 `Never`로 임시 변경 후 설치 완료 시 원본 복원
  - 원본 백업: `$PREFIX/etc/pacman.conf.bak`
  - 설치 성공/실패 모두에서 원본 복원
- pacman 키링 초기화: `pacman-key --init`, `pacman-key --populate` (실패해도 계속 진행, SigLevel=Never로 우회 가능)
- 검증: `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` 실행 권한 확인
- `grun` 명령어 존재 여부 확인 (경고만, 필수는 아님)
- 실패 가능 지점: 네트워크 불가, pacman 저장소 미응답, 디스크 공간 부족
- 대응: 에러 메시지 출력 후 exit 1
- 스킵 조건: `$PREFIX/glibc/lib/ld-linux-aarch64.so.1`이 이미 실행 가능할 때
- 의존하는 외부 서비스: `service.termux-pacman.dev` (pacman 패키지 저장소)

#### [3/4] termux-api CLI 디스패처 + symlink

- 디스패처 스크립트 경로: `$PREFIX/local/bin/termux-dispatch`
- 동작 원리: 원본 `$PREFIX/bin/termux-*` 스크립트를 읽고, `sed`로 `com.termux.api`를 `com.honeybadger.terminal`로 치환한 뒤 `bash -c`로 실행
- symlink 생성: `$PREFIX/bin/termux-*`에 매칭되는 모든 명령어에 대해 `$PREFIX/local/bin/$name` → `termux-dispatch` symlink 생성
- PATH 설정: `.bashrc`에 `export PATH="$PREFIX/local/bin:$PATH"` 추가. `$PREFIX/local/bin`이 `$PREFIX/bin`보다 앞에 오므로 dispatcher가 원본보다 우선 실행됨
- 실패 가능 지점: `pkg install termux-api`가 아직 실행되지 않아 `$PREFIX/bin/termux-*`가 없을 수 있음. 이 경우 symlink 0개 생성 (에러 아님). 사용자가 나중에 `pkg install termux-api` 실행 후 first-run.sh를 재실행하거나 수동으로 symlink 추가 가능
- 의존하는 외부 서비스: 없음 (로컬 파일 조작만)

#### [4/4] 완료 마커

- 마커 파일: `$HOME/.honeybadger/.glibc-ready`
- 앱이 이 파일의 존재 여부로 첫 실행 완료를 판별
- 스크립트 시작 시 마커가 이미 존재하면 전체 스킵

### 5.3 재실행 안전성

- 모든 단계에 스킵 조건이 있어 중복 실행 시 안전 (idempotent)
- pacman: 이미 설치되어 있으면 스킵
- glibc-runner: ld.so가 이미 존재하면 스킵
- 디스패처: 항상 재생성 (최신 상태 보장)
- .bashrc PATH: 이미 존재하면 스킵
- 마커: 이미 존재하면 스크립트 전체 스킵

### 5.4 에러 처리 전략

- `set -euo pipefail`: 미정의 변수 사용, 명령 실패 시 즉시 중단
- 핵심 단계(pacman 설치, glibc-runner 설치, ld.so 검증) 실패 시 명확한 에러 메시지와 함께 exit 1
- SigLevel 패치는 실패 시에도 반드시 원본 복원
- 마커 파일은 모든 단계 성공 후에만 생성

## 6. 오버레이 맵

### 6.1 오버레이 목록

#### 6.1.1 termux-api CLI 디스패처

- **대상**: `pkg install termux-api`로 설치되는 `$PREFIX/bin/termux-*` CLI 스크립트. 이 스크립트들은 `com.termux.api` 패키지를 explicit Intent 대상으로 하드코딩하고 있음
- **방식**: PATH 우선순위 (`$PREFIX/local/bin/` > `$PREFIX/bin/`)
- **우리 파일 위치**:
  - `$PREFIX/local/bin/termux-dispatch` — 단일 디스패처 스크립트
  - `$PREFIX/local/bin/termux-*` — 각 명령어에 대한 symlink → `termux-dispatch`
- **동작 원리**: PATH에서 `$PREFIX/local/bin`이 `$PREFIX/bin`보다 앞에 있으므로, 사용자가 `termux-camera-photo`를 실행하면 `$PREFIX/local/bin/termux-camera-photo` (symlink → termux-dispatch)가 먼저 찾아짐. dispatcher는 원본 `$PREFIX/bin/termux-camera-photo`를 읽어 `com.termux.api`를 `com.honeybadger.terminal`로 치환한 뒤 실행함. 원본 파일은 전혀 수정하지 않음
- **pkg upgrade 영향**: `pkg upgrade`로 원본 `$PREFIX/bin/termux-*`가 갱신되어도 dispatcher가 항상 최신 원본을 읽어 치환하므로 영향 없음. 새 명령어가 추가된 경우 symlink를 수동 추가하거나 first-run.sh를 재실행하면 됨

#### 6.1.2 PATH 오버라이드

- **대상**: 셸 세션의 `$PATH` 환경변수
- **방식**: 환경변수 (`$PATH` 앞에 `$PREFIX/local/bin` 추가)
- **우리 파일 위치**: `$HOME/.bashrc` (PATH 설정 라인)
- **동작 원리**: `.bashrc`에서 `export PATH="$PREFIX/local/bin:$PATH"`를 설정하여 `$PREFIX/local/bin`의 wrapper/dispatcher가 `$PREFIX/bin`의 원본보다 우선 실행됨. 앱의 환경 빌더(Phase 6)에서도 동일하게 설정 예정
- **pkg upgrade 영향**: 없음. `.bashrc`는 패키지가 소유하지 않는 사용자 파일

#### 6.1.3 PATH 환경변수 (앱 레벨) (Phase 6)

- **대상**: 앱의 환경변수 설정 (`TermuxShellEnvironment.java`)
- **방식**: `$PREFIX/local/bin`을 `$PREFIX/bin`보다 앞에 PATH에 추가
- **동작 원리**: 앱의 환경 빌더에서 `ENV_PATH`에 `localBinPath + ":" + TERMUX_BIN_PREFIX_DIR_PATH`를 설정. `.bashrc`의 설정과 동일한 효과이나, 앱 레벨에서 설정하므로 `.bashrc`가 없어도 동작

#### 6.1.4 LD_LIBRARY_PATH 설정 (Phase 6)

- **대상**: 앱의 환경변수 설정 (`TermuxShellEnvironment.java`)
- **방식**: `$PREFIX/lib`을 `LD_LIBRARY_PATH`에 설정
- **동작 원리**: Bootstrap 바이너리의 DT_RUNPATH가 `/data/data/com.termux/files/usr/lib`를 가리키고 있어 동적 링커가 라이브러리를 찾지 못함. `LD_LIBRARY_PATH`를 명시적으로 설정하여 해결

#### 6.1.5 APT_CONFIG 환경변수 (Phase 6)

- **대상**: 앱의 환경변수 설정 (`TermuxShellEnvironment.java`)
- **방식**: `APT_CONFIG=$PREFIX/etc/apt/apt.conf` 환경변수 설정
- **동작 원리**: apt 바이너리가 `/data/data/com.termux/files/usr/etc/apt/apt.conf` 경로를 하드코딩하고 있으므로 `APT_CONFIG` 환경변수로 올바른 경로를 지정

#### 6.1.6 Bootstrap 경로 패치 (Phase 6)

- **대상**: Bootstrap ZIP에서 추출된 모든 텍스트 파일 (bin/, etc/, libexec/, var/lib/dpkg/ 내)
- **방식**: `TermuxInstaller.fixBootstrapPaths()`에서 `/data/data/com.termux/` → `/data/data/com.honeybadger.terminal/` 치환
- **동작 원리**: 스크립트 shebang, 설정 파일 경로 등을 우리 패키지명으로 변경. ELF 바이너리는 건너뜀

#### 6.1.7 dpkg 바이너리 패치 (Phase 6)

- **대상**: `$PREFIX/bin/dpkg.real`, `$PREFIX/bin/dpkg-deb`, `$PREFIX/bin/dpkg-query` 등 dpkg family 바이너리
- **방식**: `TermuxInstaller.binaryPatchFile()`로 ELF 바이너리의 하드코딩된 경로를 짧은 symlink 경로로 치환
- **동작 원리**: 바이너리의 `/data/data/com.termux/files/usr/etc/dpkg` (40자) → `/data/data/com.honeybadger.terminal/d` (38자+null패딩) 등으로 치환. 짧은 symlink가 실제 디렉토리를 가리킴
- **symlink 목록** (`/data/data/com.honeybadger.terminal/` 아래):
  - `d` → `files/usr/etc/dpkg`
  - `a` → `files/usr/var/lib/dpkg`
  - `p` → `files/usr`
  - `h` → `files/home`
  - `t` → `files/usr/tmp`
  - `l` → `files/usr/lib`

#### 6.1.8 dpkg wrapper (Phase 6)

- **대상**: `$PREFIX/bin/dpkg` (원본은 `dpkg.real`로 이름 변경)
- **방식**: wrapper 스크립트로 PATH와 LD_LIBRARY_PATH 설정 후 dpkg.real 호출
- **동작 원리**: dpkg.real이 sh, tar 등 하위 도구를 PATH에서 찾을 수 있도록 PATH 설정

#### 6.1.9 tar wrapper (Phase 6)

- **대상**: `$PREFIX/bin/tar` (원본은 `tar.real`로 이름 변경)
- **방식**: wrapper 스크립트로 `--transform="s,com.termux,com.honeybadger.terminal,g"` 플래그 추가
- **동작 원리**: dpkg가 deb 패키지에서 파일 추출 시 tar를 호출. wrapper가 `com.termux` 경로를 우리 패키지명으로 변환하여 올바른 위치에 파일 설치

#### 6.1.10 apt.conf (Phase 6)

- **대상**: `$PREFIX/etc/apt/apt.conf`
- **방식**: Bootstrap 설치 시 TermuxInstaller가 생성
- **동작 원리**: apt의 모든 디렉토리 경로를 우리 패키지명 기준으로 명시적 설정. `Dpkg::Options`으로 `--force-configure-any --force-bad-path` 전달

#### 6.1.11 pacman 바이너리 패치 (Phase 6, first-run.sh)

- **대상**: `$PREFIX/bin/pacman`, `$PREFIX/lib/libalpm.so`, `$PREFIX/lib/libcurl.so` 등
- **방식**: first-run.sh에서 `bpatch` 도구로 바이너리 패치
- **동작 원리**: 패키지 관리 바이너리의 하드코딩된 hooks/db/cache/gnupg/tls 경로를 짧은 symlink로 치환. dpkg와 동일한 symlink 접근 사용
- **symlink 추가** (`/data/data/com.honeybadger.terminal/` 아래):
  - `H` → `files/usr/share/libalpm/hooks`
  - `E` → `files/usr/etc/pacman.d/hooks`
  - `D` → `files/usr/var/lib/pacman`
  - `C` → `files/usr/var/cache/pacman/pkg`
  - `G` → `files/usr/etc/pacman.d/gnupg`
  - `T` → `files/usr/etc/tls`

#### 6.1.12 bpatch 바이너리 도구 (Phase 6)

- **대상**: `$PREFIX/bin/bpatch` — APK assets에서 복사
- **방식**: NDK로 크로스 컴파일된 ARM64 PIE 바이너리
- **동작 원리**: ELF 바이너리의 문자열을 null-padded 교체. first-run.sh에서 pacman/libalpm 패치에 사용

### 6.2 오버레이 예외 목록

Phase 6에서 패키지 소유 파일을 수정한 경우:

#### 6.2.1 dpkg 바이너리 (dpkg.real)

- **대상**: `$PREFIX/bin/dpkg` → `dpkg.real`로 이름 변경 + wrapper 생성 + 바이너리 패치
- **이유**: dpkg는 컴파일 시 hardcoded open() 경로를 가지고 있어 오버레이로 해결 불가. 바이너리 패치와 wrapper 조합으로 대응
- **pkg upgrade 영향**: `pkg upgrade dpkg` 실행 시 원본 dpkg가 복원되어 wrapper가 깨짐. 이 경우 first-run.sh를 재실행하여 복구 가능

#### 6.2.2 tar 바이너리 (tar.real)

- **대상**: `$PREFIX/bin/tar` → `tar.real`로 이름 변경 + wrapper 생성
- **이유**: dpkg가 패키지 추출 시 tar를 호출하며, deb 패키지 내 파일 경로에 com.termux가 포함되어 있어 변환 필요
- **pkg upgrade 영향**: `pkg upgrade tar` 실행 시 원본 tar가 복원되어 wrapper가 깨짐. first-run.sh 재실행으로 복구

#### 6.2.3 Bootstrap 스크립트/설정 파일 텍스트 패치

- **대상**: bin/, etc/, libexec/, var/lib/dpkg/ 내 텍스트 파일
- **이유**: Termux bootstrap ZIP의 모든 파일이 com.termux 경로를 포함. libtermux-exec가 execve()만 intercept하므로 텍스트 파일은 직접 패치 필요
- **pkg upgrade 영향**: 패키지 업그레이드 시 원본 파일이 복원될 수 있음. libtermux-exec가 execve() 경로 변환을 처리하므로 대부분 정상 동작. shebang이 깨지면 해당 스크립트 재패치 필요

### 6.3 환경변수 전체 목록

| 변수 | 값 | 용도 | 설정 위치 |
|------|-----|------|----------|
| `PATH` | `$PREFIX/local/bin:$PREFIX/bin` | termux-api CLI 디스패처 + 오버레이 bin 우선순위 | TermuxShellEnvironment.java, .bashrc |
| `LD_LIBRARY_PATH` | `$PREFIX/lib` | DT_RUNPATH가 com.termux를 가리키는 바이너리의 라이브러리 탐색 | TermuxShellEnvironment.java |
| `APT_CONFIG` | `$PREFIX/etc/apt/apt.conf` | apt가 올바른 설정 파일을 읽도록 | TermuxShellEnvironment.java |
| `CURL_CA_BUNDLE` | `$PREFIX/etc/tls/cert.pem` | libcurl의 TLS 인증서 경로 | first-run.sh |
| `SSL_CERT_FILE` | `$PREFIX/etc/tls/cert.pem` | TLS 인증서 경로 (OpenSSL) | first-run.sh |

## 7. 유지보수 주의사항

### 7.1 설정 파일 보호 (Phase 6 확인 결과)

Termux의 bootstrap 추출 시 패치한 apt.conf, sources.list 등이 `pkg upgrade`로 덮어씌워지는 문제:

- **apt.conf**: TermuxInstaller가 커스텀 apt.conf를 작성. dpkg는 conffile을 관리하므로 `pkg upgrade apt` 시 사용자에게 교체 여부를 묻거나 `.dpkg-old`로 백업함. 그러나 Termux의 dpkg는 non-interactive 모드에서 기본적으로 원본을 유지 (`--force-confold` 동작)
- **sources.list**: bootstrap에서 HTTPS→HTTP 다운그레이드 패치 적용. `pkg upgrade` 시 원본으로 복원될 수 있으나, HTTP 미러는 별도의 `sources.list.d/` 파일로 관리 가능
- **pacman.conf**: first-run.sh에서 com.termux→우리 패키지명으로 패치. `pkg upgrade pacman` 시 원본 복원 가능. 이 경우 HookDir, SigLevel 설정이 초기화되므로 pacman 재사용 시 재패치 필요

### 7.2 pkg upgrade 시 체크리스트

- [ ] dpkg wrapper (`$PREFIX/bin/dpkg`)가 wrapper 스크립트인지 확인. 원본으로 복원됐으면 재패치 필요
- [ ] tar wrapper (`$PREFIX/bin/tar`)가 wrapper 스크립트인지 확인
- [ ] apt.conf가 우리 패키지명 경로를 사용하는지 확인
- [ ] pacman.conf가 우리 패키지명 경로를 사용하는지 확인
- [ ] 새 스크립트에 com.termux shebang이 있으면 libtermux-exec가 처리하므로 별도 조치 불필요

### Phase 7: About 화면 + README

#### 1.14 `app/src/main/java/com/honeybadger/terminal/app/activities/AboutActivity.java` (신규)
- Termux 프로젝트 크레딧 및 라이선스(GPLv3) 표시
- "Built on Termux (https://github.com/termux/termux-app)" 문구 포함
- Honey Badger 버전 정보 (`BuildConfig.VERSION_NAME`)
- Termux GitHub 링크 버튼, 프로젝트 GitHub 링크 버튼
- 뒤로가기(Up navigation) 지원

#### 1.15 `app/src/main/res/layout/activity_about.xml` (신규)
- ScrollView 기반 About 화면 레이아웃
- 섹션: 앱 이름, 버전, Credits, License, Project
- 다크 테마 (검은 배경, 흰색 텍스트)

#### 1.16 `app/src/main/res/values/strings.xml` (Phase 7 추가분)
- `action_about` — "About" 메뉴 항목 텍스트
- `about_version_format` — 버전 표시 포맷
- `about_credits_title`, `about_termux_credit` — Termux 크레딧 섹션
- `about_btn_termux_github` — Termux GitHub 버튼 텍스트
- `about_license_title`, `about_license_text` — GPLv3 라이선스 섹션
- `about_project_title`, `about_project_text` — 프로젝트 설명
- `about_btn_project_github` — 프로젝트 GitHub 버튼 텍스트

#### 1.17 `app/src/main/AndroidManifest.xml` (Phase 7 추가분)
- `AboutActivity` 등록 (unexported, TermuxActivity의 하위 Activity)

#### 1.18 `app/src/main/java/com/honeybadger/terminal/app/TermuxActivity.java` (Phase 7 수정분)
- `CONTEXT_MENU_ABOUT_ID = 12` 상수 추가
- Context 메뉴에 "About" 항목 추가 (`CONTEXT_MENU_ABOUT_ID`)
- `onContextItemSelected()`에 About 메뉴 핸들러 추가 (AboutActivity 실행)
- `AboutActivity` import 추가

### 7.3 hook 작업 (특정 변경 시 필수 후속 작업)

| 트리거 | 후속 작업 |
|--------|----------|
| Bootstrap ZIP 교체 (새 Termux 릴리즈) | (1) 오버레이 목록(6장) 재확인: 새 CLI/스크립트에 com.termux 경로가 추가되었는지 확인 (2) dpkg/tar wrapper 호환성 확인 (3) bpatch 치환 오프셋이 변경되었는지 확인 |
| 새 termux-api CLI 명령어 추가 | (1) `$PREFIX/local/bin/` 아래 symlink → termux-dispatch 추가 (2) 또는 first-run.sh 재실행 |
| 업스트림 머지 (termux-app) | (1) 3장 치환 맵 기준으로 새 `com.termux` 참조 치환 여부 판단 (2) AndroidManifest 변경 시 API/Boot 추가 항목과 충돌 확인 (3) 라이브러리 모듈 API 변경 시 호환성 확인 (4) `TermuxConstants` 변경 시 우리 수정분과 충돌 확인 |
| 업스트림 머지 (termux-api) | (1) 새 API 서비스/Activity 확인 → AndroidManifest 추가 (2) 새 퍼미션 확인 → AndroidManifest 추가 (3) `TermuxApiReceiver` 변경 시 우리 디스패처와 호환 확인 |
| 업스트림 머지 (termux-boot) | (1) `BootReceiver`/`BootJobService` 변경 확인 (2) 새 하드코딩 경로 확인 → TermuxConstants로 교체 |
| `build.gradle` applicationId 변경 | (1) AndroidManifest, 환경변수 설정, first-run.sh, 오버레이 설정 파일, dispatcher 스크립트 모두 함께 변경 (2) SPEC.md 3장 치환 맵 업데이트 |
| `TermuxConstants.TERMUX_PACKAGE_NAME` 변경 | (1) build.gradle applicationId와 일치 확인 (2) first-run.sh 내 하드코딩 경로 확인 (3) 바이너리 패치 symlink 경로 확인 |
| pkg upgrade dpkg | (1) dpkg wrapper 복구 필요 (first-run.sh 재실행 또는 수동) (2) dpkg.real 바이너리 재패치 필요 |
| pkg upgrade tar | (1) tar wrapper 복구 필요 |
| pkg upgrade pacman | (1) pacman.conf 재패치 필요 (HookDir, SigLevel 설정) (2) pacman/libalpm 바이너리 재패치 필요 |
| 앱 버전 업그레이드 | (1) `build.gradle`의 versionCode/versionName 변경 (2) About 화면에 자동 반영 (BuildConfig.VERSION_NAME 사용) |
| 새 오버레이 추가 | (1) SPEC.md 6장 오버레이 목록 업데이트 (2) 환경변수 사용 시 6.3 환경변수 목록 업데이트 |

### 7.4 pkg upgrade 영향 분석

| 패키지 | 영향받는 오버레이 | 증상 | 복구 방법 |
|--------|-----------------|------|----------|
| `dpkg` | dpkg wrapper (6.1.8), dpkg 바이너리 패치 (6.1.7) | `pkg install` 실패 (dpkg가 com.termux 경로를 찾음) | first-run.sh 재실행 |
| `tar` | tar wrapper (6.1.9) | `pkg install` 시 파일이 com.termux 경로에 추출됨 | first-run.sh 재실행 |
| `pacman` | pacman 바이너리 패치 (6.1.11) | `pacman -S` 실패 (hooks/db 경로 오류) | first-run.sh 재실행 (bpatch 단계) |
| `apt` | apt.conf (6.1.10) | apt가 기본 경로 사용 시도 → APT_CONFIG으로 우회 가능 | apt.conf 재생성 (TermuxInstaller가 처리) |
| `termux-api` | termux-api CLI 디스패처 (6.1.1) | 영향 없음 (dispatcher가 원본을 동적으로 읽으므로 최신 원본 반영) | 새 명령어 추가 시 symlink만 추가 |
| `bash` | .bashrc PATH 설정 (6.1.2) | 영향 없음 (.bashrc는 사용자 파일, 패키지가 수정하지 않음) | 없음 |
| 기타 패키지 | 없음 | libtermux-exec가 execve() 경로 변환 처리 | 없음 |

**검증 방법 (pkg upgrade 후):**
1. `head -1 $PREFIX/bin/dpkg` — `#!/data/data/com.honeybadger.terminal/...` wrapper인지 확인
2. `head -1 $PREFIX/bin/tar` — wrapper 스크립트인지 확인
3. `cat $PREFIX/etc/apt/apt.conf | grep honeybadger` — 올바른 경로인지 확인
4. `pacman -Qi glibc-runner` — pacman이 정상 동작하는지 확인
5. `termux-battery-status` — API 디스패처가 정상 동작하는지 확인

### 7.5 업스트림 머지 시 체크리스트

- [ ] 새로운 `com.termux` 참조 확인 (3장 치환 맵 기준)
- [ ] 라이브러리 모듈 (`terminal-emulator/`, `terminal-view/`, `termux-shared/`) 변경 시 호환성 확인
- [ ] AndroidManifest 변경 시 우리가 추가한 항목 (API 서비스, Boot 리시버, About Activity)과 충돌 확인
- [ ] 새로운 바이너리/스크립트가 `com.termux` 경로를 사용하는지 확인 → 오버레이 필요 여부 판단
- [ ] `TermuxConstants.java` 변경 시 우리 수정분 (`TERMUX_APP_NAME`, `TERMUX_PACKAGE_NAME`)과 충돌 확인
- [ ] `build.gradle` 변경 시 우리 수정분 (`applicationId`, `namespace`, `ndk abiFilters`)과 충돌 확인
- [ ] 새 Activity/Service/Receiver 추가 시 패키지명 확인
- [ ] `TermuxInstaller.java` 변경 시 우리 추가분 (`fixBootstrapPaths`, `createDpkgWrapper`, `copyFirstRunScript`, `installFirstRunProfile`)과 충돌 확인

### 7.6 수정 시 연쇄 영향 맵

| 수정 대상 | 연쇄 확인 대상 |
|-----------|---------------|
| `build.gradle` applicationId | AndroidManifest, TermuxConstants, first-run.sh, 오버레이 설정, 바이너리 패치 symlink |
| `TermuxConstants.java` 상수 | 파생 상수 (경로, 환경변수), first-run.sh, 디스패처 스크립트 |
| `AndroidManifest.xml` 퍼미션 | API 기능 동작 확인 |
| `TermuxInstaller.java` | bootstrap 추출 로직, first-run 트리거, bpatch 복사 |
| `TermuxShellEnvironment.java` | PATH, LD_LIBRARY_PATH, APT_CONFIG 설정 |
| `first-run.sh` | pacman 설치, glibc-runner 설치, 디스패처 생성, 마커 파일 |
| `TermuxActivity.java` 메뉴 | 메뉴 상수 ID 충돌 확인 |
| About 화면 문자열 | strings.xml 업데이트 |
