# 源码依据、差距与实施验收

## 1. 本次核对的基线

更新日期：2026-10-06。核心发布源码、发布后的文档提交和 Provider 验收分别记录。

| 来源 | 固定基线/证据 |
| --- | --- |
| FeatBit Android SDK | 正式依赖 `co.featbit:featbit-client-android:0.2.0`；tag `v0.2.0`，发布 commit `7210251fbe5e3f9f452ef15e370da265f1009702`；所需核心能力已发布 |
| 核心发布后文档 | commit `dc351c6aa58c39fa594968e17ee6a5a2bde9049b` 记录公开产物验证与安装说明；不改变发布标签或运行时源码 |
| OpenFeature Kotlin | 当前正式版及开发/问题复现基线为 release tag v0.9.0，commit `72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b`；正式依赖需通过 G02 |
| OpenFeature main | `8d727fbead4b8d8bc66dc271ad35de1ece603a8b`；与 v0.9.0 差异仅两个发布配置文件，本文引用的运行时/API 源码一致 |
| LaunchDarkly Java server provider | `2363198f45cbe7c06bec73bf1cf070a2281eb17a` |
| 文档 | [OpenFeature Kotlin SDK 文档](https://openfeature.dev/docs/reference/sdks/client/kotlin/)，2026-10-06 复核；接口细节以固定源码为准 |

FeatBit 0.2.0 的 G01 核心前置条件已满足：

- [正式 Release](https://github.com/featbit/featbit-android-client-sdk/releases/tag/v0.2.0) 与 [Maven Central 产物](https://repo.maven.apache.org/maven2/co/featbit/featbit-client-android/0.2.0/) 均已公开。
- [发布工作流](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489895116) 成功，Central 状态为 `PUBLISHED`；公开 AAR、POM、Gradle metadata、源码和文档的签名及校验值通过，并与工作流归档一致。
- 核心 155 项测试及 Java、Kotlin 1.9.24/1.9.25/2.2.10 独立产物矩阵通过；发布后以新依赖缓存从 Central 下载的 Java/Kotlin 消费项目也通过 Debug、Release/R8 构建、单元测试及 lint。该次公开下载验证没有重跑设备或真实服务检查。
- 详细证据保存在核心的[发布验证记录](https://github.com/featbit/featbit-android-client-sdk/blob/dc351c6aa58c39fa594968e17ee6a5a2bde9049b/docs/verification.md#maven-central-020-publication--2026-10-06)。

这些证据确认核心依赖可用，不等于 Provider 已通过测试。OpenFeature 的 G02 尚未通过；0.9.0 与上述 main 的状态仲裁实现相同，本项目已执行最小复现并确认缺陷，见[验证记录](./verification.md)。Provider 与两项依赖组合后的编译、运行时和 Android 兼容性仍须单独验收。

### OpenFeature 精确源码入口

- [FeatureProvider](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/FeatureProvider.kt)：六类评估、初始化、上下文变更、同步 shutdown、可选 track/observe。
- [OpenFeatureAPIInstance](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureAPIInstance.kt)：provider 监听、注册、上下文协调和状态错误处理。
- [OpenFeatureClient](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureClient.kt)：NotReady/Fatal 门禁、异常与 hooks、track 直通。
- [Value](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/Value.kt)、[ProviderEvaluation](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/ProviderEvaluation.kt)、[Provider events](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/events/OpenFeatureProviderEvents.kt)：实际值域和事件模型。

### FeatBit 精确源码入口

以下链接固定在 0.2.0 发布提交；相邻核心仓库后续的文档提交不改变这些运行时源码。

- [ClientContract](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/api/ClientContract.kt)：Operation、评估、identifyContext/identifyAnonymousContext、readiness、订阅与 close。
- [ClientOptions](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/api/ClientOptions.kt)、[User](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/api/User.kt)：配置与身份校验。
- [LocalClient](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/internal/LocalClient.kt)、[Values](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/internal/Values.kt)：类型转换、快照仲裁和评估事件发生点。

## 2. 如何借鉴 LaunchDarkly

核对了 [Provider.java](https://github.com/launchdarkly/openfeature-java-server/blob/2363198f45cbe7c06bec73bf1cf070a2281eb17a/src/main/java/com/launchdarkly/openfeature/serverprovider/Provider.java) 和 [EvaluationDetailConverter.java](https://github.com/launchdarkly/openfeature-java-server/blob/2363198f45cbe7c06bec73bf1cf070a2281eb17a/src/main/java/com/launchdarkly/openfeature/serverprovider/EvaluationDetailConverter.java)。

可借鉴其薄适配层、集中转换、复用原生配置和状态监听。它使用 Java server SDK，每次评估传 LDContext；移动端必须通过 identify 切换当前用户。它的公开构造接收 key/config，client 注入构造是包级可见；不能据此认为它提供公开的任意客户端接管。它暴露底层 client，且有更丰富的评估元数据和 Track 数据能力；本设计不复制这些接口。

## 3. 已确定的核心接入方式（2026-10-06 更新）

核心普通方法和 Detail 方法统一检查声明类型，Provider 复用 bool/string/number/jsonVariationDetail。保留 Double/FbValue 数值模型：Int/Long 从 Double 转换，允许截断和边界饱和，不承诺完整 Long 精度。类型和解析失败仍可能收集选中的远端 variation 事件；不扩展服务端错误事件字段。撤销新增 resolveDetail 和精确数值树的方案。

identifyContext、identifyAnonymousContext 直接属于 FeatBitClient，已包含在正式版 0.2.0；成功仅表示身份采用。详细数值、错误映射和事件契约见[核心 SDK 契约](./core-sdk-contract.md)。Provider 开发实现已建立，验收状态见[验证记录](./verification.md)。

其余接入边界保持：ClientOptions 使用 factory 创建；status 无身份 generation，由 Provider token + awaitReady 仲裁；Track 结构详情不发送；OpenFeature 初始化/协调状态提交仍需满足 G02。

## 4. 实施顺序（2026-10-09 已获实现授权）

1. 核心 G01 已完成，依赖固定为 0.2.0；不再安排新增核心接口或再次发布核心 SDK。
2. 先针对 OpenFeature 0.9.0 建立 R15 最小复现，覆盖初始化、上下文协调、provider 替换及失败状态提交；据此修正并验证上游状态仲裁。G02 在正式 Provider 发布前必须通过。
3. 已建立独立 Android library 工程，并单独记录 G02 阻塞；开发/复现暂固定 OpenFeature 0.9.0，核心固定 0.2.0。固定 AGP/Gradle/JDK；两项公共类型依赖通过 api 暴露，内部依赖用 implementation。开发基线不代表正式发布依赖已经验收。
4. 实现纯映射与 EvaluationAdapter，再实现拥有客户端的生命周期和事件桥；复用 TestData，无需复制测试数据源。保留 Double 数值边界，不恢复精确 Long 或失败零事件方案。
5. 选定包含状态修正且通过 G02 的正式 OpenFeature 版本，在该版本上重跑集成测试；完成独立 Kotlin/Java 消费者、Android 设备及服务端事件验收。核心的兼容性结果不替代这些检查。
6. 补集成文档、限制、迁移/版本兼容表及发布产物验证，全部适用闸门通过后再安排正式发布。

目录现已建立：provider 模块、consumer-tests/kotlin、consumer-tests/java、tools 与 docs。具体执行证据和未完成闸门见[验证记录](./verification.md)。

## 5. 验收矩阵

| 编号 | 必须验证的行为 |
| --- | --- |
| A01 | builder 无 I/O；Activity 不泄漏；factory 用户不一致在创建前失败；注册重复/关闭后注册行为确定 |
| A02 | 在线冷启动、缓存、Bootstrap、offline、TestData；只按真实 readiness 完成初始化；初始监听无漏窗 |
| A03 | factory 前后与 create 提交前后的上下文变化、create 晚到、初始化取消/超时、close 竞争；冻结创建身份，仅一个 owned client，最新身份就绪前不放行，晚到资源最终释放 |
| A04 | key/name、字符串空白、Null/缺失、全部属性类型、保留字段、属性顺序及匿名来回切换 |
| A05 | A→B→C 快速切换、同值重试、失败上下文、取消后继续同步；无旧值泄漏或错误事件归属 |
| A06 | 六类成功/缺失/错误类型/格式错误；错误返回精确 default，error hooks 被调用 |
| A07 | Int/Long 两端边界、2^53±1、Long.MAX_VALUE、科学计数、小数、溢出、NaN/Infinity；验证 Double 舍入、向零截断和越界饱和；非有限解析失败 |
| A08 | JSON 对象/数组/标量/Null、嵌套整数、无效 JSON、深度边界、含 Instant/Long 的 fallback 原样保留 |
| A09 | Ready/Stale/Error 恢复、终止、后台/离线暂停、初始化完成瞬间断线；迟到事件不覆盖当前身份 |
| A10 | 单 key/全量变更、慢消费者、取消、多次订阅及超过 64 个预先订阅者；不拒绝 OpenFeature 内部监听、不漏最终状态、不重复 SDK 订阅、全量失效可识别 |
| A11 | Track 名称/有限 Double 数值（允许精度损失、下溢；非有限/转换异常丢弃）/结构限制、身份门禁、去重/禁用/队列容量、flush 丢失结果；失败解析可产生选中远端 variation 事件，不伪造 fallback 或错误字段 |
| A12 | 模式成功/失败/超时后的实际状态恢复；扩展操作预算包含排队、提交前超时无副作用；shutdown 不阻塞主线程，close 幂等，释放结果准确，正常关闭无多余错误事件 |
| A13 | 实际发布 AAR 的 Kotlin 2.2.10、Java 消费者，Debug/Release、R8、minSdk 21 与当前模拟器；不继承核心 SDK 的 Kotlin 1.9 兼容承诺 |
| A14 | OpenFeature 0.9.0 的开发/复现测试，以及最终通过 G02 的正式依赖上的注册/上下文/错误 hooks 验收；独立 API instance 分别创建 provider，不共享可变身份 client |
| A15 | 真实服务端同步及事件归属落库；HTTP 200 不能单独证明服务端接受事件 |

Provider 已开始执行功能、竞态、制品和设备验证；A01–A15 是完整验收范围，不代表每个变体均已验证。当前证据与缺口见[验证记录](./verification.md)。核心验收仍仅支持 G01。

具体竞态屏障、24 组时序用例、10 项不变量与 G01–G06 发布闸门见[详细验收协议](./architecture-validation.md)。本次还核对了 OpenFeature 的 [provider 规范](https://openfeature.dev/specification/sections/providers/)、[上下文规范](https://openfeature.dev/specification/sections/evaluation-context/)和[事件规范](https://openfeature.dev/specification/sections/events/)；当前在线规范可能比 v0.9.0 接口更新，本文不自行添加该版本接口没有的 domain 参数。
