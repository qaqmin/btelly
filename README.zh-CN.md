[English](README.md) | **简体中文** | [日本語](README.ja-JP.md) | [한국어](README.ko-KR.md)

---

# <p align="center">B视机 · BTelly</p>
<p align="center"><strong>第三方 Bilibili Android TV / 车机 客户端 | 双播放内核 · 弹幕评论 · 空降助手 · 青少年模式 | Android 6.0 → 15 全世代覆盖 | Kotlin · MVVM · Koin · 无广告</strong></p>
<p align="center">
    <a href="https://github.com/qaqmin09577/btelly/releases/latest">
        <img alt="Latest Release" src="https://img.shields.io/github/v/release/qaqmin09577/btelly?style=flat&color=blue">
    </a>
    <a href="https://github.com/qaqmin09577/btelly/issues">
        <img alt="GitHub Issues" src="https://img.shields.io/github/issues/qaqmin09577/btelly?style=flat&color=red&label=%F0%9F%90%9B%20Bug%20%2F%20Feature">
    </a>
    <img alt="API" src="https://img.shields.io/badge/API-23%2B-brightgreen">
    <img alt="Android" src="https://img.shields.io/badge/Android-15%20ready-blue">
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-purple">
</p>

---

> ### 👤 维护者近况 / Maintainer's Corner
> 本项目利用业余时间维护，遥控器真机验收 + Mac 远程构建机持续产出。
> 每一枚被提交的 Bug，都会被逐一排查、逐一修复。
>
> - 📺 **测试设备**：Android 电视盒（遥控器操作验收为主），兼顾车机显示模式适配
> - 🚗 **车机模式**：设置内一键切换，文字与触控间距统一放大约 25%，电视模式零变化
>
> 📌 **账号迁移公告（2026-10-05）**：原 GitHub 账号 **min09577** 因不可抗拒原因被封禁（仓库均已 404）。
> 本项目已整体迁移至新账号 **[qaqmin09577](https://github.com/qaqmin09577/btelly)**；
> Codeberg 镜像（min09577/btelly）继续同步更新，作为备用渠道。
> 应用内「检查更新」已支持 **GitHub / Codeberg 双通道检测**，自动取两者中版本最新的一版；
> 签名钥匙不变，覆盖更新无缝衔接。

---

## ☕ 随缘赞助 (Sponsor)

这个项目是利用业余时间“用爱发电”写出来的，能帮到大家我非常开心。不过随着项目的不断完善，跑测试消耗的 API Tokens 确实超出了我的预期，加上长时间的调试，也搭进去了不少休息时间（笑）。

如果这个项目恰好为你解决了问题，或者帮你省下了一些折腾的时间，欢迎随缘投喂。你的打赏将全部用于“回血”高昂的 API 账单，这也是让我能毫无顾忌持续更新它的最大动力。

当然，完全自愿，千万别有任何压力。只要你觉得好用，点个 **Star** 同样是对我极大的鼓励！

> **Tip:** 为了避免大家承担高昂的转账手续费，建议通过 **BNB Smart Chain (BEP-20)** 网络进行转账。感谢支持！

<p align="center">
    <img src="docs/images/sponsor_qr.png" alt="Sponsor QR (Binance · BEP-20)" width="360">
</p>

<p align="center"><i>币安收款二维码 · Binance Wallet QR（USDT · BNB Smart Chain / BEP-20）</i></p>

---

## ✨ 为什么选择 B视机 / Why BTelly

| 特性 | 说明 |
|------|------|
| 📺 **电视/车机双形态** | 专为遥控器操作设计；车机模式文字与间距统一放大约 25%，七套皮肤全局适配 |
| 🎬 **全站视频** | 番剧、电影、电视剧、UGC；首页推荐/热门/排行榜/番剧/影视五大 tab |
| 🔀 **双播放内核** | ExoPlayer / IjkPlayer 自由切换，下次播放生效；IjkPlayer 随包分发（全 ABI），无需额外下载 |
| 📡 **直播 & CCTV** | 直播弹幕实时呈现；央视 1-17 套遥控器上下直接切台 |
| 💬 **弹幕引擎** | Protobuf 协议解析，人物区域智能防挡；T9 键盘发弹幕 |
| 🗨️ **播放器内评论** | 播放中 → 更多操作 → 评论，评论区直接覆在播放器上，BACK 即回播放器，不错过正在播的画面 |
| ✈️ **空降助手** | 自动跳过恰饭/开场/片尾片段，进度条显示片段标记 |
| 🎮 **互动视频 / 抖音模式** | 互动分支选择；抖音模式上下滑动切换推荐流 |
| 👶 **青少年模式** | 设置内即点即用的家长控制内容过滤，无需秘籍 |
| 🔍 **搜索/收藏/历史** | 搜索直达播放、收藏夹卡片堆叠、观看进度自动续播 |
| ✨ **Min化 动效** | 焦点弹簧 / 列表浮入 / Hero 转场，一个总开关回到零动画直出 |
| 📲 **扫码登录** | TV 端扫码快速登录，凭据仅存本地 |
| 🔒 **隐私优先** | 无埋点跟踪，仅与 B 站 API 通信，不收集不上传任何用户数据 |
| 📱 **超长世代兼容** | 横跨 Android 6.0 (API 23) 至 Android 15 (API 35) |

---

## 📖 简介

B视机（BTelly）是一个**非官方**的 Bilibili Android TV / 车机客户端，使用 Kotlin 编写，
MVVM + Koin 架构，青碧深色自有设计语言。专为电视遥控器与车机操作场景设计：
大焦点、大间距、纯遥控器可达，覆盖首页浏览、视频播放、弹幕互动到追剧追番的完整链路。

> **⚠️ 声明：** 本软件及源码仅供学习交流使用，严禁用于商业用途。与哔哩哔哩（Bilibili）无关。

### 🛠️ 技术栈 / Tech Stack

- **Kotlin 2.1.0** · MVVM + Koin 依赖注入
- **Coroutines + Flow + LiveData** 异步
- **Retrofit + OkHttp + Gson** 网络层
- **Media3 (ExoPlayer) / IjkPlayer** 双播放内核（CCTV 走 WebView）
- **弹幕引擎**（Protobuf 协议解析）
- **DataStore Preferences** 数据存储
- **AndroidX** UI 组件
- **minSdk 23 (Android 6.0)** / **targetSdk 35 (Android 15)**

## 📷 APP 截图

首页（推荐 / 热门 / 排行榜 / 番剧 / 影视）
<img width="960" height="540" alt="home" src="screenshots/home.png" />

播放 & 弹幕
<img width="960" height="540" alt="player" src="screenshots/player.png" />

视频详情
<img width="960" height="540" alt="detail" src="screenshots/detail.png" />

关于本机 & 检查更新
<img width="960" height="540" alt="about" src="screenshots/about.png" />

## 🆕 v3.0.8 — Hero 转场：封面飛进详情页

> ### 🖼️ 点卡片，封面自己飞过去
> 点视频卡片进详情页，封面从卡片原位飞到头图位置（300ms 减速插值、圆角随飞行过渡），
> 按返回再飞回卡片；详情内点相关视频同样接力。覆盖首页/动态/搜索/收藏/历史全部卡片入口；
> 详情数据慢或接口失败时等待 2.5s 自动撤下，不卡页面；随「Min化」总开关门控。

## 🆕 v3.0.7 / v3.0.6 — Min化 动效全面铺开

> ### 🎞️ 列表浮入 + 对话框弹簧
> 首页/直播等列表首次进场 stagger 浮入（子项 12% 递延迟、上移 14% + 渐显）；
> 全部 16 处对话框弹簧呈现（0.92 倍放大回弹 + 渐显）、首页/直播切页淡入——
> 均随「Min化」总开关门控：关闭时零动画直出。

## 🆕 v3.0.5 — Min化 动画总开关

> ### 🎛️ 一个开关，两种性格
> 设置 → 界面新增「Min化」总开关（默认开）：关闭后全站动效零动画直出，
> 等价于系统「减少动态效果」，对低性能设备与敏感用户友好。

## 🆕 v3.0.4 — 焦点弹簧：遥控器也有"Apple 味"

> ### 🍏 动效基座落地
> 视频卡片、tab chip、导航轨、设置行聚焦时轻微放大回弹并抬升层级，
> 失焦减速回落——UiMotion / UiFocusMotion 中央令牌统一驱动。

## 🆕 v2.1.0 — 更名 B视机 · 双播放内核纪元

> ### 🔀 ExoPlayer / IjkPlayer 自由切换
> 新增「播放器内核」设置：IjkPlayer 支持视频与直播播放（DASH 双轨本地 MPD 合流、
> 直播直连 FLV/HLS），内核 .so 随包分发全 ABI；为 Media3 Player 门面实现，
> 弹幕/进度/控制既有链路零适配。青少年模式改为设置内直接开关，即点即用。
>
> ### 🏷️ 品牌统一
> 界面、图标、代码包名与仓库命名统一为 B视机（BTelly），安装标识切换为 com.btelly。

## 🆕 v3.0.9 — 全站排行榜 + 播放器评论入口

> ### 🏆 排行榜 tab + 播放器内评论
> 首页新增「排行榜」tab（推荐/热门/排行榜/番剧/影视五档）；
> 播放器「更多操作」面板新增评论入口，评论区直接覆在播放器上、BACK 返回播放不中断
> （顺手修复了评论区容器缺失导致的一处崩溃）；更新检测升级为双通道。

## 💡 使用贴士 / Pro Tips

| 场景 | 建议 |
|------|------|
| 🕹️ **纯遥控器操作** | OK 播放/确认，BACK 逐级返回；视频卡片长按可唤起快捷菜单 |
| 💬 **看评论** | 播放中 → 更多操作 → 评论；按 BACK 返回播放器，播放不中断 |
| ✍️ **发弹幕** | 播放中 → 更多操作 → 发送弹幕，T9 键盘输入，回车发送 |
| ✈️ **空降助手** | 播放器进度条自动标记可跳过片段，跟随设置开关 |
| 👶 **青少年模式** | 设置 → 青少年模式，即点即用，拦截不适内容 |
| 🔄 **检查更新** | 设置 → 关于本机 → 检查更新（GitHub / Codeberg 双通道，取最新版） |
| 🐛 **排障辅助** | 关于页查看日志 → 复制，提交 issue 时附上日志，事半功倍 |

## 💬 反馈与贡献 / Feedback and Contribution

🐛 发现 Bug？💡 有好想法？欢迎通过 GitHub Issues 提交：

<p align="center">
    <a href="https://github.com/qaqmin09577/btelly/issues">
        <img alt="GitHub Issues" src="https://img.shields.io/github/issues/qaqmin09577/btelly?style=flat&color=red&label=%F0%9F%90%9B%20Bug%20%2F%20Feature">
    </a>
</p>

> 提交时请携带**应用日志**（关于页 → 查看日志 → 复制），越详细修复越快。

## 👨‍💻 原作者 / Original Author

本项目由 **[QAQMin (min09577)](https://github.com/qaqmin09577)** 开发并维护，
2026-09 更名为 B视机（BTelly）。

> 🙏 **绝大部分页面和操作逻辑参考自 [BBLL](https://github.com/xiaye13579/BBLL)**，
> 部分关键功能参考 PiliPlus / BiliPai / Blbl，B 站 API 行为参考
> [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)。
> 对上述开源项目表示由衷感谢。

## 🔗 友情链接

+ [SocialSisterYi/bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) - B 站 API 收集整理
+ [xiaye13579/BBLL](https://github.com/xiaye13579/BBLL) - 页面与操作逻辑的主要参考实现
+ [bggRGjQaUbCoE/PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) / [jay3-yy/BiliPai](https://github.com/jay3-yy/BiliPai) / [cat3399/blbl](https://github.com/cat3399/blbl) - 部分关键功能参考

---

## 📋 版本迭代记录 / Version History

| 版本 / Version | 日期 / Date | 说明 / Description |
|---|---|---|
| v3.0.8 | 2026-09-26 | 🖼️ **Hero 转场** — 封面飛进详情页 · 全部卡片入口覆盖 · 返回飞回原位 |
| v3.0.7 | 2026-09-26 | 🎞️ **列表 stagger 浮入** — 首页/直播首次进场递延上移渐显 |
| v3.0.6 | 2026-09-26 | 🪀 **对话框弹簧呈现** — 16 处接入 · 首页/直播切页淡入 |
| v3.0.5 | 2026-09-25 | 🎛️ **Min化 动效总开关** — 一键回到零动画直出 |
| v3.0.4 | 2026-09-25 | 🍏 **焦点弹簧** — 卡片/tab/导航轨聚焦回弹 · UiMotion 令牌基座 |
| v3.0.3 | 2026-09-25 | 🚗 **车机间距铺开收官** — 对话框 + 详情页头部接入 · 电视模式零变化 |
| v3.0.2 | 2026-09-25 | 📺 **直播间令牌化收尾** — 房间卡片/列表背景/空态文案统一主题 |
| v3.0.1 | 2026-09-25 | 🩺 **直播音画同步专项** — HEVC 路 14 分钟真机遥测无漂移 |
| v3.0.0 | 2026-09-25 | 🎨 **图标体系整体重画 56 个** · 车机模式全屏铺开 · 收藏夹/用户空间改版 · 旧色令牌清零 |
| v2.1.0 | 2026-09-25 | 🏷️ **更名 B视机 (BTelly)** — 包名切换 com.btelly · **双播放内核** · 青少年模式开关 · 欢迎页/设置改版 |
| v2.0.4 | 2026-09-18 | 🐛 **弹幕滚动速度修复** · 个人空间页重做 · 用户信息弹窗官网风 |
| v2.0.3 | 2026-09-10 | 🔤 **UI 文字大小五档** · **视频卡片大小四档** · 首页转圈修复 |
| v2.0.2 | 2026-09-10 | 🐛 搜索串台/焦点丢失修复 · 弹幕字号上限 100 · 超宽屏每排 8 卡 |
| v2.0.1 | 2026-09-10 | 🎫 **试看提示明确化** · 试看结束停止连播 · 若干加载修复 |
| v2.0.0 | 2026-09-09 | 📦 **包名变更版本**（需卸载旧版重装）· 返回键焦点异常修复 |
| [▶ 完整日志](CHANGELOG.md) | | 更早版本（v1.2.0 ~ v1.6.x，2025-04 ~ 2026-09）见 CHANGELOG.md |

---

## 🛠️ 构建说明 / Build Instructions

### 环境要求 / Prerequisites

- **JDK 17+**
- **Android SDK**（compileSdk 35）
- Android Studio（推荐）

### 构建命令 / Build Commands

```bash
# Debug 构建 / Debug Build
./gradlew assembleDebug

# Release 构建 / Release Build
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/` 目录，产物名格式为 `BTelly-v<版本号>-<buildType>.apk`。

### 签名说明 / Signing

Release 签名由 **Mac 远程构建机**统一管理（一项目一把密钥，仅生成一次，永不重建），
keystore 三件套**不在本仓库内**。本地自行构建 Release 请自备签名配置：

```properties
# keystore.properties（勿提交到版本控制系统 / Do NOT commit）
storeFile=your_keystore_file.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

> ⚠️ **注意 / Note:** 请勿将 `keystore.properties` 与任何 `.jks` 文件提交到版本控制系统。

---

## ⚠️ 免责声明 / Disclaimer

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
    <sub>维护者 / Maintainer: <a href="https://github.com/qaqmin09577">qaqmin09577</a>（原 min09577） · Codeberg 镜像：<a href="https://codeberg.org/min09577/btelly">min09577/btelly</a> · 源码仅供学习交流</sub>
</p>
