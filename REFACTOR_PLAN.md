# 全面重构路线图

> 分支：`refactor/cleanup-optimization`　开始日期：2026-08-29
> 原则：分步进行、每步编译验证、提交由用户发起。

## 阶段 1：工具函数合并 + 死代码清理（低风险）

### 1a 格式化工具统一（并入 `core/common/format`）——已完成
- [x] `formatCount` 私有副本 ×3 → `NumberUtils.formatCount`
  - VideoPlayerFragment、PlayerActivity 已删除；VideoDetailContentAdapter 保留（含"千"分支，为有意变体），`formatFans` 已改公共版
  - 注：1 万以下数字现在统一带千分位逗号（与 App 其他页面一致）
- [x] `formatTime(timeMs)` ×3 + PlayerActivity `formatMs` → `NumberUtils.formatTimeMs`（新增）
- [x] `TimeUtils.formatDuration` 已删，唯一调用点迁移到 `NumberUtils.formatDuration`
- [x] `formatCodecName/formatAspectRatio` → 新建 `MediaFormatUtils`（VideoPlayerFragment + PlayerActivity）
- [x] `formatDanmakuRange` → `MediaFormatUtils.formatDanmakuRange`（DanmakuPlaybackController + VideoPlayerPlayInfoGateway）
- [x] `formatPublishTime/formatHistoryTime` → 直接内联 `TimeUtils.formatRelativeTime/formatHistoryViewTime`（本身已处理 <=0 返回空）
- [x] `formatBytes/formatFileSize` → `NumberUtils.formatBytes`（ApkUpdater + SettingsFragment）
- [~] `formatCacheAge` 三胞胎 → 延后到阶段 3 随 `BaseFeedCacheRepository` 一并处理
- [~] dp→px ×8 → 评估后放弃：各处仅 1-3 行且舍入策略不一（±0.5f），抽公共无净收益

### 1b 死代码 / 死资源删除——已完成
- [x] `Extensions.kt` 删除 9 个 0 引用函数（View 扩展区、hasPermission、openAppSettings、serializableCompat），去掉 `@file:Suppress("unused")`
- [x] `ScreenUtils` 删除 `pxToDp`/`getScreenDensity`/`getScreenHeight`（均 0 引用）
- [x] 删除整个 `model/user/DynamicFollowingModels.kt`（4 个类全部 0 引用）
- [x] `SearchRemoteModels.kt` 删除 `SearchHotWordItem`、`SearchSuggestResponse`
- [x] 删除 `@Deprecated` 死方法：`WbiGenerator.getSpaceDmParams`、`NetworkSessionGateway.handleResponseAuthError`（接口+实现，0 调用）
- [x] 经复核**保留**（有引用）：TabLayoutExtensions、SearchAllModels、TvLoginModels（TvCookie 在 SignInFragment 使用）、BackNavigation、TypedIds、ViewPager2Tuning、DmModelExt
- [x] 删除 11 个无引用 drawable（bg_splash 经复核被主题引用，已恢复保留）

### 1c 验证
- [x] `gradlew compileDebugKotlin` 通过（含资源链接）

## 阶段 2：性能优化——已完成
- [x] `Gson()` ×14 → 新建 `core/common/json/GsonHolder`（DEFAULT / CONFIGURED 两实例），`createGson()` 自身也加了缓存
- [x] `SimpleDateFormat` 热点复用：`TimeUtils.formatTime` 改静态字段；其余 4 处为低频路径（日志/一次性提示），保留
- [x] DataStore 启动优化：`initCacheBlocking` 不再取消并重复读盘，改为 join BaseActivity 阶段已启动的异步读（消除同份 XML 双读，读盘与其他启动步骤重叠）
  - 评估后保留：`initCacheBlocking` 在 MainActivity.onCreate 的同步语义（后续 isLoggedIn 读取依赖）；CookieManager 的 blocking 持久化（刻意的 durability 设计）
- [~] `X5TbsDownloader` Thread.sleep：评估后保留——已在专用后台线程、是 TBS SDK 安装要求的等待时序、一次性手动流程
- [~] player postDelayed 链：评估后保留——刷新链职责清晰，收敛属高风险低收益

## 阶段 3：中等重构——已完成
- [~] Home 三个 FeedRepository：评估后**不抽基类**（三者网络逻辑差异大，重基类属过度设计）；实际收敛点：
  - [x] `formatCacheAge` ×3 / `isExpired` → `HomeCacheStore.cacheAgeMs/isExpired`
  - [x] 顺带删除 `HomeCacheStore.readVideos/readSections`（0 引用薄包装）
- [x] HotViewModel/RecommendViewModel 分页骨架 → 新建 `BaseVideoFeedViewModel`（loadInitial/refresh/loadMore/consumeListChange/loadPage 状态流转/filterForDisplay/writeCache 全部上提，子类只剩 fetchPage 差异逻辑，各减 ~80 行）
- [~] HotListFragment/RecommendListFragment：评估后无需归一——已是 VideoFeedFragment 的薄配置子类
- [~] LiveListFragment 迁移 BaseListFragment：评估后放弃——需换布局+改造 adapter（有标题栏/登录遮罩/首屏打点），实际重复仅 ~20 行滚动接线，风险收益不成比例
- [~] 三套加载状态建模（LiveListStatus/FeedUiState/MeListUiState）收敛：评估后放弃——跨 feature 公共 API 变更，纯美观收益

## 阶段 4：高风险重构——已完成（安全边界内）
- [x] PlayerActivity 与 VideoPlayerFragment 共 37 个同名函数。分三批去重：
  - [x] 未成年人保护对 → `PlayerContentGuards`
  - [x] 11 对逐字相同的界面逻辑 → `PlayerScreenLogic`（调试信息、进度事件、起始 seek、标题/元信息、chrome 状态机、slim 时间轴显隐、主操作显隐等）
  - [x] 其余双胞胎经 diff 证实语义有细微差异（TV/手机路径、判空回退不同），保留
- [x] VideoPlayerViewModel 拆分（3210 → 2899 行）：
  - [x] 字幕子系统（轨道/选中态/正文缓存/时间轴渲染，~330 行）→ `SubtitlePlaybackController`，视频身份经 provider 回调读取宿主最新值，subtitle_trace 诊断日志原样保留
  - [x] CDN 预连接三函数 → `CdnPreconnector`；URL 纯工具三函数 → `VideoPlayerUrlUtils`
- [~] MyPlayerView(3121 行) 拆分：评估后放弃——抖音手势子系统与 onTouchEvent 主分发 149 处交织、seek/动画路径时序敏感，无测试手段下盲拆手势代码风险远大于行数收益

## 附加清理——已完成
- [x] 删除根目录垃圾：`hs_err_pid49540.log`、`debug.txt`、`com/`（残留 .class）、空的 `tmp_bili_source/`（均不在 git 追踪内；`.trae/` 参考目录保留）

## 进度日志
- 2026-08-29：建分支，创建本文档，开始阶段 1a。
- 2026-08-29：阶段 1（工具合并 + 死代码/死资源清理）完成，编译通过。待用户确认后提交。
- 2026-08-29：阶段 2 完成（Gson 单例、SDF 热点复用、initCacheBlocking 消除双读；X5 轮询/postDelayed/Cookie blocking 持久化经评估刻意保留）。
- 2026-08-29：阶段 3 完成（BaseVideoFeedViewModel、HomeCacheStore 收敛；4 项评估后放弃并注明理由）。
- 2026-08-29：阶段 4 安全部分完成（PlayerContentGuards）；深拆与双胞胎合并列为后续独立任务。assembleDebug 通过。
- 2026-08-29：按用户要求改为每阶段一提交，补拆为 4 个 commit（aeffe35c/c6b4087b/13377f85/993f7ad8）。
- 2026-08-29：阶段 4 深化完成：PlayerScreenLogic（e87d3427）、SubtitlePlaybackController/CdnPreconnector/VideoPlayerUrlUtils（4662158b），VideoPlayerViewModel 瘦身至 2899 行；MyPlayerView 手势子系统评估后放弃拆分。assembleDebug 通过。全部计划任务完成。
