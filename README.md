# FoliaCompat

> 让 Paper 插件无需修改即可在 Folia 上运行的高性能兼容中间件

[![Java 21](https://img.shields.io/badge/Java-21+-orange)](https://adoptium.net/)
[![Folia 1.21](https://img.shields.io/badge/Folia-1.21.11-green)](https://papermc.io/software/folia)
[![License: WTFPL](https://img.shields.io/badge/License-WTFPL-brightgreen.svg)](http://www.wtfpl.net/)

Folia 是 PaperMC 的区域化多线程 Minecraft 服务端，每个世界区域在独立线程上并行运行。这打破了几乎所有 Bukkit/Paper 插件的核心假设 —— 全局主线程、统一调度器、线程安全状态。

**FoliaCompat 通过四层拦截机制，让旧插件无需修改任何代码即可在 Folia 上运行。**

Top 50 主流插件测试 **36/50 完全兼容 (72%)**，0 个调度器错误。

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

| Bukkit/Paper 假设 | 实际情况 | 后果 |
|---|---|---|
| 全局唯一主线程 | Folia 有多个区域线程 | `isPrimaryThread()` 误判 |
| `Bukkit.getScheduler()` 返回全局调度器 | Folia 需要按上下文选择调度器 | `UnsupportedOperationException` 崩溃 |
| `static HashMap` 线程安全 | 多线程并发访问 | 数据竞争、ConcurrentModificationException |
| `plugin.yml` 无需 `folia-supported` | Folia 要求此字段 | 插件直接被拒绝加载 |

没有 FoliaCompat，大多数 Paper 插件在 Folia 上要么无法加载，要么运行时崩溃。

---

## 快速开始

### 前提

- Java 21+
- Folia 1.20.4+ (测试通过 1.20.4 / 1.21.11)

### 构建

```bash
git clone https://github.com/your-repo/folia-compat.git
cd folia-compat
./gradlew build
```

产物：`build/libs/folia-compat-1.0.0.jar`

### 安装

**必须同时作为 Java Agent 和插件使用**，两层配合才能实现完整拦截：

```bash
# 1. 复制到 plugins 目录（反射注入层 + 运行时模块）
cp build/libs/folia-compat-1.0.0.jar plugins/

# 2. 启动时指定为 Java Agent（字节码层 + YAML 补丁层）
java -javaagent:plugins/folia-compat-1.0.0.jar -jar folia.jar --nogui
```

启动日志中看到以下输出即表示成功：

```
[FoliaCompat-Agent] PluginYamlPatcher: Patched 12 plugins with folia-supported: true
[FoliaCompat] Successfully injected CompatScheduler into CraftServer!
[FoliaCompat] All Bukkit.getScheduler() calls will now use CompatScheduler.
```

### 配置

首次启动自动生成 `plugins/FoliaCompat/config.yml`：

```yaml
modules:
  scheduler-compat: true    # BukkitScheduler → Folia 调度器重定向
  thread-safe-state: true   # 线程安全状态容器
  entity-bridge: true       # 跨区域实体操作安全桥
  main-thread-proxy: true   # isPrimaryThread() 行为修正
```

---

## 工作原理

```
                        ┌──────────────────────────────────────────────────┐
                        │              FoliaCompat 四层拦截                  │
                        ├──────────────────────────────────────────────────┤
                        │                                                  │
  旧插件代码             │  Layer 1: 反射注入 (onLoad)                       │  Folia 原生 API
  ──────────             │  ─────────────────────────                       │  ──────────────
                         │  sun.misc.Unsafe 替换                             │
  Bukkit.getScheduler()  │  CraftServer.scheduler → CompatScheduler    ──→  │  RegionScheduler
         │               │                         ↗                       │  EntityScheduler
         ├──────────────→│  Layer 2: 字节码转换 (Agent)                     │  GlobalRegionScheduler
         │               │  Bukkit.getScheduler() ──→ CompatSchedulerHolder │  AsyncScheduler
         │               │  Bukkit.isPrimaryThread() ──→ MainThreadProxy   │
         │               │                                                  │
         │               │  Layer 3: 调度器适配                              │
         │               │  CompatScheduler 完整实现 BukkitScheduler         │
         │               │  + TaskRegistry 双索引注册中心                    │
         │               │  + 自调度递归实现 runTaskTimer                     │
         │               │                                                  │
         │               │  Layer 4: YAML 补丁 (premain)                    │
         │               │  为插件 jar 注入 folia-supported: true           │
         └──────────────→│                                                  │
```

### 调度路由策略

CompatScheduler 根据任务上下文自动路由到 Folia 对应的调度器：

```
有 Entity 上下文   →  EntityScheduler          在实体所属区域执行
有 Location 上下文  →  RegionScheduler          在目标位置区域执行
全局同步任务       →  GlobalRegionScheduler     在全局 tick 线程执行
纯异步任务         →  AsyncScheduler            在线程池执行
同步重复任务       →  GlobalRegionScheduler     自调度递归实现周期循环
异步重复任务       →  AsyncScheduler            原生 runAtFixedRate
```

---

## 兼容性测试

在 Folia 1.21.11-6 (Java 21) 上测试 **50 个主流 Paper 插件**。

### 总览

| 结果 | 数量 | 占比 |
|------|------|------|
| ✅ 完全兼容 | 36 | 72% |
| ⚠️ 部分兼容 | 3 | 6% |
| ❌ 不兼容 | 11 | 22% |

关键指标：**0 个 CraftScheduler 错误**，**0 个 jar-in-jar 兼容问题**，**50 个类成功 ASM 转换**

### ✅ 完全兼容 (36)

| 插件 | 版本 | 拦截方式 | 插件 | 版本 | 拦截方式 |
|------|------|----------|------|------|----------|
| LuckPerms | 5.5.17 | 反射 (jar-in-jar) | PlaceholderAPI | 2.12.2 | 反射注入 |
| Vault | 1.7.3 | 字节码 + 反射 | SkinsRestorer | 15.12.0 | 字节码 + 反射 |
| EssentialsX | 2.21.2 | 反射 (jar-in-jar) | Citizens | 2.0.42 | 字节码 + 反射 |
| EssentialsX Spawn | 2.21.2 | 反射注入 | Towny | 0.103.0.0 | 字节码 + 反射 |
| GriefPrevention | 16.18.4 | 字节码 + 反射 | TAB | 5.0.7 | 字节码 + 反射 |
| CoreProtect | 23.2 | 字节码 + 反射 | Chunky | 1.4.40 | 反射注入 |
| ChunkyBorder | 1.2.23 | 反射注入 | GrimAC | 2.3.74 | 反射注入 |
| PacketEvents | 2.12.1 | 反射注入 | VeinMiner | 2.6.0 | 反射注入 |
| AuthMe | 5.7.0 | 字节码 + 反射 | Plan | 5.7-b3341 | 反射注入 |
| Geyser | 2.10.0 | 反射注入 | SetSpawn | 3.1 | 反射注入 |
| SetHome | 6.2 | 字节码 + 反射 | ClearLag | 1.7.8 | 反射注入 |
| ajLeaderboards | 2.11.0 | 反射注入 | GSit | 3.4.1 | 反射注入 |
| TAB-Bridge | 6.2.1 | 反射注入 | InteractionVisualizer | 2026.1.1 | 反射注入 |
| tps-hud | 1.9.0 | 字节码 + 反射 | BuildPaste | 1.11.1 | 反射注入 |
| ClickMobs | 1.3.1 | 字节码 + 反射 | JustTPA | 20250220c | 反射注入 |
| VillagerInABukkit | 1.5.0 | 反射注入 | AxGraves | 1.28.0 | 字节码 + 反射 |
| ItemEdit | 3.7.8 | 字节码 + 反射 | ClickVillagers | 1.6.2 | 反射注入 |
| ItemSwapper | 0.2.1 | 反射注入 | FokusAPI | 4.2 | 反射注入 |

### ⚠️ 部分兼容 (3)

| 插件 | 版本 | 状态 | 问题 |
|------|------|------|------|
| PowerRanks | 1.10.10 | 启用成功 | `CraftScoreboard.registerNewTeam()` 抛 `UnsupportedOperationException`（Folia 限制记分板 API） |
| NoEmotecraft | 2.5.2 | 启用成功 | 初始化警告，不影响运行 |
| NexusCore | 1.12.2 | 启用成功 | 原为 1.12.2 设计，功能有限 |

### ❌ 不兼容 (11)

| 插件 | 版本 | 原因 | 类别 |
|------|------|------|------|
| ProtocolLib | 5.3.0 | NMS `ProtocolInfo$a` 不存在 | NMS 不兼容 |
| FastAsyncWorldEdit | 2.15.1 | `NoCapablePlatformException` | 平台检测 |
| WorldGuard | 7.0.16 | 依赖 WorldEdit（已失败） | 依赖链 |
| dynmap | 3.7-beta-8 | 版本映射不兼容 | NMS 不兼容 |
| Multiverse-Core | 5.6.2 | `getCurrentWorldData()` 返回 null | Folia 线程模型 |
| DiscordSRV | 1.30.5 | 无 bot token（非兼容问题） | 配置缺失 |
| Terra | 6.6.6-BETA | NMS 绑定不存在 v1_21_11 | NMS 不兼容 |
| Orebfuscator | 5.6.0 | 依赖 ProtocolLib（已禁用） | 依赖链 |
| voicemessages | 1.0.12 | 依赖加载失败 | 依赖链 |
| CustomCrafting | 4.16.11 | 缺少 WolfyUtils | 依赖链 |
| BetterTeams | 1.0 | jar 不含 plugin.yml | 打包错误 |

### 失败原因分析

```
NMS 不兼容 (4)  ─── 插件直接访问 Folia 修改过的 NMS 内部类，超出调度兼容范围
                    → ProtocolLib / dynmap / Terra / FAEW

依赖链失败 (4)  ─── 上游插件失败导致下游无法启动
                    → WorldGuard→WorldEdit / Orebfuscator→ProtocolLib
                      CustomCrafting→WolfyUtils / voicemessages

Folia 线程模型 (1) ── 在非区域线程访问区域数据
                    → Multiverse-Core

非兼容性问题 (2) ─── 配置缺失或打包错误
                    → DiscordSRV / BetterTeams
```

> **真正与 FoliaCompat 调度层相关的失败仅 1 个** (Multiverse-Core)，其余均为 NMS 依赖或插件自身问题。

### 运行时命令测试

通过 RCON 对每个兼容插件的命令进行实际执行测试，验证插件不仅在 Folia 上加载成功，而且运行时功能正常：

| 插件 | 测试命令 | 结果 |
|------|----------|------|
| **LuckPerms** | `/lp version` `/lp info` `/lp listgroups` `/lp listpermissions` | ✅ 4/4 |
| **Towny** | `/towny version` `/towny time` `/towny map` `/towny new day` | ✅ 4/4 |
| **EssentialsX** | `/essentials version` `/essentials info` `/essentials god` | ✅ 3/3 |
| **PlaceholderAPI** | `/papi info` `/papi list` `/papi parse me %server_online%` | ✅ 3/3 |
| **CoreProtect** | `/co version` `/co status` `/co lookup t:1d` | ✅ 3/3 |
| **Citizens** | `/npc help` `/npc type` `/npc list` | ✅ 3/3 |
| **GriefPrevention** | `/griefprevention` `/claimlist` `/abandonclaim` | ✅ 3/3 |
| **GrimAC** | `/grim version` `/grim alerts` `/grim debug` | ✅ 3/3 |
| **VeinMiner** | `/veinminer version` `/veinminer help` `/veinminer reload` | ✅ 3/3 |
| **AuthMe** | `/authme version` `/authme help` `/authme reload` | ✅ 3/3 |
| **Plan** | `/plan version` `/plan analyze` `/plan reload` | ✅ 3/3 |
| **Geyser** | `/geyser version` `/geyser list` `/geyser reload` | ✅ 3/3 |
| **ClearLag** | `/clearlag version` `/clearlag check` `/clearlag reload` | ✅ 3/3 |
| **AxGraves** | `/axgraves version` `/axgraves help` `/axgraves reload` | ✅ 3/3 |
| **GSit** | `/gsit version` `/gsit help` `/gsit reload` | ✅ 3/3 |
| **Chunky** | `/chunky version` `/chunky list` `/chunky cancel` | ✅ 3/3 |
| **TAB** | `/tab parse &aTest` `/tab reload` | ✅ 2/2 |
| **Vault** | `/vault` (API 提供者，无交互命令) | ✅ 1/1 |
| **SkinsRestorer** | `/skinsr` | ✅ 1/1 |
| **SetSpawn** | `/setspawn version` | ✅ 1/1 |
| **SetHome** | `/sethome version` `/sethome list` | ✅ 2/2 |
| **ajLeaderboards** | `/ajleaderboards help` `/ajleaderboards reload` | ✅ 2/2 |
| **BuildPaste** | `/buildpaste version` `/buildpaste reload` | ✅ 2/2 |
| **ClickMobs** | `/clickmobs version` `/clickmobs reload` | ✅ 2/2 |
| **ChunkyBorder** | `/chunkyborder` `/chunkyborder list` | ✅ 2/2 |
| **ItemEdit** | `/itemedit version` `/itemedit reload` | ✅ 2/2 |
| **JustTPA** | `/tpa` | ✅ 1/1 |
| **ClickVillagers** | `/clickvillagers version` | ✅ 1/1 |
| **EssentialsXSpawn** | `/spawn version` | ✅ 1/1 |
| **FoliaCompat** | `/foliacompat status` `/foliacompat info` `/foliacompat reload` | ✅ 3/3 |
| **PowerRanks** | `/pr help` `/powerranks` `/pr list` | ✅ 3/3 |
| **InteractionVisualizer** | `/iv` | ⚠️ 1/2 (`/iv help` 不识别) |

**命令测试结果：31/32 插件命令完全可用 (97%)**，仅 InteractionVisualizer 的 `/iv help` 子命令未注册

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

### 反射注入层 (核心)

```
onLoad() 阶段:
  CraftServer.scheduler (private final)
    │
    │  sun.misc.Unsafe.objectFieldOffset()
    │  unsafe.putObject(server, offset, compatScheduler)
    ▼
  CompatScheduler  ←  所有 Bukkit.getScheduler() 调用返回此实例
```

为什么选择 `Unsafe` 而非反射修改 `Field.modifiers`？Java 17+ 封装了 `Field.modifiers`，Java 21 直接报错。`Unsafe.putObject()` 绕过 final 和 access 限制，是目前唯一可靠的方案。

为什么注入在 `onLoad()` 而非 `onEnable()`？某些插件 (LuckPerms) 在自己的 `onEnable()` 中调用 `Bukkit.getScheduler()`，必须在更早的时机完成注入。

### 字节码转换层 (优化)

```
Java Agent premain:
  ClassFileTransformer.transform()
    │
    ├─ 快速扫描: indexOf("getScheduler") / indexOf("isPrimaryThread")
    │  不包含 → 跳过（99% 的类直接跳过）
    │
    ├─ 排除: org/bukkit/, net/minecraft/, com/mojang/ 等
    │  避免在平台 ClassLoader 中引用 com.foliacompat 类
    │
    └─ 转换: AdviceAdapter 重写 INVOKESTATIC 指令
       Bukkit.getScheduler()      →  CompatSchedulerHolder.getScheduler()
       Bukkit.isPrimaryThread()   →  MainThreadProxy.isPrimaryThread()
```

使用 `COMPUTE_MAXS`（而非 `COMPUTE_FRAMES`）避免 ClassLoader 加载引用类导致 `LinkageError`。

### YAML 补丁层

Folia 在加载插件时检查 `plugin.yml` 是否包含 `folia-supported: true`，缺失则拒绝加载。PluginYamlPatcher 在 Agent premain 阶段（JVM 启动最早时机）自动处理：

1. 扫描 `plugins/` 目录所有 `.jar`
2. 读取 `plugin.yml`，检查是否已有 `folia-supported`
3. 缺失则在 `api-version` 行后注入 `folia-supported: true`
4. 临时文件 + 原子移动重写 jar

### 自调度重复任务

Folia 的 `GlobalRegionScheduler` 没有 `runAtFixedRate`。CompatScheduler 通过递归调度实现：

```
runTaskTimer(plugin, task, delay, period)
  → runGlobalTaskDelayed(delay)
      → execute task
      → runGlobalTaskDelayed(period)   ← 递归
          → execute task
          → runGlobalTaskDelayed(period)
              ...
              直到 cancel() 打断递归
```

### BukkitRunnable 拦截

`BukkitRunnable.runTask()` / `runTaskTimer()` / `cancel()` 内部调用 `Bukkit.getScheduler()`。反射注入层替换 `CraftServer.scheduler` 后，这些调用自然返回 `CompatScheduler`，无需额外处理。

> **设计决策**：早期版本曾将 `BukkitRunnable` 加入 ASM 白名单，但插件 ClassLoader 无法访问 `com.foliacompat` 类，导致 `NoClassDefFoundError`。移除白名单后，反射注入层已完全覆盖。

### 边界条件保护

Folia 的 `runDelayed` 方法拒绝 `delay ≤ 0`，但 Bukkit 插件常使用 `delay=0` 表示"下一 tick"：

```java
// SchedulerRouter: delay ≤ 0 时回退为立即执行
if (delayTicks <= 0) {
    scheduler.run(plugin, task);  // 立即执行
    return;
}

// CompatScheduler: 重复任务 period 至少为 1
long safePeriod = Math.max(1, period);
```

---

## 项目结构

```
folia-compat/
├── build.gradle.kts
└── src/main/java/com/foliacompat/
    ├── FoliaCompatPlugin.java          # 主插件入口 (Unsafe 反射注入)
    ├── agent/
    │   ├── FoliaCompatAgent.java       # Java Agent premain
    │   ├── SchedulerTransformer.java   # ASM 字节码转换器
    │   ├── PluginYamlPatcher.java      # plugin.yml folia-supported 注入
    │   └── FoliaPluginPatcher.java     # 插件补丁工具
    ├── scheduler/
    │   ├── CompatScheduler.java        # BukkitScheduler 完整实现
    │   ├── CompatBukkitTask.java       # BukkitTask 包装 (AtomicReference)
    │   ├── CompatSchedulerHolder.java  # 字节码注入目标 + shutdown
    │   ├── TaskRegistry.java           # 任务注册中心 (双索引)
    │   └── SchedulerRouter.java        # 调度器路由
    ├── state/
    │   ├── GlobalStateStore.java       # 全局 ConcurrentHashMap 存储
    │   ├── ThreadSafePlayerData.java   # 线程安全玩家数据
    │   └── ThreadSafeWorldData.java    # 线程安全世界数据
    ├── entity/
    │   ├── CrossRegionEntityBridge.java # 跨区域实体桥
    │   └── SafeEntityAccessor.java      # 安全实体访问
    ├── thread/
    │   ├── MainThreadProxy.java        # isPrimaryThread 修正 (反射缓存)
    │   └── RegionAwareRunnable.java    # 区域感知 Runnable
    └── util/
        ├── FoliaDetector.java          # Folia 环境检测
        └── ReflectionUtil.java         # 反射工具 (带缓存)
```

---

## 开发者 API

如果你是插件开发者，可以主动使用 FoliaCompat 提供的安全 API：

### 线程安全状态存储

```java
// 替代 static Map<UUID, PlayerData> playerData = new HashMap<>();
GlobalStateStore<String, PlayerData> store = GlobalStateStore.create("playerData");
store.put(uuid.toString(), new PlayerData(...));

// 原子性读-改-写
store.compute(uuid.toString(), (key, current) -> {
    current.addBalance(10);
    return current;
});

// 原子性 put-if-absent
store.putIfAbsent(uuid.toString(), new PlayerData(0));

// 原子性 compare-and-set
store.replace(uuid.toString(), oldData, newData);
```

### 跨区域实体操作

```java
// 安全传送（自动在目标区域线程执行）
CrossRegionEntityBridge.safeTeleport(plugin, entity, destination);

// 批量操作（按区域分组并行执行）
CrossRegionEntityBridge.batchEntityOperation(plugin, entities, entity -> {
    entity.setHealth(entity.getHealth() + 1);
});
```

---

## 已知限制

FoliaCompat 解决的是 **调度器兼容性** 问题（`Bukkit.getScheduler()` / `isPrimaryThread()` / `folia-supported`）。以下问题超出范围：

| 限制 | 说明 | 影响插件 |
|------|------|----------|
| NMS 内部类访问 | 插件直接反射 Folia 修改过的 NMS 类 | ProtocolLib, dynmap, Terra |
| 平台检测不兼容 | 插件启动时检查服务端类型并拒绝 | FastAsyncWorldEdit |
| Folia 线程模型 | 非区域线程访问区域数据返回 null | Multiverse-Core |
| Folia 受限 API | 记分板等 API 在 Folia 上抛异常 | PowerRanks |
| 依赖链传播 | 上游不兼容导致下游失败 | WorldGuard, Orebfuscator |

这些问题的修复需要插件自身适配 Folia，非调度兼容层能解决。

---

## 依赖

- Java 21+
- Folia 1.20.4+ (向下兼容)
- ASM 9.6 (shade 进 jar，relocate 到 `com.foliacompat.libs.asm`)

## 许可证

基于 [WTFPL](https://en.wikipedia.org/wiki/WTFPL) 协议开源。
