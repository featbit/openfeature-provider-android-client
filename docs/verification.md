# 实现与验证记录

日期：2026-10-09。Provider 已实现，开发版本为 `0.1.0-SNAPSHOT`，未正式发布。本记录只描述本仓库执行的验证，不继承核心 SDK 的测试结果。

## 实现范围

- `FeatBitProvider`、嵌套 Builder、`FeatBitOptionsFactory` 和 `IdentityPolicy`。
- 六类评估、具名/持久化匿名上下文映射、严格类型错误、JSON 转换、Track。
- 单客户端所有权、异步创建、身份采用回执与 readiness 分离、旧请求隔离、同步读取租约和幂等关闭。
- 有界扩展命令与等待者、包含排队的预算、模式失败后的状态恢复、Operation 保留结果与注册失败探测。
- 共享两条核心订阅；每个 observe collector 有独立的合并槽，无订阅人数硬上限。
- 独立 Java/Kotlin 验收应用、本地 Maven 发布、可复跑脚本和 CI 工作流。

生产代码只使用 FeatBit 的公共 API；未修改核心 SDK 或 OpenFeature 依赖。测试通过内部 ClientFactory 注入 fake，测试中的反射仅构造不可公开实例化的 IdentityReceipt，不进入 AAR。

架构图中的 ClientOwner、AdmissionGate、TrackingAdapter 对应 `ProviderRuntime` 内的职责，EvaluationAdapter 的纯转换对应 `ValueMapper`；没有为每个职责额外建立接口。控制采用短 monitor、串行 submission mutex 和独立 owner 协程。扩展命令按接受顺序串联；新上下文淘汰尚未提交的旧协调工作，已提交采用的回执继续由 owner 接收。Factory 任务提交时冻结创建身份。

## 固定构建组合

| 项目 | 本次组合 |
| --- | --- |
| Provider 坐标 | `co.featbit:featbit-openfeature-provider-android:0.1.0-SNAPSHOT` |
| FeatBit | `co.featbit:featbit-client-android:0.2.0`，Maven Central |
| OpenFeature | `dev.openfeature:kotlin-sdk:0.9.0`，Maven Central 的 Android variant |
| Kotlin / AGP / Gradle | 2.2.10 / 8.10.1 / 8.11.1 |
| JDK / JVM target | JDK 17 / JVM 11 |
| Android | compileSdk 34，minSdk 21；设备验证 API 34 |
| 协程 | kotlinx-coroutines 1.8.1 |

公共依赖以 `api` 暴露。消费者不依赖 provider 源码模块，也没有 composite build 或 dependency substitution；只从 `build/repository` 读取实际 AAR，核心与 OpenFeature 从 Central 解析。

## 单元测试与集成覆盖

40 项测试通过，包含 3 项专门断言上游已知缺陷的复现测试。复现测试通过表示缺陷仍存在，不表示 G02 通过。

| 测试类 | 数量 | 主要覆盖 |
| --- | ---: | --- |
| MappingTest | 6 | key/name/属性、匿名限制、保留字段、Null/缺失、整数属性精度、类型错误、递归 JSON |
| RuntimeTest | 25 | 六类读取、Double 到整数、采用/就绪分离、具名与匿名竞争、非法上下文、旧回调、取消、创建超时、factory 内切换与关闭、迟到资源、读租约、扩展容量/排队/超时、模式失败、Track 舍入与重入、Stale/Fatal |
| EventAndOperationTest | 5 | 70 个 collector、迟到订阅、慢订阅者超过 1024 key 后 All 失效、状态/变化分离、关闭回收、observe 竞争、单次完成、注册失败探测 |
| OpenFeatureIntegrationTest | 4 | 实际 API Client 的默认值/error hook，以及初始化、上下文返回和 provider 替换的状态覆盖复现 |

竞态使用虚拟时间、延迟 Operation 和重入屏障，不以 sleep 猜时序。Fake 测试没有验证网络、存储或服务端事件协议。

## Android 制品与设备

已通过：

- Spotless、40 项单元测试、Android lint（无错误；compileSdk 更新提示不影响构建）。
- Release AAR、sources JAR、POM 和 Gradle metadata 的本地发布。
- 独立 Java 与 Kotlin 工程的 Debug、Release/R8 构建。
- Pixel_3a_API_34 / `emulator-5554` 上 Java/Kotlin × Debug/Release 四组运行。

设备验收使用真实核心 TestData 和实际 provider AAR。Kotlin 验证六类评估、类型错误、缺失 flag 默认值、A→B→匿名切换和关闭结果；Java 验证 Builder/SAM、Continuation 初始化、评估和关闭。测试源关闭分析事件，故这些结果不证明真实服务端事件正确。

执行入口：

```powershell
python tools/acceptance.py --device emulator-5554
```

本次最终运行记录：`build/acceptance/20261009-151841-d5e39c19/`。Release AAR SHA-256：`eb65e8cb52b16146367b025428cf4e5c3c303f65e7b59835a7292075d186c8bd`。之后仅统一文本换行及补充文档，并再次通过 Spotless 和文档链接检查。

每次运行保存到 `build/acceptance/<timestamp-id>/`，包括 provider、consumer、安装、启动、设备日志。设备日志必须匹配本次唯一 runId 才判成功；不会清空全设备 logcat。构建产物与日志被 gitignore 排除。

## 上游 G02 的实测结果

OpenFeature 0.9.0 的原始 Maven 依赖存在以下问题：

1. provider 初始化尚未返回时发出 Stale 或 Fatal，API 已收到该状态；随后初始化正常返回，API 无条件覆盖为 Ready。
2. onContextSet 尚未返回时发出 Stale，API 已收到；随后正常返回，再被覆盖为 Ready。
3. 旧 provider 的初始化尚未返回时替换成初始化失败的新 provider；旧初始化返回后把当前 Error 覆盖为 Ready。

对应测试是 `OpenFeatureIntegrationTest` 的三个 `upstream090...` 方法。测试使用独立 API instance，不修改依赖、全局状态或生产实现，也不使用固定延时绕过问题。Provider 自身的准入校验仍有效，但不能保证上游 `getStatus()` 在这些窗口准确。

## 未完成的发布验证

- G02：需要上游状态仲裁修正及正式依赖版本，再将复现改为正确行为回归测试。
- G03：完整 A01–A15/R01–R24 是验收目标。本次覆盖上述具体场景，尚未穷举所有排列，例如所有 create 提交边界、清缓存与切换的全部组合、日志重入组合，以及 65+ 订阅者同时通过真实 API 注册的组合。
- G04：尚未运行 API 21 设备、真机或系统性的内存/主线程 I/O 检查；API 34 模拟器结果不能替代这些项目。
- G05：尚未验证真实服务器冷启动、缓存/Bootstrap 恢复和分析事件落库归属。
- G06：已具备开发文档和本地产物元数据；尚未配置远端签名/上传、发布文档 JAR、全部传递依赖锁定及公开仓库验证。新增 CI 文件尚未在 GitHub 执行。

因此本次交付是可编译、可运行并有测试的开发实现，不是正式发布验收完成。
