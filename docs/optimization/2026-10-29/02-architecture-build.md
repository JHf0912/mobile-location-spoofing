# 02 - 架构、构建与测试（Architecture / Build / Test）

**日期**: 2026-10-29
**来源**: Explore A，经独立审计修订
**范围**: 模块边界、Gradle/AGP/依赖、CI、release 路径、测试体系
**方法**: 只读源码/配置复核；测试计数用 grep 重新核对；构建体积、缓存内 AGP 元数据、Pixel_8 结果来自 Explore 报告，本轮未重跑
**证据图例**: 见 `00-index.md` 第 3 节

> **快照说明**：归档期间 Phase 0 已开始改动工作树，并在归档后完成测试部分（`SpoofingDatabase.exportSchema=true`、`core/data/schemas/.../1.json`、`app/src/test/` 28 个测试、`lint.xml`、`settings.gradle.kts` 注释、preview 固定时钟）。本文的"当前"描述以审计时为准；第 4.1 节同时列出 Phase 0 后当前状态，live 状态以代码为准。

---

## 1. 架构地图与模块边界

### 1.1 模块图

实际依赖图见 `01-project-map.md` 第 1.1 节。结论：

- `:app` 直接依赖四个 core 模块（`app/build.gradle.kts:98-101`）。
- `:core:location` 同时依赖 model 与 geo（`core/location/build.gradle.kts:12-13`）。
- `:core:data` 只依赖 model（`core/data/build.gradle.kts:17`）。
- `:core:geo` 只依赖 model（`core/geo/build.gradle.kts:6`）。
- `:core:model` 无项目依赖（`core/model/build.gradle.kts:1-3`）。

`settings.gradle.kts:21-23` 的注释写 `(location|data) -> geo -> model`，与 `core/data` 的实际依赖不符。P0.4 记录为待修正的文档漂移；本轮不改构建文件。

### 1.2 纯 JVM 边界（项目最强结构保证）

`core/model` 与 `core/geo` 只应用 `mobilespoofing.kotlin.jvm`：

- `core/model/build.gradle.kts:1-3`
- `core/geo/build.gradle.kts:1-3`

该 convention plugin（`KotlinJvmConventionPlugin.kt:11-45`）只应用 `org.jetbrains.kotlin.jvm`，不引入 Android 插件，因此 classpath 上没有 `android.jar`。任何 `import android.*` 都会编译失败。

代价与收益：

- 收益：`GeoMath`、`Route`、`CoordinateTransformer`、`MovementSpec` 等可以用普通 JUnit + 虚拟时间测试；`core:geo` 47 个测试全部是纯 JVM。
- 代价：`core:location` 无法完全纯 JVM 化，因为 `LocationManagerMockEngine`、`FusedMockLocationEngine`、`MockPermissionChecker`、`LocationExt` 都直接依赖 Android 类型。

### 1.3 边界泄漏（按严重度）

| # | 泄漏 | 证据 | 影响 | 置信度 |
|---|---|---|---|---|
| 1 | `MainViewModel` 绑定 Android `Context` | `MainViewModel.kt:34-42` 注入 `@ApplicationContext Context`；`:211-224` 用 `PowerManager`；`:230-238` 用 `ContextCompat`；`:93-117` 用 `R.string.*` | ViewModel 无法用纯 JUnit 测试；fakes 需要 Robolectric 或先重构 | `verified` |
| 2 | `app/domain` 不是领域层 | `StartMockUseCase.kt:14-36` 直接用 `Context` + `startForegroundService`；`StopMockUseCase.kt:12-31` 直接用 `Context` + `startService` | 服务启动机制没有接口；UseCase 无法在 JVM 测试中替换 | `verified` |
| 3 | `core:location` 混合纯 Kotlin 与 Android | 纯：`MockSessionRunner`、`MockStatusStore`、`SessionConfigStore`、`CompositeMockLocationEngine`、`MockErrorStrings`、`ElapsedClock`；Android：`LocationManagerMockEngine`、`FusedMockLocationEngine`（在 `MockLocationEngine.kt:33-54`）、`MockPermissionChecker`、`LocationExt` | 无法把会话逻辑拆成纯 JVM `:core:session`；测试仍要 Android 插件 | `verified` |
| 4 | `core:data` 用 `api(room-runtime)` 暴露 Room | `core/data/build.gradle.kts:19-23`；`:app` 从不引用 `SpoofingDatabase`，只用 `FavoriteRepository`（`MainViewModel.kt:36`） | 所有消费者都拿到 Room 编译 classpath；模块边界变宽 | `verified` |
| 5 | `GeoPoint` 默认 GCJ02，`Fix` 文档要求 WGS84 | `Models.kt:17-21` 默认 `CoordType.GCJ02`；`Fix.kt:1-9` 声明 always WGS84；`LocationExt.kt:16-35` 不校验 | 未来新 MovementSource/导入路径可静默产生数百米偏差 | `verified` |
| 6 | `MockStatus.Error(message: String)` 把展示文案带进 model | `Models.kt:23-34`；`MockErrorStrings.kt:8-27` 只解决 runner 一侧 | core 模型持有本地化文本；error code/重试语义无法表达 | `verified` |
| 7 | `SessionConfigStore.clear()` 无调用者 | `SessionConfigStore.kt:32-34`；grep 无 `SessionConfigStore.clear` 调用 | 停止后 pending spec 残留；磁贴可重启旧位置（R5） | `verified` |
| 8 | 缺少接口 | `FavoriteRepository` 是 final class（`FavoriteRepository.kt:11-12`）；`MockPermissionChecker` 是 final class（`MockPermissionChecker.kt:17-18`）；`MockStatusStore` 是 final class（`MockStatusStore.kt:18-19`） | fakes 需要 Robolectric 或改接口；Phase 2 的 ViewModel 测试被阻塞 | `verified` |

### 1.4 Hilt 装配

- `LocationModule`：显式提供 `FusedLocationProviderClient`（`LocationModule.kt:24-28`）、组装 `CompositeMockLocationEngine(listOf(fused, locationManager))`（`:40-45`）、手工构造 `MockSessionRunner`（`:52-62`）。
- 为什么不用 `@IntoSet`：composite 同时是 `MockLocationEngine` 又消费一组 `MockLocationEngine`，会形成 Dagger 依赖环（`LocationModule.kt:30-39` 注释）。
- `DataModule` 在 `:core:data` 内部提供 `SpoofingDatabase`/`FavoriteDao`（`DataModule.kt:18-30`），避免 app 看到实现类。
- `AppModule` 提供 `MockErrorStrings` 的本地化实现（`AppModule.kt:16-35`），让 `:core:location` 不依赖 Android 资源。

架构结论：模块拆分的方向正确，但"纯 JVM"只在 model/geo 真正成立；`core:location` 是可拆但尚未拆的混合层；`app/domain` 是 Android 适配层而不是领域层。**在 app 测试和 DI seam 之前拆模块，是审计明确反对的顺序**（见 `05-audit.md` 第 10 节）。

---

## 2. 构建与工具链

### 2.1 版本矩阵

| 组件 | 版本 | 证据 | 备注 |
|---|---|---|---|
| Gradle wrapper | 9.3.1 | `gradle-wrapper.properties:10` | 从腾讯镜像下载；checksum 固定 |
| AGP | 9.1.0 | `libs.versions.toml:24` | Android Studio 2025.3 锁定的最高版本 |
| Kotlin | 2.3.10 | `libs.versions.toml:30-31` | 只用于 Compose compiler plugin / 纯 JVM 模块；AGP 9 自带 KGP |
| KSP | 2.3.12 | `libs.versions.toml:31` | Room 编译器 |
| Hilt | 2.60.1 | `libs.versions.toml:25` | |
| Room | 2.8.5 | `libs.versions.toml:39` | 经典 `RoomDatabase` + KSP，不是 KMP |
| Compose BOM | 2026.09.00 | `libs.versions.toml:37` | Compose 1.12.x |
| compileSdk / targetSdk | 37 / 37 | `libs.versions.toml:17-19` | catalog 存整数；`toInt()` 解析 |
| minSdk | 26 | `libs.versions.toml:18` | |
| Java | 17 | `AndroidApplicationConventionPlugin.kt:36-39`、`KotlinJvmConventionPlugin.kt:23-29` | toolchain 17 |
| AMap | 10.0.600 | `libs.versions.toml:45` | 只有 arm64-v8a/armeabi-v7a native |

### 2.2 AGP 与 Gradle 是单一升级单元

`libs.versions.toml:21-24` 注释：AGP 9.1.0 的 `GRADLE_LATEST_VERSION` 是 9.3.1（Explore 从缓存 `com.android.tools:common:32.1.0` 验证，`reported`）。这意味着：

- AGP 与 wrapper 必须同步升级；Dependabot 目前没有把 `gradle-wrapper.properties` 与 AGP 分组（`.github/dependabot.yml:3-19` 只分组 Compose 和 Kotlin 工具链）。
- 若 Dependabot 单独提 AGP 9.2.x 而 wrapper 仍 9.3.1，构建会因 minimum Gradle 不满足而失败。这是一个可复现的升级陷阱，但本轮未触发。
- 当前锁定组合是经过验证的；不要在本轮追 AGP 9.2/Gradle 9.4（`06` 禁做清单）。

### 2.3 compileSdk 37 是硬要求

Explore 报告：Compose UI 1.12.1 的 AAR metadata 要求 `minCompileSdk=37`、`minAGP=9.1.0`（`reported`）。`gradle.properties:12-21` 也记录了同样的理由：

- compileSdk 37 是 Compose BOM 2026.09.00 的强制要求，不是可选升级。
- AGP 9.1.0 官方只测到 compileSdk 36.1，因此有警告；`android.suppressUnsupportedCompileSdk=37.0` 是刻意抑制（`gradle.properties:21`）。
- `libs.versions.toml:15-16` 说明 SDK levels 必须写成整数 `37`，`"37.0"` 会让 `toInt()` 抛出。

### 2.4 Kotlin stdlib 版本不一致（未解决）

Explore 报告：解析后的 `kotlin-stdlib` 是 2.3.21，而 catalog pin 2.3.10。可能是运行时解析（AGP 自带 KGP 的传递依赖）而不是编译器不一致。`libs.versions.toml:26-30` 的注释说 "must match the KGP that AGP bundles"，但没有说明如何验证。审计把这条列为未复现结论，不应在路线图中当作已确认问题。

### 2.5 配置缓存、并行与构建浪费

`gradle.properties:3-7`：

```text
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=2g ...
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

观察：

- `org.gradle.tooling.parallel=true`（`gradle.properties:25-26`）是 Gradle 9.4+ 属性；当前 9.3.1 上无效。审计判定为 "无效，删除或标注未来启用"。
- CI 有三次独立 Gradle 调用：`ci.yml:41`（`verify`）、`:46`（`lint`）、`:49`（`:app:assembleRelease`）。每次都会重新配置/可能重算缓存。Explore 认为是优化机会，审计降级为 "优化不是缺陷"。
- `build-logic` 没有自己的 `gradle.properties`（`build-logic/` 目录下只有 `convention/` 和 `settings.gradle.kts`）。included build 是否继承根 `gradle.properties` 是开放问题；至少 `org.gradle.jvmargs` 的 daemon 设置不会自动作用于 included build 的独立 daemon（需要验证）。
- `AndroidLibraryConventionPlugin.kt:44-47` 明确不打开 `unitTests.isIncludeAndroidResources`，因为 AGP 9 会在没有测试的模块上因 `failOnNoDiscoveredTests` 失败（该注释针对 library convention）。审计时 `:app` 是 application 模块且没有 test source set，其 `testDebugUnitTest` 是 no-op；Phase 0 后 `:app` 已有 `app/src/test` 的 28 个测试，`testDebugUnitTest` 会运行它们（见 4.1 与 `07-test-inventory.md`）。

### 2.6 Toolchain / bootstrap 耦合

- 纯 JVM convention 要求 JDK 17 toolchain（`KotlinJvmConventionPlugin.kt:23-29`），但 `settings.gradle.kts` 没有 foojay resolver。CI 只装 JDK 21（`ci.yml:29-33`），本地 `JAVA_HOME=24`。
- 被 gitignore 的 `gradle/gradle-daemon-jvm.properties` 仍存在于本地，pin `toolchainVersion=21` + foojay；它可能让本地 JDK 24 被下载的 21 覆盖。该文件已被忽略（`.gitignore` 末尾），但"被忽略"不等于"无效果"。
- 这是环境问题，不是应用代码问题；`reported` / `inferred`。Phase 0 不处理，除非构建实际失败。

### 2.7 依赖治理

- 版本 catalog 通过 `from(files("../gradle/libs.versions.toml"))` 在 `build-logic/settings.gradle.kts` 共享（Explore 报告）。
- 没有 dependency locking / verification metadata（`gradle/verification-metadata.xml` 不存在）。
- wrapper checksum 有 pin（`gradle-wrapper.properties:11`），但 CI 的 `gradle/actions/wrapper-validation@v4`（`ci.yml:26-27`）只校验 wrapper JAR，不校验 distribution URL；腾讯镜像的 distribution 是否与 checksum 匹配，只在下载时由 Gradle 校验。
- CI 从腾讯镜像下载 Gradle（`gradle-wrapper.properties:10`），GitHub runner 上的可用性/延迟未验证。

### 2.8 Windows 构建

Explore 报告：换行处理正确；剩余风险是 daemon-JVM/foojay、toolchain 17、路径转义、腾讯镜像延迟、没有 Windows CI。本轮归档在 Windows 11 上完成，但没有运行 Gradle。

---

## 3. CI 与 Release 路径

### 3.1 CI 实际做什么

`.github/workflows/ci.yml`：

| 步骤 | 行 | 内容 | 不做什么 |
|---|---|---|---|
| checkout | `:21-22` | 拉代码 | |
| wrapper validation | `:26-27` | 校验 wrapper JAR | 不校验 distribution URL |
| JDK | `:29-33` | Temurin 21 | 本地 toolchain 17 由 Gradle 解决 |
| setup-gradle | `:35-36` | 缓存/daemon | |
| Build and test | `:40-41` | `./gradlew verify --stacktrace` | 不跑 androidTest |
| Lint | `:45-46` | `./gradlew lint --stacktrace` | 不是 `lintVitalRelease`；不上传报告 |
| Assemble release | `:48-49` | `./gradlew :app:assembleRelease --stacktrace` | 不安装/不启动；无 keystore 时是未签名 APK |
| Upload test reports | `:51-57` | 只上传 `**/build/reports/tests/` | 不上传 lint/mapping/APK/sdkDependencies |

`verify` 任务在根 `build.gradle.kts:17-33` 定义：`:app:assembleDebug` + 每个模块的 `testDebugUnitTest`/`test`。它**不包含** `connectedDebugAndroidTest`、`lintVitalRelease`、`bundleRelease`。

### 3.2 Release 配置

- R8 + resource shrinking 开启：`app/build.gradle.kts:78-90`。
- ProGuard 只 keep `com.amap.api.**` 和 `com.autonavi.**`（`app/proguard-rules.pro`），没有 `com.loc`。AMap 的反射/asset 加载是否完整，只有 ARM 真机 release 能回答。
- signing 依赖本地 `keystore.properties`：`app/build.gradle.kts:37-40,64-75`。文件不存在时 release 仍配置成功，但产出未签名 APK。
- `versionCode=1`、`versionName=1.0.0`：`app/build.gradle.kts:53-56`。与 D3 的 "Room v1 已分发/不确定" 相互印证但不足以证明未分发。
- CI 不产出 mapping/APK artifact，也没有 `sdkDependencies` 上传；release 失败通常只能在本地发现。

### 3.3 APK 体积与 native

Explore 报告值（`reported`，本轮未重跑）：

- release APK 约 38 MB，debug 约 52 MB；其中约 33 MB 是 AMap native。
- debug 另一组测量为 54.3 MB：arm64 AMap `.so` 20.0 MB、armv7 13.4 MB。两组数字不一致，说明测量时机/构建类型不同；引用时写"约 38–54 MB，native 占大头"，不要写单一精确值。
- 没有 ABI split；AMap 只提供 arm64-v8a/armeabi-v7a。x86/x86_64 上 `libandroidx.graphics.path.so` 是 Compose 的，不是 AMap 的。
- 主 ABI 检查（`AMapPicker.kt:86-93`）让 x86 永远看到 placeholder；这对 Room/UI 测试是合适的，对地图测试不合适。

### 3.4 ART profile / baseline profile

Explore 报告（`reported`，审计要求不要引用未复现的精确条目数）：

- AGP 9 的 ART profile 管线已启用，但 merged profile 由库主导；`com/snake/mobilespoofing` 的条目为 0；R8 combined 只有 5 条混淆条目。
- 没有 app baseline profile、没有 Macrobenchmark。
- 结论：启动优化不能依赖当前 profile；应先测冷启动，再决定 curated `baseline-prof.txt` 或 `BaselineProfileRule`。不要引用 "4,478 条" 作为已确认事实（审计判定未复现）。

### 3.5 Lint

Explore 报告：18 warnings、0 errors；没有 `warningsAsErrors`、没有 `lintVitalRelease`、报告不上传；`NewerVersionAvailable` 每次联网，使 lint 非 hermetic。

审计补充：不要在 18 条 warning 分诊前全局开 `warningsAsErrors`；可以关闭 `NewerVersionAvailable` 使 CI hermetic（P0.5）。

### 3.6 keystore 配置缓存风险（hypothesis，需复现）

`app/build.gradle.kts:37-40` 在配置阶段用 `File.exists()` / `Properties.load()` 读取 `keystore.properties`，不像 AMap key 那样通过 `providers.fileContents(...)` 追踪（`app/build.gradle.kts:22-27`）。

Explore 认为这可能导致：

- 修改 keystore 不使配置缓存失效；
- 密码可能被序列化进项目本地配置缓存。

审计判定：Gradle 配置缓存通常会跟踪 `File.exists()`/`FileInputStream`，**很可能是误报，需要两次构建复现**。因此：

- 不要把它当"已证明的安全修复"宣传。
- P0.5 只做复现；确认是真问题再改。
- 即使要改，动机是"一致性/卫生"，不是已证明的漏洞。

---

## 4. 测试体系

### 4.1 测试盘点（审计时基线 + Phase 0 后当前）

审计时基线（本归档开始时的快照）：

| 模块 | JVM 测试 | 测试类 | 类型 | CI |
|---|---:|---|---|---|
| `:core:geo` | 47 | 5 | 纯 JVM | 是（`verify`） |
| `:core:location` | 15 | 3 | JVM + coroutines-test | 是 |
| `:core:data` | 2 | 1 | JVM（`parseCoordType`） | 是 |
| `:app` | 0 | 0 | 无 `src/test`/`src/androidTest` | `testDebugUnitTest` 是 no-op |
| **合计 JVM** | **64** | **9** | | |
| `:core:data` instrumented | 3 | 1 | `AndroidJUnit4` + Room in-memory/file | **否**；本地 Pixel_8 报告通过（0.56s，`reported`） |

Phase 0 后当前（2026-10-29；app 28）：

| 模块 | JVM 测试 | 测试类 | 类型 | CI |
|---|---:|---|---|---|
| `:core:geo` | 47 | 5 | 纯 JVM | 是 |
| `:core:location` | 15 | 3 | JVM + coroutines-test | 是 |
| `:core:data` | 2 | 1 | JVM | 是 |
| `:app` | **28** | **2** | 纯 JVM（`FormatTest` 10 + `MainUiStateTest` 18） | 是（`verify` -> `:app:testDebugUnitTest`） |
| **合计 JVM** | **92** | **11** | | |
| `:core:data` instrumented | 3 | 1 | `AndroidJUnit4` + Room | **否**；仍 local-only |

Phase 0 的 app 通过证据：`app/build/test-results/testDebugUnitTest/TEST-...FormatTest.xml`（tests=10, failures=0, errors=0）与 `TEST-...MainUiStateTest.xml`（tests=18, failures=0, errors=0）。计数命令与逐类明细见 `07-test-inventory.md`。

### 4.2 现有测试质量

好的部分（`verified` by source）：

- 手写 fake engine（`MockSessionRunnerTest.kt:32-78`、`CompositeMockLocationEngineTest.kt:23-60`），不依赖 mock 框架。
- 虚拟时间：`runTest` + `StandardTestDispatcher` + `advanceTimeBy`/`runCurrent`（`MockSessionRunnerTest.kt:80-298`）。
- 覆盖关键生命周期：取消 teardown（`:119-141`、`:245-270`）、supersede（`:206-243`）、单次 push 失败继续（`:143-160`）、启动失败（`:162-178`）、路线自然结束（`:180-204`）、深睡跳 tick（`:272-298`）。
- 几何测试覆盖 NaN/负距离/antimeridian/退化路线（`Route.kt:37-60`、`RouteTest.kt`、`GeoMathTest.kt`）。
- Room instrumented 测试覆盖 DAO 排序/删除、repository round-trip、file-backed 持久化（`SpoofingDatabaseTest.kt:40-104`）。

问题：

- fixtures 重复：每个测试重新构造 runner/store/engine。
- 主要断言计数（push 次数、startCount/stopCount）而不是 fix 内容/时间戳；`Running` 的坐标/时间语义没有直接断言。
- `LocationExt`、`isRunning`、`onFinished`、`SessionConfig` 未覆盖。
- 没有 coverage 工具；没有 Compose UI test、截图测试、benchmark。
- 审计时 `:app` 没有 test source set；Phase 0 后已有 `FormatTest`/`MainUiStateTest` 28 个纯 JVM 测试，但 `MainViewModel`、Service、Tile、`MockPermissionChecker`、两个真实引擎、DI、AMapPicker/AMapPrivacy、Compose/theme 仍未测。
- `ui-test-manifest` 只在 catalog 定义（`libs.versions.toml:71`），没有任何模块依赖它；`ui-test-junit4` 未定义/未使用；Phase 3 才需要。

### 4.3 务实测试金字塔（Explore A 建议，审计修订）

1. 保留纯 JVM 测试（geo/location/app 现有模式）。
2. 抽取 ViewModel 依赖接口，用 `runTest`/Turbine 测状态（Phase 2 前置，不是 Phase 0）。
3. Robolectric 覆盖 Service/Tile/`MockPermissionChecker`/`AMapPrivacy`（Phase 1）。
4. CI 跑 Room instrumented（GMD/ATD）（Phase 0 决策：独立非阻塞 job，或记录"仅本地"）。
5. Compose UI/截图测试（320dp/1.3x/dark）（Phase 3）。
6. 可选 Macrobenchmark（Phase 2/4）。

审计修正：Phase 2 的 Compose 测试验收与 Phase 3 才加测试依赖存在矛盾；测试依赖必须提前到 Phase 2 开始前（`05-audit.md` 第 9 节）。

---

## 5. 排序后的机会（Ranked Opportunities）

排序来自 Explore A；"审计后"列是最终处置。阶段映射见 `06-roadmap.md`。

### P0（验证/解锁）

| 机会 | 证据 | 审计后 | 阶段 |
|---|---|---|---|
| 补 app/instrumentation 验证缺口 | 审计时 `:app` 无 test source set；CI 不跑 androidTest；Phase 0 已补 28 个纯 JVM 测试 | 保留；Phase 0 已完成 Format/MainUiState 测试；instrumentation 仍待 CI/设备 | 0 |
| 让 release 路径可运行时验证 | CI 只 `assembleRelease`（`ci.yml:48-49`），不安装/启动 | 保留；加 lintVital + 上传 mapping/APK；ARM 真机记录 | 0 |
| Room v1 schema 导出 | `SpoofingDatabase.kt:8-12` `exportSchema=false` | 保留；只导出 v1，不加空 migration 骨架 | 0 |

### P1（结构性）

| 机会 | 证据 | 审计后 | 阶段 |
|---|---|---|---|
| Room `exportSchema` + migration test | 同上 | 保留；下一次 schema 变更才加 `MigrationTestHelper` | 0/4 |
| 移除/论证 Material Components | `app/build.gradle.kts:107`；`themes.xml` 用 `Theme.Material3.DayNight.NoActionBar` | **降级**：不是 Phase 0/1 优先项；移除前必须视觉验证 edge-to-edge/status bar/splash | 3+ |
| ViewModel/UseCase 去 Android 化 | `MainViewModel.kt:34-42`、`StartMockUseCase.kt:14-36` | 保留为 Phase 2 前置；先加接口/测试 seam，不先拆模块 | 2 |
| keystore 配置缓存 | `app/build.gradle.kts:37-40` | **先复现**；不作为安全修复宣传 | 0 |
| toolchain/bootstrap 耦合 | 无 foojay；本地 daemon JVM 文件 | 保留为环境/文档项，不阻塞 | 0/2 |
| 模块边界 + datum 强制 | `Models.kt:17-21`、`LocationExt.kt:16-35` | datum 校验收进 P1.11；模块拆分推迟到接口/测试之后 | 1/2 |
| CI artifacts/timeout | `ci.yml:51-57` 只传 test reports | 保留；P0.3 | 0 |
| lint 真正 gate | 无 `warningsAsErrors`/`lintVitalRelease` | 保留；先关闭 `NewerVersionAvailable`，不全局开 warningsAsErrors | 0 |

### P2（优化/治理）

| 机会 | 证据 | 审计后 | 阶段 |
|---|---|---|---|
| 合并 Gradle 调用/清理构建浪费 | `ci.yml:41,46,49` | 保留但降级；先做单次调用实验证明等价 | 2+ |
| 删除 dead catalog/deps | catalog 有 `ui-test-manifest` 但无模块使用，且无 `ui-test-junit4` | 保留为卫生项 | 0/2 |
| 共享 test fixtures + coverage | 重复 fixtures | 保留；Phase 2 后 | 2+ |
| app baseline profile + macrobenchmark | 当前 profile app 类 0 | **先测启动**，不要引用未复现条目数 | 2 |
| 依赖治理 | 无 locking/verification metadata | 保留 | 2+ |
| 文档漂移 | `settings.gradle.kts:21-23`、README、DESIGN | Phase 0 P0.4 | 0 |
| Windows CI | 无 Windows job | 低优先；先记录本地风险 | 4+ |

---

## 6. 新奇发现 / 可复用线索

以下条目来自 Explore A，保留原始证据级别。`reported` 表示本轮未重跑。

1. **AGP 9.1.0 的 minimum Gradle 正好是 9.3.1**（`reported`，缓存 `com.android.tools:common:32.1.0`）。AGP/wrapper 是单一升级单元；Dependabot 分组漏了它。
2. **compileSdk 37 是 Compose AAR metadata 的硬要求**（`reported`：`minCompileSdk=37`、`minAGP=9.1.0`），不只是警告；当前 target 37 的行为没有任何 CI 设备覆盖。
3. **ART-profile 管线已运行但完全由库驱动**；app 自己的类 0 条。可用 curated `baseline-prof.txt` 或 Macrobenchmark `BaselineProfileRule` 针对启动。审计要求先测再引用。
4. **Material Components 只用于 XML theme parent**（`themes.xml` 的 `Theme.Material3.DayNight.NoActionBar`），却带入 AppCompat/Fragment/ConstraintLayout/RecyclerView 并污染 baseline profile。移除需要平台 theme + edge-to-edge/status bar 视觉验证。
5. **Compose Preview Screenshot Testing**（`com.android.compose.screenshot`，实验性）可直接复用现有 preview，在 JVM/layoutlib 验证 320dp/1.3x/dark；Paparazzi/Roborazzi 是替代。
6. **Gradle Managed Devices/ATD** 比完整 emulator 更适合 Room 测试；CI 可在 Ubuntu KVM 跑 `aosp-atd`，不碰 AMap ABI。
7. **Room 2.8 的 KMP 特性未用**；当前是经典 `RoomDatabase` + KSP，`exportSchema=false` 且无 `room.schemaLocation`，没有 schema JSON/`MigrationTestHelper` 路径。
8. **Hilt test 基础设施完全缺失**：没有 `hilt-android-testing`、`HiltTestApplication`、`@HiltAndroidTest`、custom runner。
9. **版本 catalog 通过 `from(files("../gradle/libs.versions.toml"))` 共享**，干净；但 SDK levels 存成 catalog versions 并用 `toInt()` 解析，`"37.0"` 会抛（`ProjectExtensions.kt:13-14`）。
10. **`gradle-daemon-jvm.properties` 是被 ignore 的本地文件但有真实效果**：`toolchainVersion=21` + foojay，可能让本地 JDK 24 被 21 下载覆盖。
11. **`NewerVersionAvailable` 使 lint 非 hermetic**；Dependabot 已负责更新，可关闭该 detector 或离线跑 lint。
12. **`locales_config.xml` 可交给 AGP `androidResources.generateLocaleConfig` 从 `values-*` 生成**，避免漂移。
13. **AMap packaging 诚实但脆弱**：只有 armeabi-v7a/arm64-v8a；x86/x86_64 的 `libandroidx.graphics.path.so` 是 Compose 的；主 ABI 检查让 x86 永远看到 placeholder。
14. **APK 体积由 native 主导**（约 33 MB AMap）；ABI split/App Bundle 主要改善分发；移除 Material/AppCompat 主要赢在 method count/startup/profile。

---

## 7. 开放问题

1. AGP 9.1.0 内置 KGP 到底是不是 2.3.10？resolved stdlib 2.3.21 是否是更新编译器的证据？用 `buildEnvironment`/AGP metadata 确认。
2. Dependabot 会不会更新 `gradle-wrapper.properties`？如果不会，未分组的 AGP PR 会失败。
3. 配置缓存是否真的把 signing 密码序列化进 `.gradle/configuration-cache`？需要真实 keystore + 两次构建复现。
4. 移除 `com.google.android.material` 对 `enableEdgeToEdge`、状态栏对比度、启动/splash theme 是否安全？必须视觉验证。
5. AGP 9.1 的 R8 full mode + shrinkResources 是否保留 AMap 的反射/asset 加载？只有 ARM 真机 release 能答。
6. included `build-logic` 是否继承根 `gradle.properties`？
7. `android.suppressUnsupportedCompileSdk=37.0` 是 AGP 期望的值，还是应为 `37`？
8. `SessionConfigStore.clear()` 是刻意不调用，还是应在 stop 时清 pending spec 防陈旧重启？
9. `Fix` 是否应自己强制 WGS84，还是保持 model 无依赖的"文档约束"？
10. instrumentation-in-CI 是政策/成本阻塞，还是单纯没实现？本地 Pixel_8 已证明 Room 测试可行。
11. `tooling.parallel` 在 9.3.1 是否完全 no-op？删除是否安全？
12. 三次 Gradle 调用合并后是否真的节省时间？需要 CI 实测。

---

## 8. 证据索引（关键文件）

| 主题 | 文件 | 关键行 |
|---|---|---|
| 模块依赖 | `app/build.gradle.kts` | 98-101 |
| 模块依赖 | `core/location/build.gradle.kts` | 12-13 |
| 模块依赖 | `core/data/build.gradle.kts` | 17, 21-23 |
| 纯 JVM | `core/model/build.gradle.kts`、`core/geo/build.gradle.kts` | 1-3 |
| 纯 JVM 插件 | `build-logic/.../KotlinJvmConventionPlugin.kt` | 11-45 |
| 版本 | `gradle/libs.versions.toml` | 17-19, 24-31, 37-45 |
| 属性 | `gradle.properties` | 3-7, 12-21, 25-26 |
| Wrapper | `gradle/wrapper/gradle-wrapper.properties` | 10-14 |
| CI | `.github/workflows/ci.yml` | 26-27, 40-49, 51-57 |
| `verify` | `build.gradle.kts` | 17-33 |
| Release | `app/build.gradle.kts` | 64-94 |
| ProGuard | `app/proguard-rules.pro` | 1-3 |
| ViewModel 泄漏 | `app/.../ui/MainViewModel.kt` | 34-42, 211-238 |
| UseCase 泄漏 | `app/.../domain/StartMockUseCase.kt`、`StopMockUseCase.kt` | 14-36 / 12-31 |
| 模型文案 | `core/model/.../Models.kt` | 23-34 |
| SessionConfigStore | `core/location/.../SessionConfigStore.kt` | 22-35 |
| 测试 | `core/location/src/test/.../MockSessionRunnerTest.kt` | 32-298 |
| 测试 | `core/data/src/androidTest/.../SpoofingDatabaseTest.kt` | 40-104 |

---

## 9. 置信度说明

- 模块依赖、边界泄漏、测试计数、CI 步骤、版本号、注释漂移：`verified`（本轮 read/grep）。
- APK 体积、AGP 缓存元数据、Compose AAR metadata、Pixel_8 结果、profile 条目数：`reported`（Explore），本轮未重跑。
- keystore 配置缓存风险、Kotlin stdlib 不一致、build-logic 继承：`hypothesis`/`inferred`，需要复现。
- 任何"移除 Material 后启动更快"、"合并 Gradle 调用更快"：`hypothesis`，必须测量。
