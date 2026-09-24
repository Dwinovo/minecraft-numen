# FTB Quests 联动:让她作为真玩家参与任务线

状态：调研已完成、方向已定，尚未落地（09-24 暂停，等更大的统一设计）。

参考源码在 `D:\01_Projects\ftb-refs\`（`1.21.1/main` 分支，FTB Quests 版本 2101.1.36）。下文 FTB 路径的前缀为 `FTB-Quests/common/src/main/java/dev/ftb/mods/ftbquests/`。

## 一、目标与定位

用户希望：同伴和主人在同一个 FTB 队伍里时，能一起推进任务；不在同一个队伍时，她做的事自然不算。她被 FTB 当作**真正的玩家**。

### 否掉的方案及原因

| 方案 | 否掉的原因 |
|---|---|
| 改继承（让同伴直接是原版 `ServerPlayer`，不再是子类） | 太假，而且等于把同伴的身体重写一遍 |
| 插件替 FTB 记进度（监听她的击杀、物品，再调用 FTB 内部接口加进度） | FTB 的判定逻辑会被复制一份，形成多个来源 |
| 只读主人进度（她不自己完成任务，只帮主人准备） | 用户认为趣味性大减 |

### 定下的做法

- 让 FTB 把她当真玩家，由 FTB 自己的队伍规则决定算不算。
- FTB 的按钮背后本来就有提交入口。她没有界面、点不了按钮，插件就代她调用同一个入口，判定仍由 FTB 一处负责。

## 二、为什么 FTB 不算她：Architectury 的 isFake

FTB 1.21.1 用 Architectury 的 `PlayerHooks.isFake` 排除假玩家（版本 13.0.8，13.0.11 与之相同）。

**NeoForge 端**（`dev/architectury/hooks/level/entity/forge/PlayerHooksImpl.java`）：

  ```java
  return playerEntity instanceof ServerPlayer && playerEntity.getClass() != ServerPlayer.class;
  ```

- 凡是子类都算假玩家，没有事件、注册表或配置可以改写。
- 它也不读 NeoForge 自己的 `Player.isFakePlayer()`，因此加任何标记都无效。

**Fabric 端**：先问 `FakePlayers.EVENT`，没有结论时才用上面那条启发式判断。源码注释原话是 "reasonable default"。

**同伴的情况**：`NumenPlayer extends ServerPlayer`，所以 NeoForge 端恒被判为假玩家。按 NeoForge 原生的判断（`instanceof FakePlayer`），她其实是真玩家。

从语义上讲，她没有真人客户端，说她"假"也没错。真正的错位在于 FTB 默认把假玩家当成自动化机器（机械手、海龟），整个排除掉，以防刷任务。

## 三、翻转 isFake

用一条窄 mixin，两个加载器通用：

```java
@Pseudo
@Mixin(targets = "dev.architectury.hooks.level.entity.PlayerHooks", remap = false)
abstract class ArchitecturyPlayerHooksMixin {
    @Inject(method = "isFake", at = @At("HEAD"), cancellable = true)
    private static void numen$companionIsReal(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof NumenPlayer) cir.setReturnValue(false);
    }
}
```

- **注入点选公共类 `PlayerHooks`**：类名跨加载器、跨版本不变。`…forge.PlayerHooksImpl` 这个包名是历史遗留，将来可能改。
- **Architectury 不在场**：`@Pseudo` 让 mixin 直接跳过。编译时不依赖 Architectury。
- **目标在但签名变了**：保留 `defaultRequire: 1`，直接报错，不静默失效。
- **放在哪**：`api/common` 的 `numen_api.mixins.json`。"她是真玩家"是身体的身份，属于引擎机制，影响所有基于 Architectury 的模组。`plugins/` 没有 mixin 入口。
- **上游**：可以给 Architectury 提 PR，让 NeoForge 端也走 `FakePlayers` 事件，和 Fabric 对齐。合并以后就能删掉这条 mixin。
- **其它版本分支**：
  - 26.1 起，FTB 改用 FTB Library 自己的 `Platform.get().misc().isFakePlayer`（`NeoMiscImpl.java:42`），注入点要换。
  - 1.21.11 仍然用 Architectury。

## 四、翻转后 FTB 的行为

FTB Quests 里调用 isFake 的位置：

| 位置 | 管什么 | 同队时 | 她自己一队时 |
|---|---|---|---|
| `FTBQuestsEventHandler.playerKill:132` | 击杀 | 计入主人队伍 | 计入她自己的队伍 |
| `playerTick:156` | 位置、群系、结构、进度、统计、阶段等（每 tick 检查） | 她到场、她的成就都算数 | 计入她自己的队伍 |
| `changedDimension:218` | 维度 | 同上 | 同上 |
| `containerOpened:235`、`FTBQuestsInventoryListener.detect:26` | 背包、合成、熔炼检测（不消耗物品的那类） | 她背包里有就算 | 计入她自己的队伍 |
| `StageTask.checkStages:96` | 阶段 | 算 | 计入她自己的队伍 |
| `DetectorBlockEntity.isRealPlayer:44` | 检测方块 | 她站进范围就推进 | 计入她自己的队伍 |
| `cloned:202` | 重生后保留任务书 | 保留 | 保留 |

FTB Teams 和 FTB Library 在 1.21.1 上没有假玩家判断。

### 奖励（`TeamData.checkAutoCompletion:602-648`）

以下是 FTB 对真玩家的正常规则，要让用户知情。

- **自动领取的个人奖励**：每个在线成员各发一份，她也有一份，直接进她的背包。
- **自动领取的团队奖励**：发给触发完成的那个人。如果是她触发的，就进她的背包。例外是击杀：击杀没有玩家上下文，会跳过自动领取。
- **命令奖励、阶段奖励**：`@p` 或阶段落到领取人身上。
- **凡是进她背包的东西，都要作为事件报给模型。**

### 登录检查

`ServerQuestFile.checkQuestBookOnLogin:218-274` 本来就不判假玩家，现在已经在对她执行：用她的背包和位置，对她所在队伍的"登录时检查"条件调用 `submitTask`（不扣物品），然后做一轮自动领奖。

### 入队与退队

- 入队时 `TeamData.mergeData` 会合并进度：各条件取较大值，已完成的取并集。她自己攒的进度会一次性并进主人队伍。
- 退队时她拿回自己原来的进度。

### FTB Chunks

她在主人队伍里就按成员放行。她自己一队时，要主人把她加为盟友，或者对方领地设为公开。

### FTB Ultimine

连锁挖掘需要客户端按键包，翻转后她仍然用不了。

## 五、她自己点不了的，插件代她走同一个入口

### 1. 接受入队邀请

- 邀请是 `PartyTeam.invite` 发到她假连接上的一条聊天消息，里面有"接受"按钮，点了执行 `/ftbteams party join <队伍>`。没有人会去点它。
- FTB 只检查她是否处于被邀请状态（`FTBTeamsCommands.partyTeamArg:337` 检查 `INVITED`），不需要 OP。
- 所以服务端可以以她的身份执行这条命令。
- 设想：主人邀请她时，发事件"主人邀请你加入队伍 X"，由她执行加入，结果照样报告。邀请本身就是主人的决定。
- **待定**：只接受主人的邀请，还是其他人邀请时也问一下。
- FTB Teams 的 API 里没有邀请事件，要确认怎么感知邀请（可以监听她收到的这条系统消息，或者看队伍的成员状态）。

### 2. 提交任务

**需要点按钮的任务**：
- 消耗型物品提交
- XP 提交
- 打勾
- 自定义任务
- 观察：客户端判断准星是否对准目标，满足后发包

**按钮背后的服务端处理**（`net/SubmitTaskMessage.handle`）：
1. 取发包玩家所在队伍的 `TeamData`
2. 确认没有锁定
3. 取出任务，确认 `canStartTasks(quest)`
4. 执行 `sqf.withPlayerContext(player, () -> task.submitTask(data, player))`

**插件怎么做**：原样调用这个入口，由 FTB 自己判定、扣她的背包。这是调用按钮背后的同一个入口，不是复制判定逻辑。

**例外**：观察任务的判定在客户端。服务端直接提交等于跳过"她确实在看"，属于作弊。第一版不支持，或者提交前先确认她的视线命中目标。

**有些条件她仍然做不到**：`only_from_crafting` 的物品任务只认提交者本人合成的物品，东西是谁合成的就算谁的。

## 六、读任务书

**推荐在主人客户端读。**

- Numen 的工具覆写 `NumenTool.invoke(ToolCall)` 后就在客户端当场完成，先例是 `TodoWriteTool`。
- `ClientQuestFile.INSTANCE.selfTeamData` 是主人所在队伍的实时进度。
- 只有客户端能给出本地化文本：`getTitle()` / `getDescription()` 会经 `TextUtils.parseRawText` 解析 `{翻译键}`。服务端固定是回退语言，`{key}` 也解析不了。

**同队与否**：
- 同队时，读到的就是他们共享的进度。
- 不同队时，工具要说明"你不在主人的队伍里，你做的不算"。

**用到的接口**：
- 稳定 API：`FTBQuestsAPI.api().getQuestFile(true)`、`forAllQuests` / `forAllChapters`
- 内部类：`TeamData.canStartTasks` / `isCompleted` / `getProgress` / `getCannotStartReason` / `getPinnedQuestIds`，`Quest.isVisible` / `getTasks` / `getRewards` / `streamDependencies`，`Task.getMaxProgress` / `formatProgress` / `consumesResources`
- 读客户端数据的代码放在单独的类里，避免专用服务器加载到客户端类。
- "当前可做"的判据：`isVisible(td) && !isCompleted(quest) && canStartTasks(quest)`

## 七、事件

- **注册方式**：服务端 `ObjectCompletedEvent.QUEST/CHAPTER.register(...)`（Architectury 事件）。单人游戏里客户端也会触发，要先判断 `e.getData().getFile().isServerSide()`。
- **`quest_completed`**：她所在的队伍完成了任务或章节。
- **`quest_reward_auto`**：自动领取的奖励进了她的背包。事件要推迟到下一个服务端 tick 再发，因为登录那一轮发生在 `placeNewPlayer` 里面。
- **邀请事件**：见第五节。

## 八、插件结构草案（只做 NeoForge）

```
plugins/ftbquests/
  NumenFtbQuests      install:注册工具、事件类型、技能
  QuestBookTool       一个工具,action 分三种:查看(可做任务/任务详情)、提交(某任务)、接受邀请
  ClientBook          唯一碰 ClientQuestFile 的类
  FtbqEvents          服务端:完成事件、自动奖励事件、邀请事件
  skills/ftb_quests/SKILL.md
```

- **isFake 翻转的 mixin 不放在插件里**，放在 api，见第三节。
- **为什么只加一个工具**：按扩展点原则，读任务书、提交、入队都是新动词，所以做成插件工具加技能文档。工具表每轮全量下发，因此合并成一个工具，用 action 区分。
- **详情里的"谁来完成"**：用第四、五节的表给每个条件标注，例如"她也能做 / 要她提交 / 只有主人合成才算 / 观察暂不支持"。
- **技能文档要点**：
  - 怎么入队。
  - 同队后她的行为会计入进度。
  - 奖励会发到她身上，拿到后应如实告诉主人。
  - 领地放行靠同队或盟友。
- **依赖（全部 compileOnly）**：
  - `dev.ftb.mods:ftb-quests-neoforge:2101.1.36`
  - `ftb-library-neoforge:2101.1.36`
  - `ftb-teams-neoforge:2101.1.9`
  - `dev.architectury:architectury-neoforge:13.0.8`
- **仓库**：`https://maven.ftb.dev/releases`、`https://maven.architectury.dev/`，都用 `exclusiveContent` 限定。
- **许可**：FTB 全家是 All Rights Reserved，只能编译期引用，不能打包，也不能抄代码。Architectury 是 LGPL-3.0。
- **接线**：`Builtin.java` 里 `gate.open("ftbquests", …)`。

## 九、生态影响（翻转 isFake 之后）

| 类别 | 模组 | 影响 |
|---|---|---|
| A. 只认原生 `FakePlayer` / `isFakePlayer()` | Balm 系（Waystones 等）、Lootr、CoFH、Open Parties and Claims 等 | 本来就把她当真玩家，不受影响 |
| B. 调用 Architectury `isFake` | FTB 全家、Cobblemon Quests（她抓的宝可梦会计入任务）、Tensura-FTB、VillagerQuests、Powah（Fabric 移植版）、andromalius-currency 等 | 翻转后把她当真玩家；凡是改动她背包或数值的地方，都要能报给模型 |
| C. 自己内联 `getClass() != ServerPlayer.class` | CC: Tweaked、Game Stages（1.20.x）、Twilight Forest（Fabric）、IC2R 等 | 翻转管不到，仍把她当假玩家 |

以上名单来自 GitHub 代码抽样，不完整。

## 十、风险

- 用到的大多是 FTB `api` 包以外的内部类，FTB 升版时可能改动。
- 26.x 要换注入点。
- KubeJS 自定义条件是黑盒，只能照原样显示进度数字。
- 整合包的任务书很大时，她每次登录，服务端都会把整本任务书的同步包发给她的假连接，序列化开销不小。
