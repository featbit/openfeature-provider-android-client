# 公共 API 设计

## 1. API 分层

应用通过 OpenFeature Client 评估与 Track，通过 OpenFeature API 注册 provider、设置上下文和订阅状态。FeatBitProvider 负责适配；应用不需要再自行创建 FeatBitClient。

首版新增三个顶层公共类型：`FeatBitProvider`、`FeatBitOptionsFactory`、`IdentityPolicy`。Builder 作为 FeatBitProvider 的嵌套类型。映射器、状态协调器、值转换器、事件桥均为内部实现。

以下为签名契约说明，不是 Kotlin/Java 实现代码。`Context` 在构造配置处指 Android Context；`EvaluationContext` 指 OpenFeature 上下文。

## 2. 创建与配置

| API | 类型/结果 | 契约 |
| --- | --- | --- |
| FeatBitProvider.builder(applicationContext, optionsFactory) | Builder，提供 Java 静态入口 | 只保存 applicationContext；不开始网络、磁盘、监听或 SDK 创建 |
| Builder.identityPolicy(policy) | Builder | 默认 REQUIRE_TARGETING_KEY；另一选项 PERSISTENT_ANONYMOUS |
| Builder.initializationTimeoutMillis(value) | Builder | 默认 10,000；正数，无人为固定上限；超时只结束等待 |
| Builder.contextChangeTimeoutMillis(value) | Builder | 默认 10,000；正数；切换后继续由 SDK 同步 |
| Builder.build() | Outcome&lt;FeatBitProvider&gt; | 仅校验 provider 自身配置，沿用核心 Outcome；factory 在 initialize 才调用 |
| FeatBitOptionsFactory.create(user: User?) | Outcome&lt;ClientOptions&gt; | Kotlin SAM/Java 函数式接口；冻结的创建用户或显式匿名的 null；首次初始化调用一次 |

Factory 应返回新构造、不可变的 ClientOptions，不得创建客户端，不应执行阻塞 I/O。配置中的 initialUser 必须在 key/name/attributes 上与传入 user 相等；PERSISTENT_ANONYMOUS 策略始终要求 anonymousEnabled=true，匿名初始用户必须为 null。不一致在创建 SDK 前失败，具名模式禁止 factory 悄悄替换用户。

factory 开始后创建身份固定；期间收到新上下文，也不重复调用 factory，而是在唯一客户端创建后采用最新有效身份。核心可能先启动创建身份的同步，provider 在最新身份采用且就绪前禁止评估/Track；关闭可以阻止尚未提交的 create，详见[初始化协议](./architecture.md#6-初始化协议)。

这样处理是因为现有 ClientOptions 不可变、没有公开 copy/toBuilder，而初始 EvaluationContext 在 initialize 时才确定。简单接收已构造 ClientOptions 再 identify，会先以错误用户启动同步及用户事件。

端点、sdkKey、事件开关、隐私属性、Bootstrap、TestData/custom source、缓存、日志、重试及后台策略全部通过 ClientOptions 配置。Provider 不增加同名转发 setter，不修改原 SDK 默认值。Factory 返回 INVALID 时初始化失败；配置错误使用 GENERAL，用户映射错误使用 INVALID_CONTEXT 或 TARGETING_KEY_MISSING。

## 3. 必须实现的 OpenFeature 接口

源接口为 OpenFeature Kotlin v0.9.0 的 `dev.openfeature.kotlin.sdk.FeatureProvider`。

| API | 返回类型 | Provider 行为 |
| --- | --- | --- |
| metadata | ProviderMetadata | 稳定名称 `FeatBit` |
| hooks | List&lt;Hook&lt;*&gt;&gt; | 首版为空列表；应用 hooks 由 OpenFeature 管理 |
| initialize(initialContext: EvaluationContext?) | suspend Unit | 映射身份、创建 SDK、建立订阅并等待 readiness；失败抛 OpenFeatureError |
| onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) | suspend Unit | 校验并切换当前用户；等待当前切换就绪；同值上下文也接受调用 |
| getBooleanEvaluation(key, defaultValue: Boolean, context: EvaluationContext?) | ProviderEvaluation&lt;Boolean&gt; | boolean 类型严格解析 |
| getStringEvaluation(key, defaultValue: String, context: EvaluationContext?) | ProviderEvaluation&lt;String&gt; | string 类型，不把数字/JSON 自动转成字符串 |
| getIntegerEvaluation(key, defaultValue: Int, context: EvaluationContext?) | ProviderEvaluation&lt;Int&gt; | number 类型，Double 转 Int，向零截断、越界饱和 |
| getLongEvaluation(key, defaultValue: Long, context: EvaluationContext?) | ProviderEvaluation&lt;Long&gt; | number 类型，Double 转 Long，允许精度损失、截断和饱和 |
| getDoubleEvaluation(key, defaultValue: Double, context: EvaluationContext?) | ProviderEvaluation&lt;Double&gt; | number 类型，有限 Double |
| getObjectEvaluation(key, defaultValue: Value, context: EvaluationContext?) | ProviderEvaluation&lt;Value&gt; | json 类型；递归映射 JSON 对象、数组及合法 JSON 标量 |
| track(trackingEventName, context: EvaluationContext?, details: TrackingEventDetails?) | Unit | 校验当前身份，数值转有限 Double，接受精度损失；结构详情不发送；首版实现 |
| observe() | Flow&lt;OpenFeatureProviderEvents&gt; | 共享事件源，不设订阅人数硬上限，每订阅者缓冲有界；不重复安装 SDK 监听 |
| shutdown() | Unit，非 suspend | 同步关闭准入并发起异步释放；不能阻塞主线程等待 SDK close |

所有评估方法的 key 类型为 String。默认值的类型与返回值一致；`getObjectEvaluation` 的 Value 是 OpenFeature Value。应用通常调用 OpenFeature Client 的 Value/Details 方法，而不是直接调用上表 provider 方法。

## 4. FeatBit 专属扩展

| API | 返回类型 | 用途与约束 |
| --- | --- | --- |
| getConnectionInformation() | ConnectionInformation? | 创建成功前为 null；创建后提供 SDK 当前连接快照，不替代 OpenFeature 状态 |
| setOffline(timeoutMillis: Long) | Operation&lt;ModeResult&gt; | 与身份变更串行提交；timeout 包含排队；失败后按实际模式恢复读取 |
| setOnline(timeoutMillis: Long) | Operation&lt;ModeResult&gt; | timeout 包含排队；操作结果与实际模式/readiness 分别处理 |
| flush() | Operation&lt;FlushResult&gt; | 保留 EMPTY/ALL_DELIVERED/PROCESSED_WITH_LOSS 和失败 Outcome；对外预算为 requestTimeoutMillis，包含排队 |
| clearCache(scope: CacheScope, timeoutMillis: Long) | Operation&lt;CacheClearResult&gt; | 沿用 SDK 范围与结果；timeout 包含排队，提交后超时不保证清理未发生 |
| close() | Operation&lt;CloseResult&gt; | 同 shutdown 的释放路径，可观察 undeliveredEvents/cleanupComplete；重复调用返回同一操作 |

扩展操作从 provider 接受调用时开始计算预算；提交前超时不再执行，提交后超时不取消核心工作。模式操作失败不代表模式未变，owner 重新读取实际模式并恢复当前合法身份的准入；原操作结果不因此改成成功。完整规则见[排队与超时](./architecture.md#扩展操作的排队与超时)。

client 尚未创建/订阅尚未安装时调用上述操作，除 close 外返回已完成的失败 Operation，采用 INVALID + `provider_not_initialized`；关闭后采用 CLOSED。创建完成但等待远端 ready 超时时允许 offline/online、flush 等操作恢复，不把“initialize 调用已成功返回”作为前置条件。clearCache 仅允许稳定身份，其他状态边界见[状态表](./architecture.md#4-状态机)。getConnectionInformation 在关闭后可返回已保存的最终 CLOSED 快照。

close 在 SDK 尚未创建时也必须有效：阻止后续初始化，等待或清理晚到的创建结果，避免泄漏。不得先宣称 cleanupComplete=true 再留下晚到客户端。返回时间预算内不能确认清理时，明确失败或 cleanupComplete=false，继续处理晚到清理。

不公开 getClient、identify、identifyAnonymous、resetAnonymousIdentity：这些能力绕过 OpenFeature 上下文会破坏身份与事件的一致性。匿名 reset 暂不提供；退出登录通过设置空上下文回到持久化匿名身份，回到同一匿名身份不代表创建新匿名 ID。

## 5. 应用使用流程

1. 用 factory 配置 FeatBit 的端点、凭据、事件和缓存，factory 将传入 User 放入 ClientOptions。
2. 构建 FeatBitProvider，构造 OpenFeature ImmutableContext，将用户 ID 放入 targetingKey。
3. 用 OpenFeature setProviderAndWait 注册并等待；同时检查 OpenFeature 状态。当前上游会把初始化异常转换为状态，不能把“调用返回”单独当作成功。
4. 获取 OpenFeature Client，使用六类 Value/Details 方法和 track。
5. 登录、属性变化或退出登录通过 OpenFeature setEvaluationContext 触发；依赖状态/事件判断完成，不用任意延时。
6. 必要时通过 provider.flush/close 观察异步结果。OpenFeature shutdown 会调用 provider.shutdown，但不意味着 FeatBit 事件已全部送达。

Kotlin 用户可以使用现有 ClientAdapters.await 等待扩展 Operation；Java 用户通过 Operation.observe。OpenFeature 自身的协程入口不额外复制成一套 Java OpenFeature SDK。

## 6. 详细设计依赖

新增 provider 公共类型仍保持本页的三种；核心依赖固定为已发布的 `co.featbit:featbit-client-android:0.2.0`。身份方法直接通过 FeatBitClient 对接，评估复用现有 Detail 方法，保留 Double 数值模型，详见[核心接入契约](./core-sdk-contract.md)，不再需要新增核心接口。OpenFeature 0.9.0 是开发和问题复现基线，完整状态保证需要包含[初始化状态仲裁修正](./architecture.md#10-openfeature-v090-的状态交接限制)的依赖版本，正式最低版本由 G02 验证确定。
