# 会话共享与一致性组件

多实例（多节点）部署下的会话共享组件：会话数据集中存放在共享存储中，
任意实例都能读写；会话带单调递增的版本号，支持乐观并发冲突检测与两种
冲突策略；支持实例扩缩容、会话过期清理与运行统计。节点与存储均用内存
对象模拟（无外部依赖）。

## 运行方式

```bash
./mvnw -q verify      # 或 mvn -q verify
```

当前共 29 个测试，全部通过。

## 架构

```
              ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
              │ SessionNode  │ │ SessionNode  │ │ SessionNode  │
              │  (实例 1..n) │ │              │ │              │
              └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
                     │   create/read/update/invalidate  │
                     ▼                ▼                 ▼
              ┌──────────────────────────────────────────────┐
              │           InMemorySessionStore               │
              │  （共享后端的内存模拟，如 Redis / 数据库）      │
              │  ConcurrentHashMap<String, Session>          │
              └──────────────────────────────────────────────┘
```

| 类型 | 职责 |
|------|------|
| `Session` | 不可变会话快照：id、属性表、版本号、创建/修改时间、TTL。每次更新生成版本 +1 的新对象 |
| `SessionLookup` | 读取结果：`Found(完整版本)` 或 `NotFound(明确不存在)`（sealed interface） |
| `InMemorySessionStore` | 共享存储：创建/读取/更新/失效、按版本 CAS 更新、过期清理、冲突与过期计数 |
| `SessionNode` | 服务实例：写操作直达共享存储；粘性模式额外带本地读缓存 |
| `SessionCluster` | 集群：管理节点扩容/缩容、触发过期清理、汇总 `SessionStats` |
| `ConflictStrategy` | 冲突策略：`REJECT_CONFLICT` / `LAST_WRITE_WINS` |
| `NodeMode` | `STICKY`（粘性）/ `NON_STICKY`（非粘性） |

### 会话生命周期与版本号

- 创建：版本号从 **1** 开始。
- 更新：采用乐观并发控制，调用方携带“期望版本号”。
  存储端在 `ConcurrentHashMap.compute` 中**原子地**比对版本并整体替换
  `Session` 引用。因为 `Session` 不可变、替换是原子的，并发读者只能看到
  更新前或更新后的完整版本，永远读不到属性更新到一半的混合状态
  （由 `CompleteVersionReadTest` 中 1 写 4 读、300 次更新的压测验证）。
- 失效：`invalidateSession` 立即删除。
- 过期：按 `lastModifiedAt + ttl` 判定；支持后台主动清理（`sweepExpiredSessions`）
  与读取时惰性过期两种方式，过期/清理后读取返回明确的 `NotFound`。

## 粘性与非粘性模式（一致性要求）

- **非粘性（NON_STICKY，强一致）**：请求可落到任意节点，每次读取都访问
  共享存储，因此任何节点都能读到最新的已提交完整版本。这是对一致性最
  稳妥的模式，代价是没有本地缓存加速。
- **粘性（STICKY，最终一致/依赖路由）**：负载均衡把同一会话在其存活期内
  始终路由到固定节点，该节点用本地缓存加速读取。一致性要求：
  - 路由必须真正“粘住”：一旦别的节点修改了会话，本节点缓存会变陈旧；
    生产实现中需要配合会话失效广播/缓存失效，或在故障转移时让新节点
    回源共享存储重新装载。
  - 本组件中粘性节点自己的写入会同步刷新本地缓存；缓存未命中时回源
    共享存储；节点下线（缩容）时清空缓存，数据仍在共享存储中。

## 并发更新冲突策略

两个节点基于同一个版本并发更新同一会话（期望版本相同）时，先提交者
成功，后提交者与存储中当前版本不一致，即检测为版本冲突。策略二选一，
按节点配置：

1. **`REJECT_CONFLICT`（拒绝后者）**：后来的更新被拒绝，抛出
   `VersionConflictException`（含期望版本与实际版本），存储内容不变。
   调用方应重新读取最新版本、合并后重试（测试 `afterConflictAWriterCanRetryWithTheLatestVersion`
   演示了这一流程）。适合会话状态必须可追溯、禁止静默覆盖的场景。
2. **`LAST_WRITE_WINS`（按时间取新）**：冲突时比较“本次写入时间”与
   “已存储版本的最后修改时间”：
   - 本次写入时间**更晚** → 本次写入生效（版本继续 +1）；
   - 本次写入时间**更早或相等** → 本次写入被丢弃，保留已存储版本。
   适合节点时钟可信、允许最后写入覆盖的场景。

无论哪种策略，每检测到一次版本冲突，`versionConflicts` 统计加 1。

## 扩缩容

- 会话数据只存在共享存储中，节点不拥有数据。
- **扩容**：`scaleOut(mode, strategy)` 加入新实例，新实例立即可读全部
  历史会话（共享存储回源）。
- **缩容**：`scaleIn(nodeId)` 下线实例，仅清空其本地读缓存；其上的会话
  全部保留在共享存储中，其余实例继续可读，不丢会话。
- 每次扩容/缩容分别累计到 `scaleOuts` / `scaleIns`。

## 统计（SessionStats）

`cluster.stats()` 返回快照：

| 字段 | 含义 |
|------|------|
| `activeSessions` | 当前未过期会话数 |
| `versionConflicts` | 累计版本冲突次数（两种策略都计数） |
| `scaleOuts` / `scaleIns` | 累计扩容 / 缩容次数 |
| `expiredSessions` | 累计过期清理会话数（主动清理 + 读取时惰性过期） |

## 使用示例

```java
SessionCluster cluster = new SessionCluster(Clock.systemUTC());
SessionNode nodeA = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.REJECT_CONFLICT);
SessionNode nodeB = cluster.scaleOut(NodeMode.NON_STICKY, ConflictStrategy.LAST_WRITE_WINS);

Session s = nodeA.createSession("s1", Map.of("user", "alice"), Duration.ofMinutes(30));
Session v2 = nodeB.updateSession("s1", s.version(), Map.of("user", "alice", "cart", 2));

SessionLookup lookup = nodeA.read("s1");        // Found(v2) 或 NotFound("s1")
nodeA.invalidateSession("s1");                  // 立即失效
cluster.sweepExpiredSessions();                 // 主动清理过期会话
SessionStats stats = cluster.stats();
```

## 测试覆盖

| 测试类 | 覆盖需求 |
|--------|----------|
| `SessionStoreCrudTest` | 创建/读取/更新/失效、版本号递增、不可变属性 |
| `CompleteVersionReadTest` | 任意节点可读；并发读写只观察到完整版本 |
| `StickyVsNonStickyTest` | 粘性 / 非粘性两种模式的一致性语义 |
| `ConflictStrategyTest` | 两种冲突策略：拒绝后者、按时间取新（含新/旧时间两个方向）与冲突后重试 |
| `ScaleInOutTest` | 扩容新实例读全部会话、缩容不丢会话 |
| `SessionExpiryTest` | 主动清理、惰性过期、清理后 `NotFound`、统计 |
| `ClusterStatsTest` | 五项统计计数 |

---

## 原始任务说明

Pair-wise GSB 标注任务仓库（第 18 批 / 298）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

### 任务提示词原文

我们的服务多实例部署，用户会话要在实例间共享，实例扩容时不能丢会话。请从零实现一个会话共享与一致性组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ），节点与存储用内存对象模拟。要求：1) 支持会话创建、读取、更新与失效，会话带版本号；2) 支持多节点共享：任意节点都能读到会话，读到的内容必须是某个完整版本；3) 支持会话粘性与非粘性两种模式，说明各自对一致性的要求；4) 支持并发更新冲突：同一会话在不同节点被并发更新时，按版本号检测冲突并按策略处理（拒绝后者或按时间取新），策略写进 README；5) 支持实例扩容与缩容：扩容后新实例能读取全部会话，缩容时其上的会话不得丢失；6) 支持会话过期清理，清理后读取要返回明确的不存在结果；7) 提供统计：会话数、版本冲突次数、扩容缩容次数与过期清理数；8) 测试覆盖创建读取更新、完整版本读取、并发冲突两种策略、扩缩容不丢会话、过期清理与统计；`mvn -q verify` 一条命令跑通。
