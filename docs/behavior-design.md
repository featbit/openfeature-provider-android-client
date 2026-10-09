# 行为与架构

## 1. 责任边界

Provider 只承担上下文映射、生命周期协调、值/错误转换及事件桥接。核心 SDK 保持网络、缓存、匿名存储、Android 前后台/网络状态、评估事件与 Track 队列的唯一实现。不开第二条 WebSocket，不复制评估缓存，不注册第二套 Android 生命周期监听。

内部职责、数据结构和线性化点以[详细架构](./architecture.md)为准。只有 ClientOwner 能发起 SDK 身份和模式变更；同步评估和 Track 经过 AdmissionGate 取得读取租约，锁外执行，在租约排空前不提交身份变更。绝不在持锁期间调用 SDK、解析 JSON 或 suspend。

## 2. EvaluationContext → User

| 输入 | 映射/处理 |
| --- | --- |
| 非空白 targetingKey | User.key；不 trim 有效字符串，不做 UUID 等格式检查 |
| null context / 空 targetingKey | 默认 TARGETING_KEY_MISSING；显式匿名策略下，空 key 且无属性可进入匿名。OpenFeature 的缺省 key 就是空字符串，不能区分未提供与显式空字符串 |
| 仅含空白字符的非空 targetingKey | TARGETING_KEY_MISSING，不隐式转换为匿名 |
| 顶层 name | 非空白 Value.String → User.name；缺省使用 targetingKey；Null、其他类型或空白为 INVALID_CONTEXT |
| 顶层 keyId | 拒绝，避免与 FeatBit 保留字段冲突；身份唯一入口是 targetingKey |
| 普通 String 属性 | 原文 AttributeValue.text |
| Boolean / Integer / Long 属性 | 分别转小写布尔或十进制字符串，Long 不经 Double |
| 有限 Double 属性 | 与 locale 无关、可往返的数值字符串；NaN/Infinity 为 INVALID_CONTEXT |
| Null 属性 | AttributeValue.nullValue；不等同于删除属性 |
| List / Structure / Instant 属性 | 首版 INVALID_CONTEXT，不隐式 JSON 化、展平或格式化时间；应用显式转换为 String |
| 属性缺失 | 不写入 User.attributes；每次上下文是完整替换，不做增量 merge |
| 空属性名、SDK 保留属性 | 使用 User/ClientOptions 的既有有效性规则；自动属性打开时禁止用户写 featbit.sdk.* |

匿名模式：只有 null context 或空 targetingKey、无属性的空上下文允许映射到 SDK 内置匿名身份；initialize 传 null 用户且要求 anonymousEnabled=true，后续使用新增 identifyAnonymousContext 取得采用回执，再单独等待 ready。现有 SDK 不公开“匿名 ID + 任意上下文属性”的原子创建能力，首版不伪造或维护第二套匿名 ID。需要匿名属性的应用可自行维护稳定 ID 并作为 targetingKey，按具名路径处理。

身份比较使用规范化后 key/name/attributes 的完整值，不依赖 Map 顺序、对象引用或仅 key。记录期望上下文、当前已应用上下文及操作 generation。自动属性由 SDK 添加，不混入输入上下文比较。错误日志不输出用户属性、凭据或原始响应。

## 3. 初始化、切换与并发

初始化以 factory 调用提交时的 creationContext 为固定创建身份；factory 运行或 create 等待期间出现新上下文，仍只创建一次，再协调到最新有效请求。核心可能先同步创建身份；在最新身份采用且就绪前不放行 provider 评估/Track，见[初始化协议](./architecture.md#6-初始化协议)。

initialize 的顺序：校验上下文 → 调用 options factory 并验证用户一致性 → 异步 create → 安装状态/变更订阅 → 读取当前快照补齐注册窗口 → 必要时采用最新有效身份 → awaitReady。在线模式要求 REMOTE_CONFIRMED；显式离线/LOCAL source 接受 OFFLINE_LOCAL/CUSTOM_LOCAL，事件元数据说明是本地就绪。仅有磁盘缓存或 Bootstrap 不等于在线远端确认。

初始化等待超时抛 ProviderNotReadyError；已创建 SDK 可继续同步，后续真实 ready 通过事件恢复。factory/创建不可恢复失败不循环重建。调用方取消不是业务错误，必须传播 CancellationException；创建 operation 不因取消观察自动取消，ClientOwner 必须接收并关闭晚到实例。

onContextSet 先以短临界区设置新请求 token 和切换屏障，锁外校验映射，待旧读取租约排空后提交身份采用；收到回执才等待 readiness。旧等待取消/超时不能回滚新身份。相同已采用身份只重新等待；采用失败的同身份必须重试采用。详细时序与失效结果处理见[上下文切换协议](./architecture.md#7-上下文切换协议)。

评估传入的 context 必须匹配当前已应用身份；不允许在同步评估中 identify。匹配但尚未就绪返回 PROVIDER_NOT_READY；不匹配返回 INVALID_CONTEXT。Track 同样经过此门禁，因为上游 track 不会代为检查 ready。即使 OpenFeature 正处于 Reconciling/Error，provider 也不能读取旧用户值或把新用户事件记到旧用户。

若新上下文非法，SDK 可保持旧身份，但 provider 标记该上下文不可用，阻止旧身份 Ready 回调清除此错误。随后设置合法上下文才能恢复。SDK 状态回调没有 generation 字段，不能仅凭排队的 READY 通知认定新用户 ready；用当前 identify/awaitReady 的结果确认，再刷新当前快照。SDK 自己仍负责旧网络响应隔离。

生命周期成功会由 OpenFeature 更新 statusFlow，但其 observe 消费的是 provider 事件源，故 provider 仍发一次 Ready 事件。v0.9.0 初始化返回后的无条件 Ready 写入存在覆盖更新事件的窗口，不能靠 provider 延时重新检查严格解决；所需上游修正及 G02 发布门槛见[状态交接限制](./architecture.md#10-openfeature-v090-的状态交接限制)。

## 4. 严格评估与数值

2026-10-06 已发布的核心 SDK 0.2.0 采用统一声明类型检查和 Double 数值模型，见[核心 SDK 契约](./core-sdk-contract.md)；Provider 映射仍待实现。

| OpenFeature 方法 | FeatBit variationType | 转换规则 |
| --- | --- | --- |
| Boolean | boolean | 合法布尔值；其他声明类型不转换 |
| String | string | 保留原始字符串 |
| Integer | number | 有限 Double 转 Int，向零截断，越界饱和 |
| Long | number | 有限 Double 转 Long，向零截断，越界饱和，不承诺完整 Long 精度 |
| Double | number | 有限 Double；正常 IEEE 754 舍入，不接受非有限结果 |
| Object | json | 严格 JSON，递归转 OpenFeature Value；允许对象、数组、Null 及 JSON 标量 |

JSON 数字统一按 Double 映射到 OpenFeature Value，接受 IEEE 754 舍入。Null 是合法 JSON 值；不支持从 JSON 推断 Instant。沿用核心 JSON 深度约束，不另增任意 flag 大小/数量限制。

默认值仅在失败时原样返回，不通过 FbValue/Double 往返转换，尤其是 Long 和包含 Long/Instant 的对象默认值。Object 默认值是 opaque fallback；不要求其必须可序列化为 FeatBit JSON。

一次核心调用必须基于同一个 SDK 原子视图完成声明类型检查、解析、结果与评估事件准入；失败评估允许按核心规则记录选中远端 variation。Provider 成功路径只做 Double 数值转换或 FbValue 映射，不追加整数或精度拒绝。禁止用 allVariations 查值后再另一次评估，也禁止先 numberVariationDetail 再字符串读取拼接精确结果。

## 5. Reason、错误与 metadata

| 情况 | reason | errorCode |
| --- | --- | --- |
| 成功，只有 SDK MATCH 信息 | UNKNOWN | 无 |
| 未初始化或当前身份仍在等待 | ERROR | PROVIDER_NOT_READY |
| flag 不存在/已归档 | ERROR | FLAG_NOT_FOUND |
| 声明类型不匹配 | ERROR | TYPE_MISMATCH |
| 核心 WRONG_TYPE（含内容损坏、JSON 非法） | ERROR | TYPE_MISMATCH |
| 缺失所需 targetingKey | ERROR | TARGETING_KEY_MISSING |
| 上下文无效或不匹配 | ERROR | INVALID_CONTEXT |
| 不可恢复的实例同步终止/关闭后调用 | ERROR | PROVIDER_FATAL |
| 其他内部故障 | ERROR | GENERAL |

MATCH 不足以推断命中规则、灰度分流或默认规则，不能映射为 TARGETING_MATCH。现有 EvaluationDetail 不含 variationId，variant 保持 null，不把 flag value 冒充 variant。首版 evaluation metadata 为空；不声称每个值来自 CACHE/REMOTE，连接快照不能证明具体 flag 的来源。

失败的 provider 评估建议抛具体 OpenFeatureError，使当前 Kotlin SDK 的 error hooks 正常执行；OpenFeature Client 负责包装为 defaultValue + reason/errorCode/errorMessage。仅返回带 errorCode 的 ProviderEvaluation 在当前上游不会自动走 error hooks。成功才返回 ProviderEvaluation。错误消息用固定、安全诊断，不解析人类 explanation 来猜错误类别。

## 6. Provider 事件

| 核心情形 | OpenFeature 事件/处理 |
| --- | --- |
| 初始化、上下文切换进入 READY | 生命周期方法返回；provider 发一次 ProviderReady 供 observe 消费，OpenFeature 同时管理 statusFlow；若已是 STALE，保留 Stale 而非发 Ready |
| 已超时等待或 Stale 后恢复当前身份就绪 | ProviderReady，按状态边去重 |
| 已就绪后 SyncStatus.STALE | ProviderStale；允许当前用户旧数据，遵循评估门禁 |
| TERMINAL | ProviderError，PROVIDER_FATAL；不再宣称能够自动恢复 |
| flag 变更 | ProviderConfigurationChanged，flagsChanged 使用 SDK keys |
| allFlagsChanged=true | flagsChanged 为空且 eventMetadata 的 featbit.allFlagsChanged=true；消费者全量失效 |
| 仅 BACKGROUND/OFFLINE 暂停或 candidateFailure | 不直接当 fatal；结合实际 SyncStatus，本地/offline 就绪使用明确元数据 |
| 正常 shutdown | 先隔离监听；不为正常释放额外制造错误事件 |

状态与变更监听必须先注册再读取最新状态，注册失败不能报告初始化成功。使用共享生产端、每订阅者独立的状态槽和变更合并槽；不能用单一 replay=1 混合所有事件。容量、初始订阅、慢消费者和关闭协议已在[事件桥](./architecture.md#9-事件桥与容量)定义。

## 7. Track 与关闭

无 details.value 时使用 SDK track(name)，当前 SDK 默认数值为 1.0。有值时调用 Number.toDouble() 并检查结果是否有限；有限结果直接提交 SDK，接受舍入和下溢为零，不检查整数可精确表示范围或 BigDecimal 无损性。Long、BigInteger、BigDecimal 与其他 Number 使用相同转换规则；不通过字符串猜数值，不提供精确整数/十进制统计保证。

转换异常、NaN、正负 Infinity（包括大数转换溢出）记录固定安全诊断并丢弃本次事件，不抛出影响业务流程的普通转换异常，不打印原始数值或异常消息。自定义 Number 的转换在读取租约和 SDK Track 提交之前执行；转换后重新检查当前身份/状态再取得租约，避免在持锁期间执行用户转换代码。JVM 致命错误不作为普通数值错误吞掉。此规则与 flag 数值一致接受 Double 精度损失；需要精确金额或大整数统计的应用应在调用前按自身业务单位和精度要求处理。

TrackingEventDetails.structure 首版不传输：仍发送名称/数值，按诊断代码记录“结构属性未发送”，不打印属性内容；文档必须注明这一点。不能为了携带结构属性临时 identify，也不能把属性混入用户属性。重复、禁用、容量不足和关闭结果由 SDK 管理；track 的 Unit 不是送达确认。Provider 不再额外记录 flag evaluation 事件。

上下文错误、未就绪、非有限数值等 Track 失败不抛出会影响业务路径的异常，记录安全诊断并丢弃；空事件名的上游 IllegalArgumentException 保持 OpenFeature 自身行为。需要确认队列处理结果时使用 flush，ACCEPTED 不等于落库成功。

shutdown/close 先关闭评估及 Track 准入，撤销订阅、隔离回调，再启动 SDK close。正常释放不调用先 flush 再 close 两套发送流程；复用 SDK 的关闭预算和最终发送。不得使用 runBlocking 在 Android 主线程等待，不在释放完成之前取消负责清理的 scope。
