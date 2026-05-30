# FoliaCompat

> 让 Paper 插件无需修改即可在 Folia 上运行的高性能兼容中间件

[![Java 21](https://img.shields.io/badge/Java-21+-orange)](https://adoptium.net/)
[![Folia 1.21](https://img.shields.io/badge/Folia-1.21.11-green)](https://papermc.io/software/folia)
[![License: WTFPL](https://img.shields.io/badge/License-WTFPL-brightgreen.svg)](http://www.wtfpl.net/)

Folia 是 PaperMC 的区域化多线程 Minecraft 服务端，每个区域在独立线程上并行运行，打破了几乎所有 Bukkit/Paper 插件的核心假设。

**FoliaCompat 通过五层拦截机制，让旧插件无需修改任何代码即可在 Folia 上运行。** 关键指标：**0 个 CraftScheduler 错误。**

---

## 目录

- [为什么需要 FoliaCompat](#为什么需要-foliacompat)
- [快速开始](#快速开始)
- [工作原理](#工作原理)
- [兼容性测试](#兼容性测试)
- [BukkitScheduler API 覆盖](#bukkitscheduler-api-覆盖)
- [架构设计](#架构设计)
- [项目结构](#项目结构)
- [开发者 API](#开发者-api)
- [已知限制](#已知限制)

---

## 为什么需要 FoliaCompat

| Bukkit/Paper 假设 | Folia 实际情况 | 后果 |
|---|---|---|
| 全局唯一主线程 | 多个区域线程并行 | `isPrimaryThread()` 误判 |
| `Bukkit.getScheduler()` 返回全局调度器 | 需按上下文选择调度器 | `UnsupportedOperationException` |
| `static HashMap` 线程安全 | 多线程并发访问 | 数据竞争、ConcurrentModificationException |
| `plugin.yml` 无需 `folia-supported` | Folia 强制要求此字段 | 插件直接被拒绝加载 |
| `Scoreboard.registerNewTeam()` 随时可用 | 限制为全局 tick 线程 | `UnsupportedOperationException` |

没有 FoliaCompat，大多数 Paper 插件在 Folia 上要么无法加载，要么运行时崩溃。

---

## 快速开始

### 环境要求

- Java 21+
- Folia 1.20.4+（测试通过 1.21.11）

### 构建

```bash
git clone https://cnb.cool/zentx/folia-compat.git
cd folia-compat
./gradlew build
```

产物：`build/libs/folia-compat-1.1.0.jar`

### 安装

**必须同时作为 Java Agent 和插件使用**，两层配合才能实现完整拦截：

```bash
cp build/libs/folia-compat-1.1.0.jar plugins/
java -javaagent:plugins/folia-compat-1.1.0.jar -jar folia.jar --nogui
```

启动成功的标志：

```
[FoliaCompat-Agent] Patched 13 plugins with folia-supported: true
[FoliaCompat] Successfully injected CompatScheduler into CraftServer!
[FoliaCompat] All Bukkit.getScheduler() calls will now use CompatScheduler.
```

### 配置

首次启动自动生成 `plugins/FoliaCompat/config.yml`，所有模块默认启用：

```yaml
modules:
  scheduler-compat: true        # BukkitScheduler → Folia 调度器重定向
  thread-safe-state: true       # 线程安全状态容器
  entity-bridge: true           # 跨区域实体操作安全桥
  main-thread-proxy: true       # isPrimaryThread() 行为修正
  scoreboard-compat: true       # CraftScoreboard 兼容层
  thread-context-bridge: true   # 线程上下文桥接
```

---

## 工作原理

FoliaCompat 通过五层拦截，在插件不知情的情况下将其 API 调用适配到 Folia：

### 拦截流程

```
 旧插件调用                    FoliaCompat 拦截                         Folia 原生 API
 ───────────                  ─────────────────                       ──────────────

 Bukkit.getScheduler()  ──→  Layer 1: Unsafe 反射注入              ──→ RegionScheduler
        │                    Layer 2: ASM 字节码转换                     EntityScheduler
        │                    Layer 3: CompatScheduler 路由              GlobalRegionScheduler
        │                    Layer 4: YAML 补丁 (folia-supported)        AsyncScheduler
        │                    Layer 5: 兼容层模块 (记分板/上下文)
        └──────────────────────────────────────────────────────────→  0 个 UnsupportedOperationException
```

### 调度路由

| 上下文 | 路由目标 | 用途 |
|--------|---------|------|
| 有 Entity 上下文 | `EntityScheduler` | 实体所属区域执行 |
| 有 Location 上下文 | `RegionScheduler` | 目标位置区域执行 |
| 全局同步任务 | `GlobalRegionScheduler` | 全局 tick 线程执行 |
| 纯异步任务 | `AsyncScheduler` | 线程池执行 |
| 同步重复任务 | `GlobalRegionScheduler`（递归） | 自调度周期循环 |
| 异步重复任务 | `AsyncScheduler.runAtFixedRate` | 原生周期执行 |

---

## 兼容性测试

测试环境：**Folia 1.21.11-14 · Java 21 (Zulu) · FoliaCompat v1.1.0**

### 总览

| 结果 | 数量 | 占比 |
|------|------|------|
| ✅ 完全兼容 | 20 / 22 | 91% |
| ❌ 缺依赖 | 2 / 22 | 9% |
| 🔴 CraftScheduler 错误 | **0** | — |

### ✅ 完全兼容 (20)

| # | 插件 | 版本 | FoliaCompat 干预 |
|---|------|------|-----------------|
| 1 | LuckPerms | 5.5.53 | onLoad() Unsafe 注入（jar-in-jar 模式） |
| 2 | Vault | 1.7.3-b131 | ASM 字节码转换 |
| 3 | EssentialsX | 2.21.2 | YAML 补丁 |
| 4 | GriefPrevention | 16.18.7 | YAML 补丁 |
| 5 | BentoBox | 3.16.2 | YAML 补丁 + ASM 转换 7 个类 |
| 6 | Geyser | 2.10.0 | YAML 补丁 |
| 7 | PlaceholderAPI | 2.12.2 | 原生 Folia 支持 |
| 8 | ProtocolLib | 5.4.0 | 原生 Folia 支持 |
| 9 | ajLeaderboards | 2.11.0 | ASM 转换 2 个类 |
| 10 | NoEmotecraft | 2.5.2 | YAML 补丁 |
| 11 | NBTAPI | 2.15.7 | — |
| 12 | DeathInvLimiter | 1.17.2 | YAML 补丁 |
| 13 | DeviledEggs | 1.1.0 | YAML 补丁 |
| 14 | CreeperTracker | 1.12.2 | YAML 补丁 |
| 15 | CustomNick | 1.0 | YAML 补丁 |
| 16 | MobSwitch | 1.0.0 | YAML 补丁 |
| 17 | PvPMoney | 1.2 | YAML 补丁 |
| 18 | RZWartung | 1.0 | YAML 补丁 |
| 19 | VoidTP | 1.4 | YAML 补丁 |
| 20 | FoliaCompat | 1.1.0 | — |

### ❌ 缺少依赖 (2)

| 插件 | 缺失依赖 | 解决方案 |
|------|---------|----------|
| BuffSystem | Pouvoir | 未找到可靠下载源 |
| CustomCrafting | WolfyUtilities | 下载后即可通过 |

### RCON 命令测试

| 插件 | 命令 | 结果 |
|------|------|------|
| EssentialsX | `/essentials version` | ✅ v2.21.2 |
| EssentialsX | `/bal`, `/kit` | ✅ 正常 |
| Vault | `/vault-info` | ✅ 经济→EssentialsX，权限+聊天→LuckPerms |
| LuckPerms | (API) | ✅ Vault/EssentialsX 确认接入 |
| Geyser | `/geyser version` | ✅ v2.10.0 |
| ProtocolLib | `/protocol version` | ✅ v5.4.0 |
| PlaceholderAPI | `/papi list` | ✅ 2 扩展已注册 |
| ajLeaderboards | `/ajleaderboards version` | ✅ v2.11.0 |
| BentoBox | `/bentobox version` | ✅ v3.16.2 |
| GriefPrevention | `/gpreload` | ✅ 重载成功 |
| DeviledEggs | `/de` | ✅ 命令正常 |
| CreeperTracker | `/ct` | ✅ 命令正常 |
| MobSwitch | `/mobswitch` | ✅ 命令正常 |
| CustomNick | `/nick` | ✅ 仅限玩家 |
| VoidTP | `/voidtp` | ✅ 仅限玩家 |

### v1.1.0 新增修复状态

| 修复目标 | 方案 | 状态 |
|---------|------|------|
| `CraftScoreboard.registerNewTeam()` 异常 | ScoreboardCompat | ✅ 代码完成，待 PowerRanks jar 验证 |
| 插件缺少 plugin.yml 被拒绝 | PluginYamlPatcher 自动创建 | ✅ 代码完成，待 BetterTeams jar 验证 |
| `Level.getCurrentWorldData()` null | ThreadContextBridge | ❌ NMS 层问题，当前方案无效 |

---

## BukkitScheduler API 覆盖

| API | 状态 | 实现方式 |
|-----|------|----------|
| `runTask` / `runTaskLater` | ✅ | GlobalRegionScheduler |
| `runTaskAsynchronously` / `runTaskLaterAsynchronously` | ✅ | AsyncScheduler |
| `runTaskTimer` | ✅ | 自调度递归 + GlobalRegionScheduler |
| `runTaskTimerAsynchronously` | ✅ | AsyncScheduler.runAtFixedRate |
| `scheduleSyncDelayedTask` / `scheduleSyncRepeatingTask` | ✅ | 委托 runTask* |
| `cancelTask(id)` | ✅ | TaskRegistry 双索引查询 |
| `cancelTasks(plugin)` | ✅ | TaskRegistry 按插件批量取消 |
| `isQueued(id)` | ✅ | TaskRegistry 查询 |
| `isCurrentlyRunning(id)` | ✅ | TaskRegistry 查询 |
| `getPendingTasks()` | ✅ | TaskRegistry 过滤 |
| `callSyncMethod` | ✅ | CompletableFuture + GlobalRegionScheduler |
| `getMainThreadExecutor` | ✅ | 委托 GlobalRegionScheduler |

---

## 架构设计

### 反射注入层（核心）

`onLoad()` 阶段通过 `sun.misc.Unsafe` 替换 `CraftServer.scheduler`（private final）字段为 `CompatScheduler`。Java 17+ 封杀了 `Field.modifiers` 反射，`Unsafe.putObject()` 是唯一可靠方案。在 `onLoad()` 而非 `onEnable()` 注入，确保 LuckPerms 等早期调用者也能获取正确的调度器。

### 字节码转换层

Agent premain 注册 ClassFileTransformer，ASM `AdviceAdapter` 重写：

- `Bukkit.getScheduler()` → `CompatSchedulerHolder.getScheduler()`
- `Bukkit.isPrimaryThread()` → `MainThreadProxy.isPrimaryThread()`
- `Scoreboard.registerNewTeam(name)` → `ScoreboardCompat.safeRegisterTeam(name)`

快速扫描跳过 99% 无关类，排除 `org/bukkit/`、`net/minecraft/` 等平台类，使用 `COMPUTE_MAXS` 避免 ClassLoader 问题。

### YAML 补丁层

Agent premain 扫描 `plugins/` 目录，为缺失 `folia-supported: true` 的插件自动注入，支持 `plugin.yml`、`paper-plugin.yml`，无 YAML 文件的 jar 尝试自动创建最小配置。跳过 FoliaCompat 自身和已知原生插件。

### 自调度重复任务

Folia 的 `GlobalRegionScheduler` 没有 `runAtFixedRate`，CompatScheduler 通过递归调度实现：每次执行后重新调度下一次，`cancel()` 打断递归链。

### 边界保护

- `delay ≤ 0` → 回退为立即执行（Folia 拒绝 ≤ 0）
- `period` → `Math.max(1, period)` 保证最小间隔

---

## 项目结构

```
src/main/java/com/foliacompat/
├── FoliaCompatPlugin.java          # 主入口：Unsafe 反射注入 + 6 模块开关
├── agent/
│   ├── FoliaCompatAgent.java       # Java Agent premain
│   ├── SchedulerTransformer.java   # ASM 字节码转换
│   ├── PluginYamlPatcher.java      # YAML 补丁（支持 paper-plugin.yml + 自动创建）
│   └── FoliaPluginPatcher.java     # SpigotPluginProviderFactory 补丁
├── scheduler/
│   ├── CompatScheduler.java        # BukkitScheduler 完整实现
│   ├── CompatBukkitTask.java       # BukkitTask 包装
│   ├── CompatSchedulerHolder.java  # 字节码注入目标
│   ├── TaskRegistry.java           # 双索引任务注册中心
│   └── SchedulerRouter.java        # 上下文→Folia 调度器路由
├── state/
│   ├── GlobalStateStore.java       # 线程安全 ConcurrentHashMap
│   ├── ThreadSafePlayerData.java   # 线程安全玩家数据
│   └── ThreadSafeWorldData.java    # 线程安全世界数据
├── entity/
│   ├── CrossRegionEntityBridge.java # 跨区域实体桥
│   └── SafeEntityAccessor.java      # 安全实体访问
├── compat/
│   ├── ScoreboardCompat.java        # 记分板兼容层
│   └── ThreadContextBridge.java     # 线程上下文桥接
├── thread/
│   ├── MainThreadProxy.java        # isPrimaryThread 修正
│   └── RegionAwareRunnable.java    # 区域感知 Runnable
└── util/
    ├── FoliaDetector.java          # Folia 环境检测
    └── ReflectionUtil.java         # 反射工具（带缓存）
```

---

## 开发者 API

### 线程安全状态存储

```java
// 替代 static Map<UUID, PlayerData> playerData = new HashMap<>();
GlobalStateStore<String, PlayerData> store = GlobalStateStore.create("playerData");
store.put(uuid.toString(), new PlayerData(...));
store.compute(uuid.toString(), (key, current) -> {
    current.addBalance(10);
    return current;
});
```

### 跨区域实体操作

```java
CrossRegionEntityBridge.safeTeleport(plugin, entity, destination);
CrossRegionEntityBridge.batchEntityOperation(plugin, entities, entity -> {
    entity.setHealth(entity.getHealth() + 1);
});
```

---

## 已知限制

| 限制 | 说明 | 影响插件 |
|------|------|----------|
| NMS 线程模型 | `Level.getCurrentWorldData()` 返回 null | Multiverse-Core |
| 平台检测拒绝 | 插件启动时检查服务端类型并拒绝 | FastAsyncWorldEdit（需 Folia 原生版） |
| NMS 内部类反射 | 插件直接反射 Folia 修改过的 NMS 类 | dynmap、Terra |

以下插件已有 Folia 原生支持，**无需 FoliaCompat**：ProtocolLib、PlaceholderAPI、TAB、GrimAC、Chunky、WorldGuard、Orebfuscator、Towny、SkinsRestorer、CoreProtect、VeinMiner、Plan、GSit、AxGraves、DiscordSRV、PacketEvents、ChunkyBorder、AuthMe、Terra、Citizens、FastAsyncWorldEdit。

---

## 依赖

- Java 21+
- Folia 1.20.4+（向下兼容）
- ASM 9.6（shade 进 jar，relocate 到 `com.foliacompat.libs.asm`）

## 许可证

基于 [WTFPL](https://en.wikipedia.org/wiki/WTFPL) 协议开源。
