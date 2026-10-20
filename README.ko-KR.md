[English](README.md) | [简体中文](README.zh-CN.md) | [日本語](README.ja-JP.md) | **한국어**

---

# <p align="center">B视机 · BTelly</p>
<p align="center"><strong>서드파티 Bilibili Android TV / 차량용 클라이언트 | 듀얼 플레이어 코어 · 탄막 댓글 · 공습 어시스턴트 · 청소년 모드 | Android 6.0 → 15 전 세대 지원 | Kotlin · MVVM · Koin · 광고 없음</strong></p>
<p align="center">
    <a href="https://github.com/qaqmin/btelly/releases/latest">
        <img alt="Latest Release" src="https://img.shields.io/github/v/release/qaqmin/btelly?style=flat&color=blue">
    </a>
    <a href="https://github.com/qaqmin/btelly/issues">
        <img alt="GitHub Issues" src="https://img.shields.io/github/issues/qaqmin/btelly?style=flat&color=red&label=%F0%9F%90%9B%20Bug%20%2F%20Feature">
    </a>
    <img alt="API" src="https://img.shields.io/badge/API-23%2B-brightgreen">
    <img alt="Android" src="https://img.shields.io/badge/Android-15%20ready-blue">
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-purple">
</p>

---

> ### 👤 메인테이너 근황 / Maintainer's Corner
> 본 프로젝트는 여가 시간에 유지보수하며, 리모컨 실기기 검수와 Mac 원격 빌드 머신으로 지속적으로 산출합니다.
> 보고된 버그는 하나하나 조사하고, 하나하나 수정합니다.
>
> - 📺 **테스트 기기**: Android TV 박스(리모컨 조작 검수 중심), 차량 표시 모드 대응 병행
> - 🚗 **차량 모드**: 설정 내 원클릭 전환, 텍스트와 터치 간격을 통일해 약 25% 확대, TV 모드는 변화 없음
>
> 📌 **계정 이전 공지(2026-10-10)**: 본 프로젝트의 GitHub 계정은 두 차례 제재되었습니다 — **min09577**와 **qaqmin09577**가 잇달아 불가항력적인 사유로 차단되어(리포지토리 모두 404) 두 개의 희생된 계정이 되었습니다.
> 프로젝트는 새 계정 **[qaqmin](https://github.com/qaqmin/btelly)**(으)로 전체 이전되었습니다.
> Codeberg 미러(min09577/btelly)는 계속 동기화 갱신하며 예비 채널로 남습니다.
> 앱 내 '업데이트 확인'은 **GitHub / Codeberg 듀얼 채널 감지**를 지원하며, 두 곳 중 더 최신 버전을 자동으로 선택합니다.
> 서명 키는 그대로이며, 덮어쓰기 업데이트도 매끄럽게 이어집니다.

---

## ☕ 후원 (Sponsor)

이 프로젝트는 여가 시간에 "애정으로" 만든 작품입니다. 여러분에게 도움이 되어 정말 기쁩니다. 다만 프로젝트가 발전함에 따라 테스트에 소모되는 API 토큰이 제 예상을 훨씬 넘어섰고, 오랜 디버깅까지 더해져 꽤 많은 휴식 시간을 투입했습니다(웃음).

혹시 이 프로젝트가 문제를 해결해 주었거나 삽질하는 시간을 줄여 주었다면, 후원을 환영합니다. 여러분의 후원은 모두 높은 API 청구서를 "회복"하는 데 쓰이며, 이는 제가 부담 없이 지속적으로 업데이트할 수 있는 가장 큰 원동력입니다.

물론 완전히 자발적이니 절대 부담 가지지 마세요. 쓰기 편하다고 느끼신다면 **Star** 하나만 눌러 주셔도 큰 힘이 됩니다!

> **Tip:** 높은 송금 수수료 부담을 피하기 위해 **BNB Smart Chain (BEP-20)** 네트워크를 이용해 주세요. 응원해 주셔서 감사합니다!

<p align="center">
    <img src="docs/images/sponsor_qr.png" alt="Sponsor QR (Binance · BEP-20)" width="360">
</p>

<p align="center"><i>바이낸스 수금 QR 코드 · Binance Wallet QR(USDT · BNB Smart Chain / BEP-20)</i></p>

---

## ✨ B视机를 선택하는 이유 / Why BTelly

| 특성 | 설명 |
|------|------|
| 📺 **TV/차량 듀얼 폼팩터** | 리모컨 조작을 위해 설계; 차량 모드에서 텍스트와 간격을 통일해 약 25% 확대, 일곱 가지 스킨 전역 적응 |
| 🎬 **전체 사이트 영상** | 애니·영화·드라마·UGC; 홈 추천/인기/랭킹/애니/영화 5대 탭 |
| 🔀 **듀얼 플레이어 코어** | ExoPlayer / IjkPlayer 자유롭게 전환, 다음 재생부터 적용; IjkPlayer는 패키지에 포함 배포(전 ABI), 추가 다운로드 불필요 |
| 📡 **라이브 & CCTV** | 라이브 탄막 실시간 표시; CCTV 1~17 채널을 리모컨 상하로 바로 전환 |
| 💬 **탄막 엔진** | Protobuf 프로토콜 파싱, 인물 영역 지능적 가림 방지 |
| 🗨️ **플레이어 내 댓글** | 재생 중 → 기타 작업 → 댓글, 댓글 영역이 플레이어 위에 바로 표시되어 BACK으로 플레이어로 복귀, 재생 중인 화면을 놓치지 않음 |
| ✈️ **공습 어시스턴트** | 광고/오프닝/엔딩 구간을 자동으로 스킵, 진행 바에 구간 마커 표시 |
| 🎮 **인터랙티브 영상 / 틱톡 모드** | 인터랙티브 분기 선택; 틱톡 모드에서 상하 스와이프로 추천 피드 전환 |
| 👶 **청소년 모드** | 설정 내 즉시 사용 가능한 자녀 보호 콘텐츠 필터링, 비밀 코드 불필요 |
| 🔍 **검색/즐겨찾기/기록** | 검색 후 바로 재생, 즐겨찾기 카드 스택, 시청 진행률 자동 이어보기 |
| ✨ **'Min화' 모션** | 포커스 스프링 / 리스트 플로팅 / Hero 전환, 마스터 스위치 하나로 애니메이션 제로 즉시 표시 |
| 📲 **QR 로그인** | TV에서 QR 스캔으로 빠르게 로그인, 인증 정보는 로컬에만 저장 |
| 🔒 **프라이버시 우선** | 트래킹 코드 없음, Bilibili API와만 통신하며 사용자 데이터를 수집·업로드하지 않음 |
| 📱 **초장기 세대 호환** | Android 6.0 (API 23)부터 Android 15 (API 35)까지 아우름 |

---

## 📖 소개

B视机(BTelly)는 **비공식** Bilibili Android TV / 차량용 클라이언트로, Kotlin으로 작성되었으며
MVVM + Koin 아키텍처와 청록빛 계열의 다크 자체 디자인 언어를 사용합니다. TV 리모컨과 차량 조작 환경을 위해 설계되어
큰 포커스, 넓은 간격, 리모컨만으로 모든 조작이 가능하며, 홈 탐색·영상 재생·탄막 인터랙션부터 정주행과 애니 추적까지의 완전한 흐름을 커버합니다.

> **⚠️ 고지:** 본 소프트웨어와 소스 코드는 학습 및 교류 목적으로만 사용 가능하며, 상업적 이용은 엄격히 금지됩니다. Bilibili와 아무런 관련이 없습니다.

### 🛠️ 기술 스택 / Tech Stack

- **Kotlin 2.1.0** · MVVM + Koin 의존성 주입
- **Coroutines + Flow + LiveData** 비동기
- **Retrofit + OkHttp + Gson** 네트워크 계층
- **Media3 (ExoPlayer) / IjkPlayer** 듀얼 플레이어 코어(CCTV는 WebView 경유)
- **탄막 엔진**(Protobuf 프로토콜 파싱)
- **DataStore Preferences** 데이터 저장
- **AndroidX** UI 컴포넌트
- **minSdk 23 (Android 6.0)** / **targetSdk 35 (Android 15)**

## 📷 앱 스크린샷

홈(추천 / 인기 / 랭킹 / 애니 / 영화)
<img width="960" height="540" alt="home" src="screenshots/home.png" />

재생 & 탄막
<img width="960" height="540" alt="player" src="screenshots/player.png" />

영상 상세
<img width="960" height="540" alt="detail" src="screenshots/detail.png" />

기기 정보 & 업데이트 확인
<img width="960" height="540" alt="about" src="screenshots/about.png" />

## 🆕 v3.1.0 — 라이브 '내 팔로우' 전면 개편

> ### 🏆 팔로우한 스트리머 전량 표시 · 방송 상태 한눈에
> 라이브 페이지의 '내 팔로우' 섹션 제목에서 OK를 누르면 팔로우 목록 페이지가 열립니다:
> **팔로우한 모든 스트리머를 페이지네이션으로 전량 표시**(홈 모듈의 소량 미리보기 제한 없음).
> 그룹 표시 — '방송 중'은 OK로 바로 라이브 방에, '방송 종료'는 OK로 해당 유저의 개인 공간으로 이동.
> 상단 요약 바에 '방송 중 N · 방송 종료 M'이 실시간 표시. 홈 팔로우 모듈의 카드 클릭도 수정했습니다.
> 카드 인기 표시도 수정 — 0 표시 대신 서버에서 사전 포맷된 시청자 수 텍스트를 보여주고, 방송 종료 카드는 배지를 숨깁니다.

## 🆕 v3.0.9 — 랭킹 탭 + 플레이어 내 댓글

> ### 🏆 랭킹 탭 + 플레이어 내 댓글
> 홈에 '랭킹' 탭 추가(추천/인기/랭킹/애니/영화 5단).
> 플레이어 '기타 작업' 패널에 댓글 진입점 추가, 댓글 영역이 플레이어 위에 바로 표시되고 BACK을 눌러도 재생이 중단되지 않음(겸사겸사 댓글 영역 컨테이너 누락으로 발생하던 크래시도 수정).
> 업데이트 감지가 듀얼 채널로 업그레이드.

## 🆕 v3.0.8 — Hero 전환: 표지가 상세 페이지로 날아간다

> ### 🖼️ 카드를 누르면 표지가 스스로 날아간다
> 영상 카드를 눌러 상세 페이지에 들어가면 표지가 카드 원래 위치에서 헤더 이미지 위치로 날아갑니다(300ms 감속 보간, 라운드는 비행에 따라 전환).
> 뒤로 가기를 누르면 다시 카드로 날아 돌아오며, 상세 페이지 내 관련 영상을 눌러도 같은 방식으로 이어집니다. 홈/피드/검색/즐겨찾기/기록의 모든 카드 진입점을 커버하며,
> 상세 데이터가 느리거나 인터페이스 실패 시 2.5s 대기 후 자동으로 철회되어 페이지를 막지 않습니다. 'Min화' 마스터 스위치로 제어됩니다.

## 🆕 v3.0.7 / v3.0.6 — 'Min화' 모션 전면 적용

> ### 🎞️ 리스트 플로팅 + 다이얼로그 스프링
> 홈/라이브 등 리스트가 첫 진입 시 stagger 플로팅(하위 항목 12%씩 점진적 지연, 14% 상승 + 페이드 인).
> 전체 16곳의 다이얼로그가 스프링으로 표시(0.92배 확대 반동 + 페이드 인), 홈/라이브 페이지 전환 시 페이드 인 —
> 모두 'Min화' 마스터 스위치로 제어: 끄면 애니메이션 제로 즉시 표시.

## 🆕 v3.0.5 — 'Min화' 애니메이션 마스터 스위치

> ### 🎛️ 스위치 하나, 두 가지 성격
> 설정 → 화면에 'Min화' 마스터 스위치 추가(기본 켜짐): 끄면 사이트 전체 모션이 애니메이션 제로 즉시 표시로 전환되어
> 시스템의 "동적 효과 줄이기"와 동등하며, 저성능 기기와 모션에 민감한 사용자에게 친화적입니다.

## 🆕 v3.0.4 — 포커스 스프링: 리모컨에도 "Apple 감성"

> ### 🍏 모션 기반 마련
> 영상 카드, 탭 chip, 내비게이션 바, 설정 행에 포커스하면 살짝 확대 반동하며 레이어가 상승하고,
> 포커스를 잃으면 감속하며 하강 — UiMotion / UiFocusMotion 중앙 토큰이 통일 구동.

## 🆕 v2.1.0 — B视机로 개명 · 듀얼 플레이어 코어 시대

> ### 🔀 ExoPlayer / IjkPlayer 자유 전환
> '플레이어 코어' 설정 추가: IjkPlayer가 영상과 라이브 재생을 지원(DASH 이중 트랙 로컬 MPD 합류, 라이브는 FLV/HLS 직접 연결), 코어 .so는 전 ABI 패키지 배포.
> Media3 Player 파사드로 구현되어 탄막/진행/제어 기존 체인은 수정 제로. 청소년 모드는 설정 내 직접 스위치로 변경되어 즉시 사용 가능.
>
> ### 🏷️ 브랜드 통일
> 화면, 아이콘, 코드 패키지명과 저장소 명명이 B视机(BTelly)(으)로 통일되고, 설치 식별자는 com.btelly로 전환.

## 💡 사용 팁 / Pro Tips

| 상황 | 권장 사항 |
|------|------|
| 🕹️ **리모컨 전용 조작** | OK로 재생/확인, BACK으로 단계별 뒤로; 영상 카드 길게 누르면 빠른 메뉴 호출 |
| 💬 **댓글 보기** | 재생 중 → 기타 작업 → 댓글; BACK을 누르면 플레이어로 복귀, 재생 중단 없음 |
| ✈️ **공습 어시스턴트** | 플레이어 진행 바에 스킵 가능 구간이 자동 표시, 설정 스위치를 따름 |
| 👶 **청소년 모드** | 설정 → 청소년 모드, 즉시 사용, 부적절 콘텐츠 차단 |
| 🔄 **업데이트 확인** | 설정 → 기기 정보 → 업데이트 확인(GitHub / Codeberg 듀얼 채널, 최신 버전 선택) |
| 🐛 **문제 해결 보조** | 정보 페이지에서 로그 보기 → 복사, issue 제출 시 로그를 첨부하면 한결 수월 |

## 💬 피드백과 기여 / Feedback and Contribution

🐛 버그 발견? 💡 좋은 아이디어? GitHub Issues로 제출해 주세요:

<p align="center">
    <a href="https://github.com/qaqmin/btelly/issues">
        <img alt="GitHub Issues" src="https://img.shields.io/github/issues/qaqmin/btelly?style=flat&color=red&label=%F0%9F%90%9B%20Bug%20%2F%20Feature">
    </a>
</p>

> 제출 시 **앱 로그**(정보 페이지 → 로그 보기 → 복사)를 함께 첨부해 주세요. 자세할수록 수정이 빠릅니다.

## 👨‍💻 원작자 / Original Author

본 프로젝트는 **[QAQMin (min09577)](https://github.com/min09577)** 이 개발하고 유지보수하며,
2026-09에 B视机(BTelly)(으)로 명칭을 변경했습니다.

> 🙏 **대부분의 화면과 조작 로직은 [BBLL](https://github.com/xiaye13579/BBLL)** 을 참고했으며,
> 일부 핵심 기능은 PiliPlus / BiliPai / Blbl을, Bilibili API 동작은
> [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)을 참고했습니다.
> 위 오픈소스 프로젝트들에 진심으로 감사드립니다.

## 🔗 링크 모음

+ [SocialSisterYi/bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) - Bilibili API 수집 정리
+ [xiaye13579/BBLL](https://github.com/xiaye13579/BBLL) - 화면과 조작 로직의 주요 참고 구현
+ [bggRGjQaUbCoE/PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) / [jay3-yy/BiliPai](https://github.com/jay3-yy/BiliPai) / [cat3399/blbl](https://github.com/cat3399/blbl) - 일부 핵심 기능 참고

---

## 📋 버전 변경 이력 / Version History

| 버전 / Version | 날짜 / Date | 설명 / Description |
|---|---|---|
| v3.1.0 | 2026-10-11 | 🏆 **라이브 '내 팔로우' 개편** — 팔로우 스트리머 전량 페이지네이션 목록 · 방송 중/종료 그룹 · 방송 종료는 개인 공간 이동 · 팔로우 모듈 클릭과 카드 인기 표시 수정 |
| v3.0.9 | 2026-10-05 | 🗨️ **플레이어 내 댓글 + 랭킹 탭** · 듀얼 채널 업데이트 감지 · 댓글 영역 컨테이너 크래시 수정 |
| v3.0.8 | 2026-09-26 | 🖼️ **Hero 전환** — 표지가 상세 페이지로 날아감 · 전체 카드 진입점 커버 · 뒤로 가면 원위치로 복귀 |
| v3.0.7 | 2026-09-26 | 🎞️ **리스트 stagger 플로팅** — 홈/라이브 첫 진입 시 지연 상승 페이드 인 |
| v3.0.6 | 2026-09-26 | 🪀 **다이얼로그 스프링 표시** — 16곳 적용 · 홈/라이브 페이지 전환 페이드 인 |
| v3.0.5 | 2026-09-25 | 🎛️ **'Min화' 모션 마스터 스위치** — 원클릭으로 애니메이션 제로 즉시 표시 |
| v3.0.4 | 2026-09-25 | 🍏 **포커스 스프링** — 카드/탭/내비게이션 바 포커스 반동 · UiMotion 토큰 기반 |
| v3.0.3 | 2026-09-25 | 🚗 **차량 간격 적용 마무리** — 다이얼로그 + 상세 페이지 헤더 적용 · TV 모드는 변화 없음 |
| v3.0.2 | 2026-09-25 | 📺 **라이브 방 토큰화 마무리** — 방 카드/리스트 배경/빈 상태 문구 테마 통일 |
| v3.0.1 | 2026-09-25 | 🩺 **라이브 음영 동기 집중 개선** — HEVC 경로 14분 실기기 측정 무드리프트 |
| v3.0.0 | 2026-09-25 | 🎨 **아이콘 체계 전체 재작업 56개** · 차량 모드 전체화면 적용 · 즐겨찾기/사용자 공간 개편 · 구 색상 토큰 정리 |
| v2.1.0 | 2026-09-25 | 🏷️ **B视机 (BTelly) 개명** — 패키지명 com.btelly 전환 · **듀얼 플레이어 코어** · 청소년 모드 스위치 · 시작 페이지/설정 개편 |
| v2.0.4 | 2026-09-18 | 🐛 **탄막 스크롤 속도 수정** · 개인 공간 페이지 재제작 · 사용자 정보 팝업 공식 사이트 스타일 |
| v2.0.3 | 2026-09-10 | 🔤 **UI 글자 크기 5단계** · **영상 카드 크기 4단계** · 홈 로딩 스피너 수정 |
| v2.0.2 | 2026-09-10 | 🐛 검색 결과 뒤섞임/포커스 상실 수정 · 탄막 글자 크기 상한 100 · 초광폭 화면 한 줄 8카드 |
| v2.0.1 | 2026-09-10 | 🎫 **미리보기 고지 명확화** · 미리보기 종료 후 자동 재생 중단 · 일부 로딩 수정 |
| v2.0.0 | 2026-09-09 | 📦 **패키지명 변경 버전**(기존 버전 삭제 후 재설치 필요) · 뒤로 가기 포커스 이상 수정 |
| [▶ 전체 로그](CHANGELOG.md) | | 더 이른 버전(v1.2.0 ~ v1.6.x, 2025-04 ~ 2026-09)은 CHANGELOG.md를 참조하세요 |

---

## 🛠️ 빌드 설명 / Build Instructions

### 환경 요구 사항 / Prerequisites

- **JDK 17+**
- **Android SDK**(compileSdk 35)
- Android Studio(권장)

### 빌드 명령 / Build Commands

```bash
# 디버그 빌드 / Debug Build
./gradlew assembleDebug

# 릴리스 빌드 / Release Build
./gradlew assembleRelease
```

빌드 산출물은 `app/build/outputs/apk/` 디렉터리에 위치하며, 산출물 이름 형식은 `BTelly-v<버전>-<buildType>.apk` 입니다.

### 서명 설명 / Signing

릴리스 서명은 **Mac 원격 빌드 머신**이 통일 관리합니다(프로젝트당 키 하나, 최초 1회만 생성, 재생성 없음).
keystore 3종 세트는 **본 저장소에 없습니다**. 로컬에서 직접 릴리스를 빌드하려면 서명 구성을 직접 준비하세요.

```properties
# keystore.properties(버전 관리 시스템에 커밋 금지 / Do NOT commit)
storeFile=your_keystore_file.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

> ⚠️ **주의 / Note:** `keystore.properties` 와 모든 `.jks` 파일을 버전 관리 시스템에 커밋하지 마세요.

---

## ⚠️ 고지 사항 / Disclaimer

<details>
<summary>🇨🇳 中文</summary>

1. 本软件为**非官方** Bilibili 客户端，与哔哩哔哩（上海宽娱数码科技有限公司）无任何关联。
2. 本软件及源码**仅供学习交流使用，严禁用于商业用途**。
3. 本项目不存储任何视频、弹幕、图片、文字内容，仅作播放呈现；相关内容版权归 Bilibili 及原创作者所有。
4. 本项目不收集、不上传任何用户数据；登录凭据仅保存在本地，不上传至任何第三方服务器。
5. 本项目「按现状」提供，不保证功能可用、稳定或持续更新；因使用本项目产生的任何直接或间接损失，作者不承担责任。
6. 不得在 B 站、官方账号区域（含微博评论区）及微信公众号宣传本项目；不得利用本项目牟利。
7. 若本项目侵犯了您的合法权益，请联系作者，确认后将在第一时间处理或下架。

</details>

<details>
<summary>🇯🇵 日本語</summary>

1. 本ソフトウェアは**非公式**の Bilibili クライアントであり、哔哩哔哩社とは一切の関係がありません。
2. 本ソフトウェアおよびソースコードは**学習・交流のみを目的としており、商業利用は厳禁**です。
3. 本ソフトウェアは動画・弾幕・画像・テキストを一切保存せず、再生呈现のみを行います。
4. 本ソフトウェアはユーザーデータを収集・アップロードしません。ログイン情報はローカルのみに保存されます。
5. 本ソフトウェアは「現状有姿」で提供され、機能の完全性・安定性・継続的な更新を保証するものではありません。

</details>

<details>
<summary>🇰🇷 한국어</summary>

1. 본 소프트웨어는 **비공식** Bilibili 클라이언트이며, 哔哩哔哩와는 아무런 관련이 없습니다.
2. 본 소프트웨어 및 소스코드는 **학습 및 교류 목적으로만 사용되며, 상업적 사용은 엄격히 금지**됩니다.
3. 본 소프트웨어는 영상·탄막·이미지·텍스트를 저장하지 않으며 재생만 제공합니다.
4. 본 소프트웨어는 사용자 데이터를 수집하거나 업로드하지 않습니다. 로그인 정보는 로컬에만 저장됩니다.
5. 본 소프트웨어는 기능의 완전성과 안정성을 보장하지 않습니다.

</details>

<details>
<summary>🇺🇸 English</summary>

1. This software is an **unofficial** Bilibili client and is not affiliated with Bilibili Inc.
2. This software and source code are **for learning and communication purposes only. Commercial use is strictly prohibited**.
3. This project stores no video, danmaku, image, or text content; it only presents playback. All related content copyrights belong to Bilibili and the original authors.
4. This project collects and uploads no user data; login credentials are stored locally only.
5. This software is provided "as is", without warranty of completeness, stability, or continued updates.

</details>

---

<p align="center">
    <sub>메인테이너 / Maintainer: <a href="https://github.com/qaqmin">qaqmin</a>(구 min09577) · Codeberg 미러: <a href="https://codeberg.org/min09577/btelly">min09577/btelly</a> · 소스 코드는 학습 및 교류 목적 전용</sub>
</p>
