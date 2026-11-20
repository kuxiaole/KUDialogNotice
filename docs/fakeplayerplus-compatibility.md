# FakePlayerPlus 假人协议拦截修复

日期：2026-11-20（Asia/Shanghai）。交付版本：KUDialogNotice 1.1.1。

## 根因与修改

生产日志 14:58:43 已记录 KUDialogNotice 1.1.0 的 FallbackGuardListener 在 LOWEST 将换手事件从未取消改为取消。源码调用链为 AuthMe LoginEvent → AuthMeSessionListener → NoticeFlowService.onAuthenticated → 创建协议会话 → FallbackGuardListener 取消尚未同意协议的玩家操作。假人也收到登录事件，却没有真人客户端完成协议，因此持续被限制。

1. 在认证和预览入口，通过 FakePlayerPlus 的公开 API 查询内存中的在线注册表。仅当记录的 Player 与当前 Player 是同一个对象时，跳过协议会话；重新认证时清除该假人的残留会话。
2. 不以名称、OP、权限通配符或历史 UUID 记录识别假人。插件缺失、禁用、API 不兼容或查询失败时，不给予假人豁免；查询故障每个 KUDialogNotice 实例最多记录一次。
3. 不代替假人写入“已同意”，不改写换手事件的取消状态，不强制修改库存。真实玩家和其他插件的保护继续走原有路径。
4. 声明 FakePlayerPlus 为可选加载依赖。无新增服务器配置、命令、权限或数据库迁移。Mockito 仅用于测试，没有打入成品。

修改源码：src/main/java/dev/kuxiaole/kudialognotice/compat/FakePlayerPlusSupport.java、flow/NoticeFlowService.java、src/main/resources/plugin.yml，以及 pom.xml 版本和测试依赖。

## Folia 范围与三遍复核

| 分类 | 调用链与上下文 | 处理与生命周期 |
| --- | --- | --- |
| must_fix | AuthMe 登录完成 → 假人被纳入需要真人确认的会话 | 在会话创建前验证在线实例身份；无需增添事件覆盖或调度 |
| 必须保持 | 登录/预览入口已有的玩家上下文；注册表为 ConcurrentHashMap | 新逻辑只读内存映射和对象身份，不读取跨区域库存、位置或区块，不查数据库、不等待网络 |
| 必须保持 | 原换手命令 → ControlAccess → 假人实体线程 → 原生事件 | FakePlayerPlus JAR 未修改，仍执行权限和租赁检查；事件仍可被其他插件取消或修改 |
| 必须保持 | 会话和卸载 | 不新增任务、静态玩家缓存或持久化数据；原会话仍由 cleanup/shutdown 清理。跨插件对象与反射方法不缓存，避免热重载后引用旧插件 |
| defer | Redis、租赁购买、图形菜单完整流程及所有生产服插件组合 | 不在本次兼容修复范围；不据此宣称全部生产功能重新联调完成 |

功能范围复核：原有协议流程、公开接口、命令、权限和配置键保留，仅添加经在线实例验证的假人排除条件。线程与性能复核：无新增调度、跨区域访问、阻塞 IO 或 sub-tick 操作；使用原有 SchedulerFacade。生命周期复核：仅新增不可变引用及 AtomicBoolean 告警标志，不创建资源或缓存 Player。

## 测试结果

- 构建：Java 25，`mvnw.cmd -B -ntp package`，BUILD SUCCESS。48 项单元测试全部通过；新增 11 项覆盖真实实例、同 UUID 不同对象、缺失/禁用/错误 API、API 恢复、插件替换、假人无会话、真人拦截、残留会话清理及卸载。
- Folia 26.2（build 7）旧版复现：加载真实 KUDialogNotice 1.1.0 + AuthMe 6.0.1-b2770 + 当前 FakePlayerPlus。AuthMe 登录成功，协议会话阻塞；仅副手有物品时，原生换手被同一 FallbackGuardListener 取消。复现用例符合预期。
- Folia 26.2 和 Lophine 26.2 修复版：各 18/18 项换手检查通过，另各 4 项协议状态检查通过，两次服务器退出码均为 0。
- 假人完成真实 AuthMe 自动注册/登录后，无协议会话；独立数据库中玩家协议记录数为 0、协议接受记录数为 0；预览入口也不建立假人会话。
- 覆盖两手不同物品、指定快捷栏、单手有物品、空手、相同物品、旁观、普通/晚期/LOWEST 取消、取消后放行、再次取消、事件修改物品结果、连接阻止后重试。检查真实物品类型/数量及每次操作后的监听器注册恢复。
- 额外使用原生测试玩家临时从 FakePlayerPlus 注册表移除的测试夹具，走未受管理玩家的协议路径：成功创建阻塞会话且换手被拒；恢复注册并重新认证后，残留会话被清除，换手成功。该测试不是图形客户端真人登录；同 UUID 不同 Player 实例的拒绝豁免另有单元测试。

测试环境使用新的本机 MySQL 8.4.11 实例，KUDialogNotice 通过其自带 MariaDB 驱动连接；AuthMe 使用新建 SQLite。Redis 关闭，未验证完整跨服协议同步。数据库文件、世界和插件配置均位于独立测试目录，未使用或修改现有业务数据库。测试结束后测试服及独立 MySQL 均已关闭。AuthMe 存在缺少 MaxMind/PacketEvents 的启动警告；有效测试日志未发现 ERROR、身份查询失败或区域线程异常。

有效证据（相对于 Fakeplayer 工作区）：

- `.test-swap/folia/notice-baseline-rsa/results.json`
- `.test-swap/folia/notice-fixed/results.json`
- `.test-swap/lophine/notice-fixed/results.json`
- 对应目录的 `logs/latest.log`。

前两次测试环境准备失败记录保留在 `notice-baseline` 和 `notice-baseline-config-complete`：分别为测试 Redis 配置缺少必填值、MySQL 8.4 认证缺少客户端 RSA 公钥路径；修正隔离环境配置后重新建库测试，未改动生产插件的存储实现。

可复现脚本位于 FakePlayerPlus-main/tools/integration/hand_swap_regression.py、notice_test_environment.py 和 NoticeSwapProbe.java；测试探针不包含在交付 JAR。

## 替换说明与产物

1. 正常停止服务器。
2. 从 plugins 目录移走旧版 KUDialogNotice JAR，放入 `KUDialogNotice-1.1.1.jar`，避免同时保留两个版本。
3. 保留 KUDialogNotice 配置目录和原数据库。正常启动服务器后，重新召唤有权限使用的假人，再从管理菜单点击交换主副手。无需调整协议豁免权限。

本次替换的是 KUDialogNotice；当前 FakePlayerPlus-dev.jar 无需更换。请使用正常重启完成版本更新。

- JAR：target/KUDialogNotice-1.1.1.jar
- 大小：2,407,958 字节
- SHA-256：15052463c7adaea4e3a9a8d2d00d936ed42629e01af9e93ab7ce84b503f358d3
- 联调使用的 FakePlayerPlus SHA-256：83af5296dfcecb29c6be859ca8aaa3a734922191ce667244abffb9847f8c73bd
