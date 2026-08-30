# KUDialogNotice

KUDialogNotice 是面向 Lophine/Folia 26.2 的登录公告插件。玩家通过 AuthMe 验证后，插件等待 30 tick（默认 1.5 秒），再按顺序处理服规与当前子服的更新日志。

## 工作流程

- 没有服规接受记录的玩家都会看到服规，包括插件首次部署前已经进入过服务器的老玩家。
- 玩家必须在正文下方勾选“已完整阅读”后，服规的“同意”和“拒绝”操作才会被服务端接受。
- 点击“同意”并成功写入 MariaDB 后，继续显示当前公告流中尚未读过的更新日志。
- 点击“拒绝”会先显示二次确认；再次拒绝会踢出玩家，而且不会写入接受记录，下次登录仍会显示服规。
- 服规接受状态全网共享。修改 `rules.yml` 不会自动要求已同意玩家重新确认。
- 更新日志文件会在连接同一 MariaDB/Redis 命名空间的子服之间同步；玩家已读记录仍按“玩家 UUID + `server-id`”分流，并且必须点击“已知晓”才会写入当前 `revision`。

## 运行要求

- Java 25
- Lophine `26.2-669`（API `26.2.build.669-stable`）或与 `dev.folia:folia-api:26.2.build.4-beta` 二进制兼容的 Folia 26.2 服务端
- AuthMeReloaded；本项目针对 `5.7.0-FORK` 的 `LoginEvent` 流程开发
- MariaDB（权威状态库，必须可用）
- Redis（全网共享读取缓存）
- Velocity 网络中的各后端必须接收到一致的玩家 UUID，建议使用 Velocity modern forwarding
- 允许旧版本客户端时，在 Velocity 安装并正确配置 ViaVersion；按实际客户端协议决定使用原生 Dialog 还是书本兜底

## 构建与安装

Windows：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-25'
.\mvnw.cmd clean package
```

Linux/macOS：

```bash
chmod +x mvnw  # 文件复制过程未保留执行位时只需执行一次
JAVA_HOME=/path/to/jdk-25 ./mvnw clean package
```

构建产物位于 `target/KUDialogNotice-1.1.0.jar`。

1. 将构建产物放入每个 Lophine 子服的 `plugins` 目录，并确保 AuthMe 已安装。
2. 首次启动会生成 `plugins/KUDialogNotice/config.yml`、`rules.yml`、`changelog.yml` 和 `messages.yml`。
3. 停服后配置 MariaDB、Redis 和本服的 `server-id`，再编辑服规与更新日志。
4. 启动所有子服；插件会自动创建 MariaDB 表。

## MariaDB 与 Redis

所有需要共享阅读状态的子服必须连接同一个 MariaDB 数据库，并使用同一个 `table-prefix`。Redis 也应指向同一实例和逻辑库，并使用同一个 `key-prefix`。

```yaml
storage:
  mariadb:
    jdbc-url: "jdbc:mariadb://127.0.0.1:3306/minecraft"
    username: "minecraft"
    password: "change-me"
    table-prefix: "kudn_"
    maximum-pool-size: 6
    connection-timeout-ms: 5000
    socket-timeout-ms: 15000
  redis:
    enabled: true
    uri: "redis://127.0.0.1:6379/0"
    key-prefix: "kudn:"
    cache-ttl-seconds: 300
```

MariaDB 会自动创建 `<prefix>players`、`<prefix>rules`、`<prefix>changelog_seen` 和 `<prefix>changelog_document`。其中 `changelog_document` 保存当前全网公告的完整 YAML、`revision`、SHA-256 和来源节点；MariaDB 始终是公告与玩家状态的最终事实来源。Redis 既用于共享读取缓存，也用于发布公告变更通知，但不保存公告正文。缓存 key 和通知频道会自动加入由 MariaDB JDBC URL 与表前缀生成的命名空间，切换权威数据库时不会误用旧库缓存。所有需要互相同步的子服应使用完全一致的 JDBC URL、`table-prefix`、Redis URI 和 `key-prefix`；这些值不一致会形成不同的同步命名空间。需要 TLS 时可使用 `rediss://` URI。

### 故障行为

- MariaDB 状态读取失败时，服规检查采用 fail-closed：不能确认已同意的玩家会被断开，避免绕过服规。
- 服规“同意”写入失败时不会放行，也不会生成接受记录；玩家需要重试。
- 更新日志“已知晓”写入失败时会关闭页面，但不会记录该 revision，下次登录会再次显示。
- Redis 暂时不可用时，玩家状态读写会回退到 MariaDB，并在日志中给出一次警告；公告仍会写入 MariaDB，其他子服通过启动时检查和约 30 秒一次的轮询最终同步，MariaDB 不可由 Redis 替代。
- 公告内容解析、数据库写入或远端文件原子替换失败时，当前内存配置和本地旧文件会继续使用；不会发布一个未校验的公告。

## 跨服与版本号

`config.yml` 中的 `server-id` 是玩家已读记录的更新日志流标识，只允许小写字母、数字、点、下划线和连字符，最长 64 个字符。公告文件本身使用固定的全网流 `global`：连接同一 MariaDB/Redis 命名空间的所有子服会共享同一份 `changelog.yml`。

```yaml
server-id: "survival"
```

- 不同玩法子服仍可使用不同值，例如 `survival`、`skyblock`，这样玩家在不同子服的已读记录相互独立。
- 同一个逻辑子服的多个实例使用相同值，以共享该子服的已读记录。
- 服规同意记录不区分 `server-id`，因此全网共享。
- 更新日志已读记录按“玩家 UUID + `server-id`”共享。
- `changelog.yml` 的完整内容不再需要手工复制：任一子服 reload 后提交的新版本会写入 MariaDB，并通过 Redis 通知其他子服；其他子服从 MariaDB 拉取并原子替换本地文件。Redis 不可用时由启动检查和定时轮询补偿。

`changelog.yml` 中的 `revision` 是判断是否需要显示的唯一版本依据：

```yaml
revision: 2
version-label: "2026.08.16"
```

每次修改需要重新展示的更新内容时，都必须把 `revision` 严格递增。不要回退或复用旧值；仅修改 `version-label` 或正文而不增加 `revision`，reload 会被视为同版本内容冲突，不会覆盖 MariaDB 中的权威公告。`version-label` 只负责展示，可以使用日期或自定义版本名。启用公告时 revision 至少为 `1`；禁用公告可使用 `0`，但不能为负数。

### 公告同步与 reload

编辑任一子服的 `changelog.yml` 后执行 `/kudialognotice reload`。插件会先用同一套 YAML/MiniMessage 校验读取内容，再按以下规则与 `<prefix>changelog_document` 仲裁：

- MariaDB 没有该公告时，当前文件作为首个权威版本写入。
- 本地 `revision` 高于数据库版本时，完整文件写入 MariaDB；提交成功后才发布 Redis 变更通知。
- 本地 `revision` 低于数据库版本时，数据库版本优先，插件把数据库中的完整文件原子写回本地并采用该配置。
- `revision` 相同且 SHA-256/正文相同是幂等操作；相同 revision 但正文不同会拒绝 reload，必须递增 revision 后再发布。

Redis 通知只携带公告标识、revision 和摘要，不携带 YAML 正文。收到通知的子服会从 MariaDB 读取并校验完整文件，因此 Redis 消息乱序、重复或短暂丢失不会让子服使用未经数据库确认的内容。文件写入先落到同目录临时文件并完成 flush，再执行原子替换；替换失败会保留旧文件。公告正文中的注释、格式和链接会随完整 YAML 同步，跨平台换行和 UTF-8 BOM 会在计算摘要时规范化。

## MiniMessage 与链接

`rules.yml`、`changelog.yml` 和 `messages.yml` 的显示文本支持 MiniMessage。原生 Dialog 和书本兜底均可使用 `open_url` 点击事件，例如：

```yaml
body:
  - |-
    <white>请阅读完整规则：</white>
    <click:open_url:'https://example.com/rules'><aqua><underlined>打开服规网页</underlined></aqua></click>
```

服规和更新日志界面的文本支持以下占位符：

| 占位符 | 内容 |
| --- | --- |
| `<player>` | 玩家名 |
| `<server>` | 当前 `server-id` |
| `<version>` | `version-label` |
| `<revision>` | 当前数字 revision |

`messages.yml` 的 `reload-failed` 另支持 `<error>`。配置中的 MiniMessage 标签必须闭合且语法有效；链接必须使用包含协议的完整 URL，例如 `https://...`。

## ViaVersion 与旧客户端

Minecraft 1.21.6（协议 771）是首个原生支持 Dialog 的客户端版本。插件默认对协议 771 及以上使用原生 Dialog，对更旧的 ViaVersion 客户端使用成书界面。

在 Velocity 的 ViaVersion 配置中保持以下选项开启（默认开启）：

```yaml
send-player-details: true
```

ViaVersion 会通过 `vv:proxy_details` 把代理所见的真实客户端协议发给后端，KUDialogNotice 据此选择界面。若该消息不可用，插件会依次尝试后端 ViaVersion API 和服务端协议值；这可能无法准确区分经代理转换的旧客户端，因此应确认代理设置正常。

书本兜底受旧客户端 UI 能力限制：没有原生 Dialog、真正的复选框或动态按钮状态；服规操作文字只放在最后一页，点击后仍由服务端校验会话。强制公告期间，关闭书本或尝试移动、聊天、交互及执行其他命令会触发重新打开。它能完成阅读和确认流程，但视觉及交互效果不会与原生 Dialog 完全一致。将 `clients.fallback-book-enabled` 设为 `false` 后，不支持原生 Dialog 的客户端会被拒绝进入。

## 原生 Dialog 的滚动限制

Folia/Paper 26.2 的 Dialog API 不提供玩家当前滚动位置或“已经滚动到底部”事件，按钮 API 也不支持运行时切换 enabled 状态。因此，纯服务端插件无法精确实现“滚动到底部瞬间自动启用按钮”；要获得这种精确行为必须配合客户端 Mod。

KUDialogNotice 的服务端替代方案是把确认复选框放在正文之后：同意与拒绝按钮始终可见，但玩家未勾选时，服务端会忽略点击并提示继续阅读。这个方案能验证玩家主动勾选，不能从技术上证明其实际滚动位置。

原生页面允许按 Esc 退出，避免插件热禁用后把玩家留在无法关闭的客户端页面中。插件正常运行且公告尚未完成时，玩家位移会被拦截但不会重建页面，以免下落或实体挤压反复重置复选框；交互、打开背包或执行其他命令会重新打开页面，因此不能借此绕过确认流程。

## 命令与权限

主命令为 `/kudialognotice`，别名 `/kudn`。

| 命令 | 说明 |
| --- | --- |
| `/kudialognotice reload` | 原子重载配置；存储配置变化时先验证新连接 |
| `/kudialognotice preview rules` | 当前玩家预览服规，不写入状态 |
| `/kudialognotice preview changelog` | 当前玩家预览更新日志，不写入状态 |
| `/kudialognotice status <玩家名或 UUID>` | 查询服规状态及当前 `server-id` 的已读 revision |

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `kudialognotice.admin` | OP | 使用全部管理命令 |
| `kudialognotice.bypass.rules` | 无 | 跳过服规流程 |
| `kudialognotice.bypass.changelog` | 无 | 跳过更新日志流程 |

`preview` 只能由玩家执行。`status` 只能查询已经写入 `<prefix>players` 表的玩家；名称按该玩家最近一次记录的名称解析。

## 配置提醒

- `display-delay-ticks: 30` 表示 AuthMe `LoginEvent` 后延迟 1.5 秒。
- `storage.redis.key-prefix` 只能包含字母、数字、`.`, `_`, `:` 和 `-`，并应在所有子服保持一致。
- `rules.yml` 和 `changelog.yml` 的 `body` 是列表；原生 Dialog 将每项作为正文块，书本兜底将每项作为一页。
- 更新日志仅在玩家当前已读 revision 小于配置 revision 时显示，并且必须点击“已知晓”。
- 编辑配置后执行 `/kudialognotice reload`；若新配置或新存储连接无效，旧配置仍继续工作。
