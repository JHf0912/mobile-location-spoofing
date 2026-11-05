# 04 - UI、数据与产品（UI / Data / Product）

**日期**: 2026-10-29
**来源**: Explore C，经独立审计修订
**范围**: Compose UI、可访问性/适配/本地化、Room 数据层、产品缺口
**方法**: 只读源码复核；无设备/截图/Compose 测试
**证据图例**: 见 `00-index.md` 第 3 节

本文件假定读者已读 `01-project-map.md` 第 3 节（数据流）。这里展开 UI 结构、性能、a11y 与数据层。

---

## 1. UI 地图（UI Map）

### 1.1 入口与路由

- 单 Activity：`MainActivity`（`MainActivity.kt:30-51`）。
- `onCreate` 调 `enableEdgeToEdge()` + `setContent { MockLocationTheme { MainRoute(viewModel) } }`（`:34-42`）。
- `MainRoute` 收集 `uiState`、注册权限 launcher、把 `MainActions` 绑定到 ViewModel（`:53-92`）。
- `onResume` 调 `viewModel.refreshCapabilities()`（`:44-50`），用于从设置页返回后刷新 AppOp/权限。
- 没有导航库、没有多屏；地图全屏是 `Dialog`（`MapCard.kt:65-71`）。

### 1.2 页面结构

`MainScreen`（`MainScreen.kt:59-189`）：

```text
Scaffold
  topBar    -> AppTopBar(state.status)                  MainScreen.kt:72
  bottomBar -> BottomActionBar(canStart, canStop)       :73-80
  snackbar  -> SnackbarHost(snackbarHostState)          :81
  content   -> LazyColumn                               :83-187
       item "hero"       -> StaggeredEntrance(0) -> StatusHero(state)
       item "location"   -> SectionHeader + ModeSegmentedControl + MapCard
       item "route"      -> 仅 ROUTE 模式；SectionHeader + RouteControls
       item "setup"      -> SectionHeader + SetupChecklist
       item "favorites"  -> SectionHeader + FavoriteSection
       item "bottom-space" -> Spacer
```

五个 keyed item 的 key 是字符串（`"hero"`、`"location"`、`"route"`、`"setup"`、`"favorites"`、`"bottom-space"`），见 `MainScreen.kt:95-186`。

### 1.3 组件清单

| 组件 | 职责 | 证据 | 状态 |
|---|---|---|---|
| `AppTopBar` | 标题/副标题 + 状态 pill | `AppTopBar.kt:27-59` | 顶部 Row 左侧非加权，右侧 pill |
| `StatusPill` | Idle/Running/Error 文本 + 点 | `StatusPill.kt:59-84` | `StatusDot` 在 Running 时无限脉冲 |
| `StatusHero` | 当前坐标、模式、途经点数、速度、计时器、错误 | `StatusHero.kt:28-111` | 坐标每秒 crossfade |
| `ModeSegmentedControl` | 单点/路线切换 | `ModeSegmentedControl.kt:33-92` | 已有 `selectableGroup()`（`:41-48`） |
| `MapCard` | 280dp 内联地图预览 + 全屏 Dialog | `MapCard.kt:38-138` | 内联 `gesturesEnabled=false`，点按打开 Dialog |
| `RouteControls` | 途经点计数、撤销/清空、速度、到达行为 | `RouteControls.kt:40-170` | 两个 FlowRow 无 `selectableGroup()` |
| `SetupChecklist` | 定位权限、模拟位置应用、电池白名单 | `SetupChecklist.kt:46-156` | 三行；chevron 有重复 contentDescription |
| `FavoriteSection` | 收藏名输入、保存、列表、删除 | `FavoriteSection.kt:43-185` | `forEachIndexed` 不虚拟化 |
| `BottomActionBar` | Start/Stop 两个等宽按钮 | `BottomActionBar.kt:29-93` | `canStop = status !is Idle`（`MainScreen.kt:76`） |

预览：

- `MainScreenPreviews.kt` 覆盖 Idle/Running/Error/Permission missing/Dark（`:27-102`）。
- `ComponentPreviews.kt` 覆盖 pill、segmented control、checklist、favorites、route controls。
- 预览时间源是 `SystemClock.elapsedRealtime() - 12_000L`（`MainScreenPreviews.kt:16`），不稳定；P0.5 要求换成固定值，为截图测试做准备。

### 1.4 主题与资源

- 颜色 token：`Color.kt:10-71`（`AppColors` + `LightAppColors`/`DarkAppColors`）。
- 字号：`Type.kt:10-90`（系统字体，数字样式 `fontFeatureSettings = "tnum"`）。
- 间距/圆角/尺寸：`Dimens.kt:5-29`（`AppSpacing`、`AppRadius`、`AppSize`）。
- Material3 scheme：`Theme.kt:9-88`；`MaterialTheme.appColors` 通过 `LocalAppColors` 提供（`:86-89`）。
- 字符串：`res/values/strings.xml`（中文）、`res/values-en/strings.xml`（英文）；`locales_config.xml` 只声明 zh/en。
- 未使用字符串：`map_fullscreen` 只在 `values/strings.xml:33` 和 `values-en/strings.xml:33` 定义，没有代码引用（本轮 grep 复核）。P0.4 要求删除或接入。

### 1.5 状态模型

`MainUiState`（`MainUiState.kt:17-53`）混合了五类信息：

| 类别 | 字段 | 说明 |
|---|---|---|
| 编辑草稿 | `mode`、`selectedPoint`、`routeWaypoints`、`travelSpeed`、`endBehavior` | 用户输入；进程死亡丢失 |
| 1 Hz 遥测 | `status`（含 `Running.elapsedRealtimeMs`）、`sessionStartedAtElapsedRealtimeMs` | 每秒变化 |
| 持久化数据 | `favorites: List<FavoriteItem>` | Room 流 |
| 能力标志 | `permissionGranted`、`canMock`、`batteryWhitelisted` | 设置页返回后刷新 |
| 消息 | `message: String?` | snackbar 一次性消息 |

派生属性：

- `canStart`：STATIC 需要 `selectedPoint != null`；ROUTE 需要 `routeWaypoints.size >= 2`（`MainUiState.kt:32-36`）。
- `canStartSession`：`canStart && permissionGranted && canMock && status !is Running`（`:44-48`）。
- 没有 `Starting`/`Stopping`，所以启动窗口内 `canStartSession` 仍为 true；Error 时也为 true（R8/R7）。

两个 `List` 字段（`routeWaypoints`、`favorites`）在 Compose 中默认被推断为 unstable；`MainUiState` 没有 `@Immutable`。这是整屏不可跳过重组的根因之一（见第 3 节）。

---

## 2. 状态与事件流

```text
MainViewModel.uiState (StateFlow<MainUiState>)
  -> MainRoute.collectAsStateWithLifecycle()          MainActivity.kt:55
  -> MainScreen(state, actions)                       MainActivity.kt:72-91
       -> AppTopBar(state.status)                     MainScreen.kt:72
       -> BottomActionBar(canStartSession, status)    :73-80
       -> StatusHero(state)                           :95-99
       -> MapCard(state)                              :118-123
       -> RouteControls(state)                        :128-143
       -> SetupChecklist(capabilities)                :145-162
       -> FavoriteSection(state.favorites, ...)       :164-182
```

事件反向：

- `MainActions` 是一个 data class，默认 lambda 为空（`MainScreen.kt:42-57`）。
- `MainActivity` 每次 composition 都构造新的 `MainActions`（`MainActivity.kt:74-89`），lambda 身份每秒变化；没有 `remember`。
- `MainScreen` 自己持有 `favoriteName`（`MainScreen.kt:67`），所以击键会重组整个 `MainScreen` 及其可见 item。

---

## 3. Compose 性能（Compose Performance）

### 3.1 1 Hz 状态驱动整屏重组

**证据链**

1. Runner 每秒发 `MockStatus.Running(fix.point, clock.nowMs())`（`MockSessionRunner.kt:125`）。
2. `Running` 的 `elapsedRealtimeMs` 让 data class 每秒不等（`Models.kt:27-31`）。
3. `MainViewModel.observeStatus()` 每秒 `state.copy(status = status, sessionStartedAtElapsedRealtimeMs = startedAt)`（`MainViewModel.kt:244-260`）。
4. `MainScreen` 的 `state` 变化，`StatusHero`/`MapCard`/`RouteControls`/`SetupChecklist`/`FavoriteSection` 全部每秒重组（`MainScreen.kt:95-187`）。
5. `MainScreen` 的 `favoriteName` 是本地 state，击键直接使 `MainScreen` 失效；`MainActions` 每次 composition 新建（`MainActivity.kt:74-89`），lambda 身份不稳定，进一步削弱跳过。

**量化**：静态会话 3,600 次/小时（有帧时）的全屏重组；画面上只有计时器和呼吸点在变。审计：这是性能问题，不是正确性 bug；"3,600 次/小时"只在有帧时成立。具体哪些子组件能跳过，依赖 Compose 稳定性推断；当前没有 compiler stability report 或 Compose 测试，上述重组范围是 Explore 的推断，Phase 2 需要实测。

### 3.2 计时器语义 bug

- `observeStatus` 在 Error 时保留旧 `sessionStartedAtElapsedRealtimeMs`（`MainViewModel.kt:252-255`）。
- 失败后重新开始会继承旧起点。
- ViewModel 在会话已运行时创建（进程重建/配置变化后）会以最新 tick 的 `elapsedRealtimeMs` 为起点，计时器归零（`:249-251`）。
- `rememberElapsedLabel` 是第二个 1 Hz 循环（`Motion.kt:76-91`），即使 UI 不在前台也会继续更新；它与 status 流不同步。
- 起点应由 runner/session 状态提供（session epoch），而不是 UI 推断。

### 3.3 坐标每秒 crossfade

`StatusHero` 对每秒变化的坐标做 `AnimatedContent`，180ms fadeIn + 120ms fadeOut（`StatusHero.kt:84-94`）。静态会话坐标字符串不变，所以没有 crossfade；路线模式下每秒约 300ms 在动画。等宽数字（`Type.kt` 的 `tnum`）已经足够避免抖动，动画反而增加噪声和成本。

### 3.4 `favoriteName` 提升到 `MainScreen`

- `var favoriteName by rememberSaveable { mutableStateOf("") }`（`MainScreen.kt:67`）。
- 输入框在 `FavoriteSection`（`:168-179`），每次击键回调 `onFavoriteNameChange`，更新 `MainScreen` 的状态。
- 结果：击键重组整个 `MainScreen` 和所有可见 item；`FavoriteSection` 的 `formatPoint` 对每个收藏行都跑一次（`FavoriteSection.kt:159`）。
- 50 个收藏时，Explore 估算每秒约 50 次 `String.format`，即使收藏没变（`inferred`）。
- 审计修正：不是"每次击键重组所有可见 item"的绝对表述；是击键触发 `MainScreen` 重组，收藏 item 是否重组取决于稳定性/跳过。1 Hz 状态本来就会全屏重组。

### 3.5 收藏列表不虚拟化

`FavoriteSection` 在 `Surface` 内用 `favorites.forEachIndexed`（`FavoriteSection.kt:127-183`），所有收藏行在同一个 LazyColumn item 内一次性组合/测量。100 条收藏会一次组合全部行。应拆成独立 keyed `items` + `animateItem`（Phase 2）。

### 3.6 地图双实例 + 每次编辑 O(N) marker 重建

- 内联 `MapCard` 持有 `AMapPicker`（`MapCard.kt:55-62`）。
- 全屏 Dialog 打开时，内联 `MapCard` 仍然组合，Dialog 又组合一个 `AMapPicker`（`MapCard.kt:65-71,124-134`），两个 `MapView` 同时存活。
- 每次 `waypointsWgs84`/`selectedPointWgs84` 变化，`AMapPicker` 移除全部 marker/polyline 再重建（`AMapPicker.kt:218-249`）。
- 100 点路线单次点击：约 100 次 remove + 101 次 add，两个 MapView 各做一遍。
- 审计修正：不是 O(N²)；是每次编辑、每个地图实例 O(N)。
- polyline 宽度 `10f` 是 px，不是 dp（`AMapPicker.kt:228-232`）。
- 相机状态不保存；只在单点且无 waypoint 时 `moveCamera`（`:239-248`）。

### 3.7 动效成本

- `StaggeredEntrance` 在 LazyColumn item 销毁/重建时重播（`Motion.kt:43-69`）；旋转/回收后也会重播。
- `StatusDot` 的无限动画按刷新率运行（`StatusPill.kt:87-114`）；已支持 reduce motion，但 `rememberMotionEnabled` 只在 composition 时读一次 `ANIMATOR_DURATION_SCALE`（`Motion.kt:29-39`），运行中改设置不生效。
- `rememberElapsedLabel` 是第二个不同步的 1 Hz 循环（`Motion.kt:76-91`）。
- `StatusDot` 已经在 `graphicsLayer {}` 内读 alpha（`StatusPill.kt:111`），所以不要用"删掉动画"来修它；应修可见性/动效偏好。

### 3.8 缺少 Compose 稳定性工具

- 没有 `@Immutable`、不可变集合（`kotlinx.collections.immutable`）、compiler metrics/stability report。
- `MainActions` 每次重组重建（`MainActivity.kt:74-89`）。
- 没有 baseline profile、没有 ABI split（AMap `.so` 占包体大头）。
- 没有 Compose UI test、截图测试、benchmark。

---

## 4. 可访问性与适配（Accessibility / Adaptation / Localization）

### 4.1 地图语义

- 内联预览是不可操作的 `Box.clickable`（`AMapPicker.kt:283-305`），没有 `role`、`contentDescription`、`onClickLabel`。
- 全屏选点只能点原生 `MapView`；键盘/TalkBack/开关控制无法添加途经点。
- `map_fullscreen` 字符串未被使用（`values/strings.xml:33`、`values-en/strings.xml:33`）。
- `MapPlaceholder` 的 `Icon` 是 `contentDescription = null`（`AMapPicker.kt:346-351`），文本 label 可见；整体没有可操作语义。

**审计**：R12 CONFIRMED；这是结构性 a11y 缺口，不是外观问题。

### 4.2 Heading / liveRegion / selectableGroup

- `SectionHeader` 没有 `semantics { heading() }`（`SectionHeader.kt:13-37`）。
- 坐标大字没有"纬度/经度"朗读语义（`StatusHero.kt:36,114-125`）。
- 错误行没有 `liveRegion` 设计（`StatusHero.kt:102-109`）。
- `SetupChecklist` 行内 chevron 有 `contentDescription = "Open settings"`（`SetupChecklist.kt:140-144`），与整行 clickable 语义重复；应改用 `onClickLabel`/`stateDescription`。
- `RouteControls` 的 `ChoiceChip` 用 `Role.RadioButton`（`RouteControls.kt:156-160`），但两个 `FlowRow` 没有 `selectableGroup()`（`:99-133`）；TalkBack 不会播报"1 of 4"。
- `ModeSegmentedControl` 已有 `selectableGroup()`（`ModeSegmentedControl.kt:41-48`），是正确的对照。

### 4.3 320dp + 1.3x 英文溢出

Explore C 的静态分析（`inferred`，需要截图/设备确认）：

- 顶栏：左侧标题/副标题列非加权，右侧 `StatusPill`；窄宽 + 1.3x 英文时可能挤压/溢出（`AppTopBar.kt:38-56`）。
- 底栏：两个按钮各 `weight(1f)`，内容区宽度约 280dp 减去 padding；每个约 136dp。`"Start mock"` 在 labelLarge 15sp × 1.3 ≈ 19.5sp + 48dp padding 下约需 145dp，可能溢出（`BottomActionBar.kt:52-89`、`Dimens.kt:24-27`）。
- 隐私同意卡：`Row` 内两个 intrinsic 按钮（Button + TextButton），在约 224dp 内容宽度内放不下（`AMapPicker.kt:147-165`）。
- `DESIGN.md:76` 声称 "320 dp width, font scale 1.3x and dark mode all render without clipping."，但没有自动化验证/设备矩阵。**本轮按"目标"处理，不是已验证事实。**

**审计**：R13 CONFIRMED（结构性）；需要截图确认具体溢出点。

### 4.4 IME / 键盘避让

- `MainScreen` 的 `Scaffold`/`LazyColumn` 没有 `imePadding()`（`MainScreen.kt:69-93`）。
- 收藏名输入框在页面底部（`FavoriteSection.kt:61-107`），键盘可能遮挡；底部 action bar 也可能被顶起/遮挡。
- 没有 IME 测试。

### 4.5 对比度 / focus / 夜间地图

Explore C 计算的对比度（`inferred`，基于 `Color.kt` 的值）：

| 颜色 | 用途 | 对比度（白底） | 问题 |
|---|---|---:|---|
| `accentCyan #29C0D2` | 选中边框/图标 | 约 2.2:1 | 低于 3:1 非文本要求 |
| `accentAmber #FFD43B` | Running 状态 | 约 1.4:1 | 低于 3:1 |
| `accentCyan` 选中边框 | chip 边界 | 约 2.0:1 | 低于 3:1 |
| `hairline #E2E6E8` | 1px 边框 | 约 1.25:1 | 装饰性可接受，但选中态不可依赖 |
| `muted #616A70` | 次要文本 | 通过 4.5:1 | 可接受 |

- 自定义 chip 没有可见 focus outline（`RouteControls.kt:147-163`）。
- 深色模式下 AMap 仍是白天地图（`AMapPicker.kt` 没有设置 `AMAP_MAP_TYPE_NIGHT`），亮色地图卡在深色界面里很突兀。
- 审计：对比度/1.3x 是真实问题；需要截图/axe 检查，不是本轮静态分析能定稿的。

### 4.6 RTL / 本地化

- 布局使用逻辑 start/end、auto-mirrored 图标（`SetupChecklist.kt:140-144`、`RouteControls.kt:77-80`），方向正确。
- 但坐标、`"km/h"`、`" · "`、速度标签是 LTR run（`StatusHero.kt:58-68`），缺少 bidi 隔离；RTL 下可能显示顺序错乱。
- 没有 RTL/pseudo-locale 预览或测试。
- `locales_config.xml` 只声明 zh/en；圈定范围符合 D2。
- `status_waypoints` 用普通字符串 `%1$d 个途经点`（`strings.xml:14`），不是 `<plurals>`；英文 `%1$d waypoints` 也不区分单复数。

### 4.7 减少动画

- `Motion.kt:29-39` 读 `ANIMATOR_DURATION_SCALE`，方向正确；但只在 composition 时读一次，运行中改设置不生效。
- 入场动画在旋转/回收后重播（`Motion.kt:43-69`）。
- `StatusDot` 的无限脉冲在 reduce motion 时关闭（`StatusPill.kt:92-105`），这是对的。

---

## 5. 数据层（Data Layer）

### 5.1 Room schema

- `@Database(version = 1, exportSchema = false)`（`SpoofingDatabase.kt:8-12`）。
- 没有 `room.schemaLocation`（`core/data/build.gradle.kts:16-28` 无 KSP 参数）。
- 没有 schema JSON、没有 migration、没有 `MigrationTestHelper`。
- Room 2.8 的 KMP 特性未用；当前是经典 `RoomDatabase` + KSP。
- `SpoofingDatabase.create()` 没有 `fallbackToDestructiveMigration`（`SpoofingDatabase.kt:16-23`），这是对的；但也意味着 schema 变更必须写 migration。

### 5.2 DAO / entity 语义

`FavoriteLocationEntity`（`FavoriteLocationEntity.kt:6-13`）：

- `id` 自增主键。
- `name` 无唯一约束/长度约束。
- `createdAt` 默认 `System.currentTimeMillis()`，无索引。
- `coordType` 存字符串。

`FavoriteDao`（`FavoriteDao.kt:10-19`）：

- `ORDER BY createdAt DESC`（`:11`）：同一毫秒或用户改系统时间时排序不确定。
- `@Insert(onConflict = REPLACE)`（`:14`）：没有唯一索引，REPLACE 实际就是普通 insert；不会去重。
- `deleteById`（`:17-18`）：没有撤销。

`FavoriteRepository`：

- `observeAll()` 在 `Flow.map` 中解析 `CoordType`（`FavoriteRepository.kt:14-27`）。
- `parseCoordType` 对未知字符串回退 WGS84（`:54-55`），避免单个坏行终止流；这是对的（数据库 canonical datum 是 WGS84）。
- `save` 不校验 name/坐标范围（`:29-38`）。

### 5.3 收藏功能缺口

- 只支持单点收藏；路线收藏被 UI 禁用（`MainScreen.kt:172` 的 `canSave = mode == STATIC && selectedPoint != null`）。
- `MainViewModel.saveFavorite` 的 route guard（`MainViewModel.kt:95-99`）在 UI 上不可达（按钮 disabled），但代码路径仍在。
- 无法保存"当前运行位置"。
- 快速双击 Save 会并发插入（`viewModelScope.launch` 每次调用一个协程，`:105-116`），没有 debounce/幂等。
- 保存失败后输入名已被清空：`MainScreen.kt:173-176` 在调用 `onSave` 后立即 `favoriteName = ""`，不管成功与否。
- 没有删除撤销、重命名、搜索、去重、保存时间显示。

### 5.4 备份

- `allowBackup="true"`（`AndroidManifest.xml:34`）+ `fullBackupContent`（`:36`）+ `dataExtractionRules`（`:35`）。
- 备份规则只 include `database` domain（`backup_rules.xml:8-10`、`data_extraction_rules.xml:4-12`），覆盖 Room 的 `-wal`/`-shm`。
- README 已披露收藏坐标可能进入云备份/换机迁移（`README.md:49-52`）。
- 没有应用内开关/加密；D4 决定本轮保持现状 + 披露，Phase 4 再决策。
- AMap consent 的 `amap_privacy` SharedPreferences 不在备份范围（`AMapPrivacy.kt:14-37`），这是好的合规属性。

### 5.5 `observeFavorites` 的永久终止

`MainViewModel.observeFavorites()`（`MainViewModel.kt:262-278`）：

```text
try {
  favoriteRepository.observeAll().collect { ... }
} catch (Exception) {
  message = "读取收藏失败"
}
```

`try/catch` 包在 `collect` 外面。一次 Room 异常（例如 schema/IO 错误）会永久终止收藏流，UI 保持旧数据，只显示一次瞬时 snackbar，没有重试。Phase 1 P1.11 要求改成 backoff 重试。

### 5.6 进程死亡与 UI 状态

- `MainViewModel` 没有 `SavedStateHandle`（`MainViewModel.kt:34-42`）。
- `MainUiState` 的 mode/selectedPoint/routeWaypoints/travelSpeed/endBehavior 都不持久化。
- `rememberSaveable` 只覆盖 `favoriteName`（`MainScreen.kt:67`）和地图 Dialog 可见性（`MapCard.kt:44`）。
- 运行中的服务保活；进程死亡后服务不 sticky，UI 重建为空状态（R22）。

### 5.7 `MockStatus.Error` 混淆"仍在失败"与"已终止"

- `MockStatus.Error(message)`（`Models.kt:33`）同时用于：
  - 单次 push 失败（runner 继续跑，`MockSessionRunner.kt:130-135`）；
  - 启动失败/source 失败/服务失败（会话结束）。
- `MainViewModel` 在 Error 时保留计时器（`:254`），`MainScreen` 的 `canStop = status !is Idle`（`:76`），所以终止后 Stop 仍永久可点；用户也可能误点 Start（`canStartSession` 不排除 Error）。
- 审计：terminal/degraded 分离是 UI retry/dismiss 的前置，属于 Phase 1。

### 5.8 `handleStop` 与 teardown 的竞态

见 `03` 第 3.5 节。数据层视角：`finishSession()` 立即 `stopSelf`，但 `FavoriteRepository`/UI 不受影响；风险集中在 provider 清理，不在 Room。

---

## 6. 产品机会矩阵（Product Opportunity Matrix）

价值/成本来自 Explore C；"阶段"按审计修正后的路线图。

| 优先级 | 机会 | 价值 | 成本 | 阶段 | 审计备注 |
|---:|---|---|---|---|---|
| 1 | 坐标输入/粘贴 + `geo:` 分享链接 + 中心 pin 选点 | 高 | S | 1 (C1) | 无地图设备唯一可用路径；必须 datum-explicit，不能复用 GCJ02 入口 |
| 2 | 地图显示实时模拟位置、相机跟随、距离/ETA、旋转后保留相机 | 高 | M | 2 | 与 P2.3 地图单实例/keyed diff 一起 |
| 3 | 路线收藏 + 保存"当前运行位置" | 高 | M | 4 | 依赖 Room v1 schema 决策/migration |
| 4 | 会话正确性包：计时器重置、Starting、路线预校验、错误卡 Retry/Dismiss | 高 | S-M | 1 | 与 R4/R7/R8 同源 |
| 5 | 可访问性/适配包：地图替代输入、语义/heading、320dp 英文、同意按钮、夜间地图 | 高 | S-M | 3 | 本轮只记录目标，不修 |
| 6 | 收藏 UX：删除撤销、重命名、搜索、去重、保存时间 | 中高 | S-M | 4 | 依赖 schema |
| 7 | 持久化 last spec + 会话历史 + 磁贴恢复 | 中 | M | 4 | 历史需要 Room v3 + 保留规则 |
| 8 | GPX/KML 导入导出（SAF、防御性 XML 解析） | 高（进阶） | M-L | 4 | 审计：比地理编码价值更高，且不需要第二套地图栈 |
| 9 | 地址/逆地理搜索 | 高 | M | 4 | 依赖中国/全球决策；全球需要另一 provider |
| 10 | 道路贴合/路线规划（而非直线段） | 高真实感 | L | 4 | 与"真实感 vs 确定性"决策绑定 |
| 11 | Onboarding + 永久拒绝恢复 + 通知拒绝说明 + 电池"建议"而非必需 | 中 | S | 3/4 | 通知拒绝部分可提前到 Phase 1 |
| 12 | 真实感控制：jitter、accuracy、altitude、自定义速度、单位 | 中 | S-M | 2/4 | 与确定性测试冲突；GPS 导入优先 |
| 13 | MapLibre/坐标模式替代 AMap | 中 | L | 4+ | 审计：等 16KB/全球决策后再评估 |
| 14 | Profiles、launcher shortcuts、API 26-32 语言选择 | 低中 | S-M | 4 | 低优先 |

---

## 7. 新奇发现 / 可复用线索

1. **坐标系纪律是本项目最好的不变量**：`GeoPoint` 带 `CoordType`（`Models.kt:17-21`）、边界转换（`AMapPicker.kt:361-364`、`MainViewModel.kt:57-68`）、`StaticMovementSource`/`Route` 校验（`StaticMovementSource.kt:16-22`、`Route.kt:83-92`）。
   - 缺口：UI 没有显示 datum 标签；地图是 GCJ02、Hero 是 WGS84，复制到 AMap 会偏几百米。
2. **AMap 主 ABI 判断是刻意为之**：不是"任意 ABI 匹配"，因为 x86 翻译层会列 arm64 但后续在 handler 线程失败（`AMapPicker.kt:86-93` 注释）。`runCatching` 捕获 `UnsatisfiedLinkError`（Error 而非 Exception）也正确（`:187-205`）。
3. **避免 `map.clear()` 保相机/折线的直觉正确**（`AMapPicker.kt:215-217`）；可扩展到 keyed marker diff + `CameraPosition` 保存。
4. **系统级 provider 清理用"enable 前记录 + NonCancellable + sessionId/join"是正确形状**；可考虑冷启动清理残留 GPS/NETWORK test provider（Phase 1 A1）。
5. **Compose 技巧**：
   - `graphicsLayer {}` 内读动画状态避免重组（`StatusPill.kt:111` 已经这样做）。
   - `rememberUpdatedState` 保持一次性注册的 AMap listener 最新（`AMapPicker.kt:185`）。
   - lifecycle observer 在 owner 已 RESUMED 时补调 `onResume`（`AMapPicker.kt:266-269`）。
6. **几何/域模型**：`Double` 的 NaN clamp（`Route.kt:40`）、负距离处理、antimeridian-safe haversine、slerp、球面数学保持 JVM 可测（`GeoMath.kt`、`Route.kt`）。
7. **错误文案通过注入 `MockErrorStrings`** 让 core 不依赖 Android 资源（`MockErrorStrings.kt:1-27`、`AppModule.kt:16-35`）；同样思路可用于 ViewModel 消息，避免在 ViewModel 里 `Context.getString`。
8. **参考方向**：
   - Compose strong-skipping/stability reports、`@Immutable` + `kotlinx.collections.immutable`。
   - Room `exportSchema`/`MigrationTestHelper`。
   - WCAG 2.2 SC 1.4.11（非文本对比度）/2.5.8（目标尺寸）。
   - AMap `AMAP_MAP_TYPE_NIGHT`/camera API。
   - Roborazzi/Paparazzi 截图测试。
   - Macrobenchmark + baseline profiles。
9. **`formatPoint` 用 `Locale.ROOT` + `%.5f`**（`Format.kt:5-7`），避免德语等 locale 的逗号分隔；缩略坐标大约 1.1m 精度（5 位小数）。这是 Phase 0 最合适的首个 app 测试。
10. **`FavoriteSection` 的 `formatPoint` 对每个收藏行都跑**（`FavoriteSection.kt:159`），即使收藏没变；下沉 `favoriteName` + 虚拟化后应只对可见行跑。

---

## 8. 开放问题

1. Room v1 是否已经分发？`versionCode=1` + 只有 `pre-refactor` tag 暗示没有；D3 已按"已分发/不确定"处理。
2. 产品是刻意中国优先（默认中文 + AMap），还是希望英文默认/非 AMap 坐标模式？D2 锁定本轮中国优先。
3. 运行中是否允许修改速度/路线/到达行为？当前 UI 接受编辑但 spec 在启动时已捕获，修改只影响下一次（D5 保守默认）。
4. 进程死亡后是否要恢复会话？service 是 `START_NOT_STICKY`，但持久化 last spec 能让磁贴重启后仍可用（D5/Phase 4）。
5. 团队要"真实感"（道路贴合、jitter、accuracy/altitude）还是"确定性开发测试"？这会改变路线规划 vs GPX 导入的优先级。
6. 云备份坐标是刻意产品决策吗？README 披露但 UI 没有（D4）。
7. 320dp/1.3x 验收覆盖哪些设备和语言？当前预览/CI 一个都没覆盖（D6/Phase 3）。
8. 路线收藏用独立 entity 还是 JSON column？依赖 Room v1 决策和 migration 策略。
9. 地图全屏 Dialog 打开时，内联卡是否应显示静态截图/占位，而不是继续组合？需要测量重建成本和闪烁。
10. `StatusHero` 的坐标是否应显示 datum 标签（WGS84/GCJ02）？在复制/外部粘贴场景下这是正确性问题。

---

## 9. 置信度说明

- UI 结构、组件、状态字段、性能证据链、数据层 schema/DAO、a11y 缺口：`verified`（源码复核）。
- 320dp/1.3x 的具体溢出、对比度数值、50 收藏/100 点的成本、RTL 显示：`inferred`，需要截图/设备/axe/Compose 测试。
- 产品价值/成本是 Explore C 的判断，不是用户研究。
- 任何"修复后 320dp 通过"的声明，必须有 Phase 3 的截图矩阵（320/360/1.3x/英文/dark/RTL）和可访问性检查。
