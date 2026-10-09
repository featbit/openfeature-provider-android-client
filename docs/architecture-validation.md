# 详细架构的验收协议

状态：2026-10-06 基线复核；2026-10-09 补充五项设计边界与验收用例。核心 G01 已由正式版 0.2.0 满足；Provider 已实现并执行部分 A01–A15、R01–R24 验证；下表保留完整目标，实际覆盖见[验证记录](./verification.md)，不能据此宣称 G02–G06 全部通过。总体验收范围见 [实施计划](./implementation-plan.md)。这里补充确定性时序和发布闸门，不把设计推演记为测试通过。

## 1. 不变量

| ID | 必须始终成立 |
| --- | --- |
| I01 | 每 provider 最多一次 factory、一次已提交 create、一个 owned client；关闭晚到实例也算在所有权内 |
| I02 | desiredContext 与 adoptedContext 不一致时不准入评估/Track |
| I03 | SDK 身份/模式变更不穿过已有读取租约；关闭例外必须使晚到结果不可成功返回 |
| I04 | 未收到身份采用回执，不得把旧身份的 ready 解释为目标身份已生效 |
| I05 | 旧 token 的完成不得修改当前 phase、数据准入或事件状态 |
| I06 | 类型/解析失败允许选中远端 variation 事件；不得伪造 fallback 事件；并发取消不撤销已提交事件 |
| I07 | 超时、取消观察和 SDK 工作取消是三件不同的事 |
| I08 | 慢 collector 不阻塞 SDK 回调；最终状态与全量失效不能相互覆盖；不因订阅人数拒绝 OpenFeature 内部监听 |
| I09 | close 结果最多结算一次；未知清理不得返回 cleanupComplete=true |
| I10 | 任何持锁路径不调用 factory、logger、SDK、JSON 解析、suspend 或用户回调 |

## 2. 必须可控的测试屏障

内部 CoreFacade fake 可暂停 create 完成、身份采用、readiness、resolve 提交、Track 提交和 close 完成；时钟可独立推进 elapsed 时间；回调可延迟/重排。测试 fake 不能修改生产公共 API。

纯映射测试不启动 Android。控制器测试使用虚拟协程调度与闸门，禁止用 sleep 猜时序。最终集成另外使用实际 OpenFeature API、实际 AAR 和核心 TestData/受控服务，防止 fake 复制了错误假设。

| ID | 安排与触发 | 断言 |
| --- | --- | --- |
| R01 | 卡住 resolve(A)；提交 B；再评估 B；释放 A | B 早期 fallback；SDK adopt B 发生在 A 租约释放后；A 晚返回被门禁拒绝；事件若已提交只属于 A |
| R02 | 匿名 B 准备暂停；新具名 C 完成；释放 B | 核心不晚 adopt B；provider 保持 C；无 B Ready |
| R03 | 匿名 B 未采用时到 deadline；旧 A 仍 READY | B 为采用失败；不得用 A awaitReady 解锁；显式重试才启动新采用 |
| R04 | B 已采用回执成功，延迟回调至调用者超时后 | getResult 仍 SUCCESS；owner 可继续为 B 等待数据；不重复匿名创建 |
| R05 | B 的 ReadyResult 已成功但回调延迟；提交 C | B 完成不改变 C 状态；C 仅依赖自己的回执和 ready |
| R06 | 保存排队 READY(A)；切换 B 到 WAITING；派发旧通知 | 重新读当前快照，不完成 B；无旧值放行 |
| R07 | 未初始化时订阅；完成初始化；再追加迟到订阅者 | 两者都获得当前 provider Ready；仅 2 条 SDK 监听；迟到订阅收到 All |
| R08 | 阻塞一个 collector；连续 key 变化超过 1,024 并 Stale→Ready | 其他 collector 正常；慢方恢复后获得最新 Ready 和 All，容量有界 |
| R09 | close 与 create 成功回调竞争 | 无可用 client 泄漏；恰好一次 core close；close 超时不伪造丢失数 |
| R10 | 阻塞 factory 超过 close deadline，再让其返回 | close 已结算失败；阻止尚未提交的 create；任务最终退出，不重写结果 |
| R11 | SDK observe 注册失败且操作稍后完成 | getResult/单个合并探测接收结果；取消用户 await 不丢 owned client |
| R12 | 类型为 string、内容为 true；请求 Boolean | TYPE_MISMATCH、默认值保留、error hook 一次；满足核心事件条件时允许 evaluation |
| R13 | number=9007199254740993 / Long.MAX_VALUE / 越界 1 | 按 Double 舍入后转 Long；接受精度损失和边界饱和，不追加范围拒绝 |
| R14 | 非法 context C 到来时 B 正在采用，随后 B READY | C 错误保持、旧身份不放行；提交合法 D 后恢复 |
| R15 | 初始化即将返回时先发送 Fatal/Stale，再允许上游提交；另测同身份 Stale 的 onContextSet 正常返回 | provider 门禁正确；上游 getStatus 不得被旧 Ready 覆盖，原版 0.9.0 预期暴露问题；已运行并复现缺陷，仍不满足 G02 |
| R16 | setOffline 抢在 ready 完成前；再 setOnline | mode 结果与 remote ready 区分；无旧模式 Ready 解锁；offline 空数据报 FLAG_NOT_FOUND |
| R17 | 清除缓存命令与用户切换并发 | 明确作用于提交时稳定身份；不清理旧用户后谎称已清理新用户 |
| R18 | 超过待派发命令/生命周期等待者容量；重入 logger 抛异常 | 明确拒绝额外操作、无死锁、已有操作继续；订阅者人数不在此限制内；日志不包含输入值 |
| R19 | 离线且缺在线端点时 setOnline 失败；另测重复 setOnline 和容量拒绝 | 保留原操作失败；按实际模式/readiness 恢复读取；无永久屏障，不清空仍有效的在线确认 |
| R20 | setOffline 已改变实际模式但旧连接停止超时；恢复期间再切换用户或关闭 | 操作仍 TIMED_OUT，当前合法身份可按 OFFLINE_LOCAL 恢复；旧模式结果不越过新身份或关闭屏障，不自动回滚 |
| R21 | 暂停控制队列或读取租约；推进 setOffline/setOnline/clearCache deadline，分别在提交前与提交后超时；另测 flush 排队超时 | 提交前不调用核心并解除本命令屏障；提交时仅传剩余预算；提交后保留 owner、外部结果单次结算、允许核心副作用继续；flush 不延长对外预算 |
| R22 | 注册 provider 前启动至少 65 个 observe collector，再由真实 OpenFeature 注册并监听；阻塞部分 collector，最后取消全部 | 内部监听成功，状态与变化持续更新，核心仍仅两条监听；慢方槽有界，取消后槽回收；总内存可随活跃人数增长 |
| R23 | 在 factory 调用前、运行中、返回后 create 提交前及 create 提交后分别插入 A→B；再覆盖非法/过期 B 和 close | factory 调用前可选最新身份；调用后固定 creationContext，只调用一次 factory/create；B 就绪前不放行，B 无效不回退 A，close 阻止未提交 create；记录可能发生的 A 同步 |
| R24 | Track 的 Long.MAX_VALUE、2^53+1、BigInteger、BigDecimal、小数下溢、自定义 Number；再测 NaN/Infinity/溢出及转换异常，并在转换中切换身份 | 有限 Double 均接受，允许舍入/下溢，不做无损检查；非有限/异常丢弃并安全诊断；转换不持锁或租约，提交前重新校验身份；不改变 flag 转换规则 |

## 3. 状态机性质检查

用有限模型枚举 create/采用/ready/超时/cancel/close 的相对顺序，覆盖重复 close、回调两次、零订阅者、多订阅者及所有 phase 退出路径。检查 I01–I10，而不是断言私有方法调用顺序。资源计数在结束时归零，未清理的任务必须对应显式超时失败。

## 4. 发布闸门

| 闸门 | 通过条件/证据 | 当前状态 |
| --- | --- | --- |
| G01 核心能力 | FeatBitClient 的两项身份方法、统一声明类型检查、Double 值模型及兼容性消费者通过；0.2.0 已发布并完成公开制品验证，见[发布证据](./implementation-plan.md#1-本次核对的基线) | 已满足，固定依赖 0.2.0 |
| G02 OpenFeature 状态提交 | R15 及 provider 替换/初始化失败/上下文协调变体在正式依赖上通过；必须包含防旧生命周期结果覆盖新事件的上游修正 | 未通过；0.9.0 仅作开发/复现基线，确定性复现已执行并确认缺陷 |
| G03 功能与竞态 | A01–A12、A14 + R01–R24 的确定性测试通过 | 已实现并有测试覆盖；完整闸门未宣告通过，见验证记录 |
| G04 Android 制品 | 独立 Kotlin/Java 消费者编译，Debug/Release/R8，API 21 与较新 Android 设备/模拟器实测；内存/主线程 I/O 检查 | Java/Kotlin Debug/Release/R8 与 API 34 已验证；API 21 等仍待验证 |
| G05 端到端事件 | 真实服务端验证 flag/user/Track 归属和实验事件；包含切换时序和解析失败的选中 variation 事件符合核心策略 | 待 Provider 端到端验证 |
| G06 文档与发布 | Maven 元数据、许可证、依赖锁定、 API 兼容表、匿名与 TrackingDetails 限制、已知未测范围齐全 | 开发文档及本地产物已建立；正式发布准备仍未完成 |

不以“编译通过”代替身份、状态及事件正确性。核心 0.2.0 的验收只确认 G01，不继承为 Provider 的 G03–G05 证据。G02 是尚未完成的外部依赖条件；已获实现授权并完成 Provider 开发与最小复现，但正式发布前仍须通过 G02。Provider 开发实现与测试已建立；没有修改 OpenFeature，上游缺陷仍存在。
