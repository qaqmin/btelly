**English** | [简体中文](README.zh-CN.md) | [日本語](README.ja-JP.md) | [한국어](README.ko-KR.md)

---

# <p align="center">B视机 · BTelly</p>
<p align="center"><strong>Third-party Bilibili client for Android TV / car head units | Unofficial | Kotlin + Koin | Dual playback cores · danmaku & comments · sponsor-skip | No ads</strong></p>
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

> ### 👤 Maintainer's Corner
> This project is maintained in spare time, with real-device acceptance on a TV box and a stable
> output line from a remote Mac build machine. Every reported bug gets investigated and fixed, one by one.
>
> - 📺 **Test devices**: an Android TV box (remote-control acceptance focus), plus car display-mode adaptation
> - 🚗 **Car mode**: one toggle in settings — text and touch spacing scale up by ~25% uniformly, zero change in TV mode
>
> 📌 **Account migration notice (2026-10-10)**: this project's GitHub accounts have been banned twice — **min09577** and **qaqmin09577** were both taken down for reasons beyond our control (all repositories 404). Two fallen accounts, remembered here.
> The project has fully migrated to the new account **[qaqmin](https://github.com/qaqmin/btelly)**;
> the Codeberg mirror (min09577/btelly) keeps syncing as a backup channel.
> The in-app update check supports **dual-channel detection across GitHub / Codeberg**, automatically picking the newest version;
> the signing key is unchanged, so over-the-top updates stay seamless.

---

## ☕ Sponsor (Totally Optional)

This project is written with love in spare time, and I'm happy whenever it helps someone.
The ever-growing test API usage has exceeded my expectations though, and countless debugging hours have eaten into my rest time (lol).

If this project happens to solve a problem for you, or saves you some tinkering time, a tip is welcome — but entirely optional.
All tips go toward "refilling" the API bills, and are the biggest motivation to keep updating it without hesitation.

Of course, it's completely voluntary — please don't feel any pressure. If you find it useful, a **Star** is encouragement enough!

> **Tip:** to spare everyone hefty transfer fees, **BNB Smart Chain (BEP-20)** is the suggested network. Thanks for the support!

<p align="center">
    <img src="docs/images/sponsor_qr.png" alt="Sponsor QR (Binance · BEP-20)" width="360">
</p>

<p align="center"><i>Binance Wallet QR (USDT · BNB Smart Chain / BEP-20)</i></p>

---

## ✨ Why BTelly

| Feature | Description |
|------|------|
| 📺 **TV / car dual form factors** | Built for remote control; car mode scales text and spacing up ~25% uniformly, seven themes adapt globally |
| 🎬 **Full video library** | Anime, movies, TV series, UGC; five home tabs: Recommended / Trending / Ranking / Anime / Cinema |
| 🔀 **Dual playback cores** | Switch freely between ExoPlayer / IjkPlayer, effective on next playback; IjkPlayer ships in-package (all ABIs), no runtime download |
| 📡 **Live & CCTV** | Live-stream danmaku in real time; CCTV channels 1-17 switch up/down with the remote |
| 💬 **Danmaku engine** | Protobuf protocol parsing with smart occlusion avoidance; send danmaku with the T9 keyboard |
| 🗨️ **In-player comments** | Playback → More actions → Comments; the comment page overlays the player, BACK returns to playback without losing the picture |
| ✈️ **Sponsor-skip** | Automatically skips sponsor/intro/outro segments with markers on the progress bar |
| 🎮 **Interactive video / Douyin mode** | Branch choices for interactive videos; swipe up/down for the recommendation feed in Douyin mode |
| 👶 **Teen mode** | Parental-control content filtering toggled directly in settings, no cheat codes |
| 🔍 **Search / Favorites / History** | Search straight into playback, stacked favorites cards, automatic resume |
| ✨ **Min-ified motion** | Focus springs / list stagger / Hero transitions, one master switch back to zero animation |
| 📲 **QR-code login** | Quick TV-side QR login; credentials stay on-device |
| 🔒 **Privacy first** | No tracking, talks only to Bilibili APIs, collects and uploads nothing |
| 📱 **Long-generation compatibility** | Spans Android 6.0 (API 23) to Android 15 (API 35) |

---

## 📖 Introduction

BTelly (B视机) is an **unofficial** Bilibili client for Android TV and car head units, written in Kotlin with an MVVM + Koin architecture and a signature deep-teal design language. It is purpose-built for TV remotes and car controls: large focus targets, generous spacing, fully remote-reachable — covering the whole journey from browsing and playback to danmaku interaction and binge-watching.

> **⚠️ Notice:** this software and its source code are for learning and communication only. Commercial use is strictly prohibited. Not affiliated with Bilibili.

### 🛠️ Tech Stack

- **Kotlin 2.1.0** · MVVM + Koin dependency injection
- **Coroutines + Flow + LiveData** for async
- **Retrofit + OkHttp + Gson** networking
- **Media3 (ExoPlayer) / IjkPlayer** dual playback cores (CCTV via WebView)
- **Danmaku engine** (Protobuf protocol parsing)
- **DataStore Preferences** storage
- **AndroidX** UI components
- **minSdk 23 (Android 6.0)** / **targetSdk 35 (Android 15)**

## 📷 Screenshots

Home (Recommended / Trending / Ranking / Anime / Cinema)
<img width="960" height="540" alt="home" src="screenshots/home.png" />

Playback & danmaku
<img width="960" height="540" alt="player" src="screenshots/player.png" />

Video detail
<img width="960" height="540" alt="detail" src="screenshots/detail.png" />

About & update check
<img width="960" height="540" alt="about" src="screenshots/about.png" />

## 🆕 v3.0.8 — Hero transition: covers fly into the detail page

> ### 🖼️ Tap a card, the cover flies over
> Tapping a video card flies the cover from its original position to the header image spot (300ms decelerated interpolation, corner radius morphing through the flight), and BACK flies it back to the card; related videos inside the detail page chain the same motion. Covers every card entry (home/dynamics/search/favorites/history); if detail data is slow or the API fails, the overlay retracts after 2.5s without blocking the page; the whole chain is gated by the "Min-ified" master switch.

## 🆕 v3.0.7 / v3.0.6 — Min-ified motion rolled out across the app

> ### 🎞️ List stagger + spring dialogs
> Home/live lists stagger in on first entry (12% progressive delay per item, 14% rise + fade); all 16 dialogs spring in (0.92x scale bounce + fade), home/live page switches cross-fade — all gated by the "Min-ified" master switch: off means zero animation.

## 🆕 v3.0.5 — Min-ified motion master switch

> ### 🎛️ One switch, two personalities
> New "Min-ified" master toggle in Settings → UI (on by default): off disables all motion app-wide, equivalent to the system "remove animations" option, friendly to low-end devices and motion-sensitive users.

## 🆕 v3.0.4 — Focus springs: even the remote gets an "Apple" feel

> ### 🍏 The motion foundation lands
> Video cards, tab chips, the nav rail and settings rows subtly scale-bounce and lift on focus, decelerating back on blur — all driven by central UiMotion / UiFocusMotion tokens.

## 🆕 v2.1.0 — Renamed BTelly · dual playback core era

> ### 🔀 Freely switch ExoPlayer / IjkPlayer
> New "Playback core" setting: IjkPlayer supports video and live playback (DASH dual tracks merged via local MPD, live via direct FLV/HLS), native libs ship for all ABIs; it is a Media3 Player façade implementation, so danmaku/progress/control paths need zero adaptation. Teen mode became a direct settings toggle, effective immediately.
>
> ### 🏷️ Unified branding
> UI, icons, package name and repository naming all switched to BTelly; the install identifier changed to com.btelly.

## 🆕 v3.0.9 — Ranking tab + in-player comments

> ### 🏆 Ranking tab + in-player comments
> New "Ranking" tab on the home page (Recommended / Trending / Ranking / Anime / Cinema, five in total);
> the player's "More actions" panel gained a comments entry — the comment page overlays the player and BACK returns to playback without interruption
> (a crash from a missing comment-page container was fixed along the way); the update check was upgraded to dual-channel.

## 💡 Tips / Pro Tips

| Scenario | Suggestion |
|------|------|
| 🕹️ **Pure remote control** | OK to play/confirm, BACK steps back; long-press a video card for the quick menu |
| 💬 **Reading comments** | During playback → More actions → Comments; BACK returns to the player, playback never stops |
| ✍️ **Sending danmaku** | During playback → More actions → Send danmaku, type with the T9 keyboard, Enter to send |
| ✈️ **Sponsor-skip** | Skippable segments are marked automatically on the player progress bar, following the setting |
| 👶 **Teen mode** | Settings → Teen mode, one tap to use, blocks unsuitable content |
| 🔄 **Check for updates** | Settings → About → Check update (dual channel across GitHub / Codeberg, newest wins) |
| 🐛 **Troubleshooting** | About page → View log → Copy, and attach it when filing an issue |

## 💬 Feedback & Contribution

Found a bug? Have an idea? File it via GitHub Issues:

<p align="center">
    <a href="https://github.com/qaqmin/btelly/issues">
        <img alt="GitHub Issues" src="https://img.shields.io/github/issues/qaqmin/btelly?style=flat&color=red&label=%F0%9F%90%9B%20Bug%20%2F%20Feature">
    </a>
</p>

> Please attach the **app log** (About page → View log → Copy) when filing — the more detail, the faster the fix.

## 👨‍💻 Original Author

This project is developed and maintained by **[QAQMin (min09577)](https://github.com/qaqmin)**, and was renamed to BTelly in 2026-09.

> 🙏 **Most pages and interaction logic follow [BBLL](https://github.com/xiaye13579/BBLL)**, with some key features referencing PiliPlus / BiliPai / Blbl, and Bilibili API behavior referencing
> [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect).
> Heartfelt thanks to the above open-source projects.

## 🔗 Friendly Links

+ [SocialSisterYi/bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) - Bilibili API reference collection
+ [xiaye13579/BBLL](https://github.com/xiaye13579/BBLL) - the main reference for pages and interaction logic
+ [bggRGjQaUbCoE/PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) / [jay3-yy/BiliPai](https://github.com/jay3-yy/BiliPai) / [cat3399/blbl](https://github.com/cat3399/blbl) - references for some key features

---

## 📋 Version History

| Version | Date | Description |
|---|---|---|
| v3.0.8 | 2026-09-26 | 🖼️ **Hero transition** — covers fly into the detail page · all card entries covered · fly back on return |
| v3.0.7 | 2026-09-26 | 🎞️ **List stagger entry** — home/live first-entry progressive rise + fade |
| v3.0.6 | 2026-09-26 | 🪀 **Spring dialogs** — 16 dialogs · home/live page-switch fade |
| v3.0.5 | 2026-09-25 | 🎛️ **Min-ified motion master switch** — one tap back to zero animation |
| v3.0.4 | 2026-09-25 | 🍏 **Focus springs** — card/tab/nav-rail focus bounce · UiMotion token foundation |
| v3.0.3 | 2026-09-25 | 🚗 **Car spacing rollout finale** — dialogs + detail-page header · zero change in TV mode |
| v3.0.2 | 2026-09-25 | 📺 **Live-room tokenization finale** — room cards / list background / empty-state copy unified |
| v3.0.1 | 2026-09-25 | 🩺 **Live A/V sync investigation** — 14-minute HEVC real-device telemetry, no drift |
| v3.0.0 | 2026-09-25 | 🎨 **Icon system redrawn (56 icons)** · car mode full-screen rollout · favorites/user-space rework · legacy colors tokenized away |
| v2.1.0 | 2026-09-25 | 🏷️ **Renamed to BTelly** — package switched to com.btelly · **dual playback cores** · teen-mode toggle · welcome/settings rework |
| v2.0.4 | 2026-09-18 | 🐛 **Danmaku scroll-speed fix** · user-space page rework · official-style user info dialog |
| v2.0.3 | 2026-09-10 | 🔤 **UI text size (5 steps)** · **video card size (4 steps)** · home spinner fix |
| v2.0.2 | 2026-09-10 | 🐛 Search bleed / focus-loss fixes · danmaku font cap 100 · 8 cards per row on ultra-wide |
| v2.0.1 | 2026-09-10 | 🎫 **Explicit preview hints** · stop autoplay after preview ends · misc loading fixes |
| v2.0.0 | 2026-09-09 | 📦 **Package rename release** (uninstall before install) · BACK key focus fix |
| [▶ Full log](CHANGELOG.md) | | Earlier versions (v1.2.0 ~ v1.6.x, 2025-04 ~ 2026-09) in CHANGELOG.md |

---

## 🛠️ Build Instructions

### Prerequisites

- **JDK 17+**
- **Android SDK** (compileSdk 35)
- Android Studio (recommended)

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

Build outputs live in `app/build/outputs/apk/`, named `BTelly-v<version>-<buildType>.apk`.

### Signing

Release signing is managed by the **remote Mac build machine** (one key per project, generated once, never regenerated), and the keystore trio is **not in this repository**. For local release builds, bring your own signing config:

```properties
# keystore.properties (do NOT commit)
storeFile=your_keystore_file.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

> ⚠️ **Note:** never commit `keystore.properties` or any `.jks` file to version control.

---

## ⚠️ Disclaimer

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
    <sub>Maintainer: <a href="https://github.com/qaqmin">qaqmin</a> (formerly min09577) · Codeberg mirror: <a href="https://codeberg.org/min09577/btelly">min09577/btelly</a> · Source for learning and communication only</sub>
</p>
