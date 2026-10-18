# Paper 端

Numen 的服务端做成 Paper 1.21.1 插件。玩家照旧可以不装客户端(原版客户端走聊天、指令、可点的消息),也可以装 Numen 的 Fabric 或 NeoForge 客户端用面板。
这份稿子定 Paper 端怎么接进现有架构;调研材料(Paper 1.21.1 打完补丁的源码、参考项目)在 `D:\01_Projects\paper-refs\`。

## 一、范围

- 只做开发分支 1.21.1(10-19 用户定),做通以后随移植带到别的分支。Paper 只维护最新版,真正发布在最新分支上。
- 只认 Paper:不支持 Spigot(我们输出 Mojang 名的 jar,Spigot 不认),不支持 Folia(按区域多线程,假人、刻、区块票都得重写)。
- 模组联动(`plugins/*`)在 Paper 上不存在,不带。
- 客户端不变。Fabric 与 NeoForge 的 Numen 客户端连 Paper 服和连模组服走同一套协议、同一套界面。

## 二、原则:平台差异只在"挂点"与"服务"两处

Numen 的服务端代码在 `numen-api/common`、`numen/common` 与纯 JVM 模块里,只碰原版类,不碰加载器。加载器只做两件事,Paper 也只做这两件:

1. **服务**(common 问平台要东西):`platform/services` 下的接口,每个平台一份实现,`Services` 用 `ServiceLoader` 取。
2. **挂点**(平台把世界里发生的事告诉 common):common 里每件"需要知道的事"有且只有一个入口(下表"common 入口"一列),
   NeoForge 与 Fabric 用 Mixin 或加载器事件调它,Paper 用 Bukkit/Paper 事件调它。common 不知道是谁在调,也不分平台走两条路。

所以 Paper 端是第三个"加载器":`numen-api/paper` 与 `numen/paper` 两个子项目,形状同 `neoforge`、`fabric`。
Mixin 类是加载器那一侧的实现,集中在各自的 `mixin` 包;**common 的 `mixin` 包之外不许引用 `mixin` 包里的类**(今天有几处直接强转访问器,要先改成服务),
Paper 子项目编译 common 时整包排除 `mixin`,这条规矩由编译本身守住。

## 三、产物与构建

- 两个插件,和模组一样分:`numen-api`(引擎)与 `numen`(内容,依赖前者)。第三方 Paper 插件可以像第三方模组一样只依赖 Numen API 给她加工具。
  `paper-plugin.yml`,`numen` 对 `numen-api` 声明 `join-classpath`,两者共用引擎的静态登记。
- 构建:paperweight-userdev 1.7.2 + `paperDevBundle("1.21.1-R0.1-SNAPSHOT")`,Java 21,输出 Mojang 名(`MOJANG_PRODUCTION`),Paper 加载时不重映射。
- **common 对着 Paper 的开发包重新编译源码**,不直接打包对着原版编出来的字节码。Paper 改过不少原版方法的签名(例:`PlayerList.remove` 从 `void` 改成返回 `Component`),
  对着原版编的字节码在 Paper 上会 `NoSuchMethodError`,覆写会悄悄失效。重编解决调用;另做一个链接检查任务(ASM 扫 Paper 产物里每一处对服务端类的引用与覆写),进 CI,专抓"覆写失效"这类编得过但不对的。
- 纯 JVM 模块(store、ai、agent、script、lua、permission)用 shadow 打进 `numen-api` 插件。`ui` 和 commonmark 只给客户端,不带。Gson、slf4j、Brigadier、netty 用 Paper 自带的。
- 本地起服用 run-paper(`runServer`,自动下载 Paper,挂上两个插件)。

## 四、服务的 Paper 实现

| 服务 | Paper 上 |
|---|---|
| `IPlatformHelper` | 平台名 `Paper`;配置根 `plugins/Numen/`(相当于模组的 `config/numen/`,下面的 `server/`、`players/`、`client/` 布局不变);`modIds`/`modResource` 列出带 Numen 内容的插件并从它们的 jar 里取 |
| `INumenConfig` | 服务端用到的那几项(代理、系统提示词)存在配置根下;语音识别那几项是客户端的,Paper 不提供 |
| `IBlockCapabilityReader` | 只有原版容器(`Container`/`WorldlyContainer`),流体与能量为空 |
| `INetworkChannel` | 见 §七 |
| `IPermissions`(common 里凡问"这人有什么权限"都经它,不读 op 等级) | `isAdmin`(Numen 管理员):权限节点 `numen.admin`,默认给 op;`isGameMaster`(原版游戏管理那一档指令,如 `/numen drive`、召唤时选创造):对应的 Bukkit 节点;`commandLevel`(她跑命令的等级,模组上取主人的 op 等级)在 Paper 上不起作用,她的权限由委托主人的 `Permissible` 管(§九)。模组实现 `ModPermissions`:房主或 op 4 级 / op 2 级 |
| 私有访问(新) | 见 §五 |

## 五、私有访问

common 今天经 Mixin 访问器读写原版的私有成员:开船(`Boat.controlBoat`)、鱼上钩(`FishingHook.nibble`)、重算合成格(`CraftingMenu.slotChangedCraftingGrid`)、
界面数据槽(`AbstractContainerMenu.dataSlots`)、掉落物拾取冷却(`ItemEntity.pickupDelay`)。改成一个服务接口(引擎一份、内容一份,各管各的),
模组用 Mixin 访问器实现(Fabric 生产环境是中间名,不能按 Mojang 名反射),Paper 用公开成员或按 Mojang 名反射实现。

载具控制不需要访问器也不需要 Mixin:原版判"载具归不归本机驱动"对玩家骑手看的是 `isLocalPlayer()`,服务端只有这一处用它;
`NumenPlayer` 覆写 `isLocalPlayer()` 返回 true(原版 GameTest 的模拟玩家也是这么做的),各平台都删掉 `MixinEntityVehicleControl`。

## 六、挂点

| 事 | common 入口 | 模组上 | Paper 上 |
|---|---|---|---|
| 她这一挖的掉落物 | `Drops.breakStarts/added/breakEnds` | `ServerPlayerGameMode.destroyBlock` 包住、`ServerLevel.addFreshEntity` | `BlockDropItemEvent`(在 `destroyBlock` 里触发,带这一挖生成的全部掉落物) |
| 掉落物被捡、并堆、被毁 | `Drops.taken/merged/hurt` | `LivingEntity.take`、`ItemEntity.merge/hurt` | `EntityPickupItemEvent`、`ItemMergeEvent`、`EntityDamageEvent` + `EntityRemoveEvent` |
| 碰到掉落物但背包放不下 | `NumenPlayer.touchedItem` | `ItemEntity.playerTouch` | `PlayerAttemptPickupItemEvent`(在归属检查之前触发,自己判归属) |
| 谁放的这一格 | `PlacedBlocks.placedBy` | `BlockItem.place` | `BlockPlaceEvent`、`BlockMultiPlaceEvent` |
| 进度奖励 | `NumenEvents.advancementReward` | 包住 `PlayerAdvancements.award` | `PlayerAdvancementDoneEvent`(在发奖励之前触发:事件里拍快照,下一刻比) |
| 方块变了 | `BlockSearch.onBlockChange` | `ServerLevel.onBlockStateChange` | 没有通用事件,见下 |
| 发给她的包(含玩家打她的击退:发给她自己、当时 `hurtMarked` 为真的速度包,下一刻应用) | `FakeClient.onOutbound` | `ServerCommonPacketListenerImpl.send`(NeoForge 在 `Connection.send` 之前就会因频道检查抛异常,只能在这里截) | `FakeConnection.send`(Paper 的发送最终落在这里) |
| 她的身体被外面移除(踢人) | `Companions.removedFromOutside(body, reason)`:调用时身体还在世界里,**身体本身由平台移除**,入口管之后的一切(停牌、收件箱里的事实、名册记下理由且不重建、告诉主人、发 `REMOVE`);同一次踢人调两次,第二次忽略;Numen 自己造成的移除(遣散、休眠、关服)不调 | `ServerCommonPacketListenerImpl.disconnect` 落到她的假连接上(关服不调),调完由挂点把她移出玩家列表 | `PlayerKickEvent`、`PlayerQuitEvent` |
| 给没装客户端的玩家翻译 | `ServerLang` | 出口包改写 | 注册成 Adventure 的翻译源(`GlobalTranslator`):Paper 编码组件时按玩家语言渲染,原版的可翻译组件也走它 |
| 登入、登出、换维度、聊天、刻前、刻后、起服、停服、重载、指令注册 | 与模组相同的那几个入口 | 加载器事件 | 对应的 Bukkit/Paper 事件,见 §八 |

**方块变化**:Bukkit 没有"任何原因的方块变化"事件,拼一组事件也漏指令、WorldEdit。热索引的正确性因此不能押在这个挂点上:
取用时核对这一格此刻还是不是目标(各平台都这么做),挂点只负责让索引更热。Paper 上用能拿到的那组事件(破坏、放置、爆炸、流体、活塞、生长与消退、`EntityChangeBlockEvent`)喂它。

## 七、网络

- 编码不变:每种包的 `StreamCodec` 在 common 里写一次。Paper 上自己把它编成字节:下行**每人各编一次**,包进 `DiscardedPayload` 发
  (Paper 的 `DiscardedPayload` 带字节;同一个实例发给两个人,第二个人收到空包);上行用 Bukkit 的插件频道收字节再解码。分片、限速、包大小上限照旧由 common 管。
- "这条连接能不能收这种包"(`canSend`)看客户端登记过的频道。NeoForge 客户端在配置阶段末尾登记,Fabric 客户端进入游戏阶段之后才登记,所以不能在
  `PlayerJoinEvent` 那一刻定,要跟着 `PlayerRegisterChannelEvent` 走。"装没装我们的客户端"仍只由 `Hello` 报到定(`Presence.hasClient`)。
- 上行处理照 `Execution` 的约定:`NETWORK` 的处理器在哪个线程都必须对;Paper 把插件频道的包交到主线程,`MAIN` 的直接调。

## 八、事件、指令、聊天

- 生命周期:`PlayerJoinEvent`/`PlayerQuitEvent`/`PlayerChangedWorldEvent`、`ServerTickStartEvent`/`ServerTickEndEvent`(刻前必须在实体更新之前,与模组一致)、
  `ServerLoadEvent`(起好)、`onDisable`(停)、`ServerResourcesReloadedEvent`(重载)。
- 指令:Numen 的指令树是原版 Brigadier 树(参数只用基础类型),在 `LifecycleEvents.COMMANDS` 里注册;数据包重载时 Paper 重建指令并再发一次这个事件,照样再注册。
  以她的身份执行指令走原版调度器,Bukkit 插件的指令也已经桥接在里面。
- 聊天:`AsyncChatEvent` 在异步线程。取消与否要当场答,所以"这句话归不归 Numen"(`ChatRoutes` 的判断)必须是线程安全的只读判断,接下来的处理交回主线程。

## 九、她在 Paper 上是个真玩家

身体照旧经 `PlayerList.placeNewPlayer` 加入(Carpet 与插件形态的假人都这么做),所以她出现在玩家列表里,会触发 `PlayerJoinEvent`,别的插件把她当玩家处理。
这和 FTB 联动时定的立场一致:她是真玩家。由此要定的几件事:

- **区块**:Paper 的区块系统(Moonrise)按 `isRealPlayer` 把她当成观察者,给她加载 9×9 并编区块包。召唤后把她从玩家区块加载里摘掉
  (`removePlayer` 后再 `setRealPlayer(false)`,顺序不能反;或在 `PlayerSpawnLocationEvent` 里提前置 false,连初始的包也不发),有界加载仍由 `CompanionChunkLoader`
  的区域票管(Moonrise 重复加票整张替换、超时重置,续票成立)。实测摘掉后区块包为 0,3×3 实体刻,移动、碰撞、跨区块、换维度、被别人看见都正常。
  **刷怪不同**:Paper 的刷怪与随机刻认"附近有没有非旁观者玩家",与观察者无关,所以她周围照常刷怪;模组上 `ChunkMapCompanionMixin` 让她不触发刷怪。两边哪种对,留给用户定,定了以后两边做成一样。
- **被踢 = 被服主请出去**:Paper 上 `/kick` 与插件踢人当场移除身体(先 `PlayerKickEvent`,再 `PlayerQuitEvent`);空闲超时与保活超时碰不到她(检查在假连接不跑的 `tick` 里)。
  模组上今天踢她什么也不发生(假连接把断开吞了)。两边统一:被踢就是身体被移除,和主人下线的收拾走同一条路,如实告诉她和主人是谁、为什么踢的,不重建;主人可以再召唤。
  common 有一个入口"身体被外部移除(原因)",模组从断开的那一处调它,Paper 从 `PlayerKickEvent`/`PlayerQuitEvent` 调它。
- **登录事件**:`placeNewPlayer` 不触发 `AsyncPlayerPreLoginEvent`/`PlayerLoginEvent`,LuckPerms 因此不装载她。Paper 上召唤时照真玩家的顺序补发这两个事件(插件形态假人的主流做法),
  被拒就召唤失败并说明原因。不打 `NPC` 元数据:实测没有好处,还和"她是真玩家"冲突。AuthMe 这类登录插件会在登录超时后踢她——走上面"被踢"那条路如实报告;服主要让她留下,按登录插件自己的豁免配置(如 AuthMe 的免登录名单)来,Numen 不替登录插件做特例。
- **她的权限 = 主人的**:Paper 上权限是节点。她的 `Permissible` 换成委托给主人的一份(与 LuckPerms 注入的方式相同):主人在线问主人;主人不在线(外部大脑驾驶)时经 Vault 的权限接口查离线玩家,
  没装 Vault 时按主人是不是 op 定(Bukkit 对离线玩家的默认语义)。Numen 自己的"管理员"判定在 Paper 上是权限节点 `numen.admin`(默认给 op),模组上是 op 4 级或房主——
  common 只问平台"这人是不是 Numen 管理员",两种来源由平台服务给出。
- **击退**:怪打她击退正常;玩家打她,原版发完速度包就把服务端速度清零了(本该由客户端应用),她就纹丝不动——模组上也一样。`FakeClient` 接住发给她自己的速度包,
  当时 `hurtMarked` 为真就下一刻应用;这是 common 的修复,两边同时受益。摔落伤害正常,不用改。
- **私聊的样子随插件变**:原版 `/msg` 到她这里是私聊包(`Whispers` 认);EssentialsX 的 `/msg` 是一条系统消息,她当作服务端消息收到(话还在,发话人在文字里)。
  要认成私聊得接 EssentialsX 的事件,属于 Paper 上的插件联动,以后按需要做。
- 移动类插件看不到她的移动(`PlayerMoveEvent` 只在收到移动包时触发),这是平台事实,不补。

## 十、内容与资源

- 内置内容(`numen/` 下的技能、模块、人设)从插件 jar 里读:把 jar 当 zip 文件系统打开,交给 `Shipped.bundle`,同步规则不变。
- 语言文件打进插件 jar,`ServerLang` 照旧读。
- 标签(`numen:natural`、`tame_foods` 等)是数据包内容,Paper 1.21.1 的插件没有直接带数据包的 API,怎么装进去待实测(§十二)。

## 十一、验证

- common 的行为继续由 NeoForge 的 GameTest 验(主力)。
- 我们的 GameTest 拿到 Paper 上跑:不开 `IS_RUNNING_IN_IDE`(开了会把测试参数类型塞进内置注册表,有 op 的客户端进服就断),
  而是自己用 `GameTestRegistry.register` 登记、`GameTestRunner` 启动、在 `ServerTickEndEvent` 里推 `GameTestTicker`;实测可行。现有用例要一层登记适配。
- Paper 适配层:run-paper 起服,测试插件跑场景、写结果、自动关服,进 CI。
- 网络与翻译:MCProtocolLib(MIT,1.21.1 用 `1.21` 线;仓库里只有快照,固定在一个时间戳构建)做无头客户端,原版客户端那一套交互(进服、翻译、召唤、私聊、会话指令与补全、可点的征询、动作栏)的场景在 `e2e/`,已对着 NeoForge 专用服跑通(`:e2e:e2eTest`)。场景只认规格(地址、RCON 口、种子,见 `e2e/build.gradle`),起服是可替换的一层:Paper 另写一层,提供同名的 `e2eServerStart`/`e2eServerStop`、满足同一份规格,把 `e2e/build.gradle` 里的 `serverProject` 指过去。
- 真客户端:Fabric、NeoForge 的 Numen 客户端各自 `runClient --quickPlayMultiplayer` 连 Paper 服,面板、征询、皮肤、语音都过一遍。

## 十二、实测结论(10-19)

实验代码与日志在 `D:\01_Projects\paper-refs\pw-probe-1.21.1`、`numen-probe-client`、`numen-probe-run`。结论已写进上文各节,另有几条:

- 指令:原版 Brigadier 树无检查强转后交给 `LifecycleEvents.COMMANDS` 的注册器可行(原版参数类型也被接受),`/minecraft:reload` 后还在,原版客户端补全正常;以她的身份执行原版与插件指令正常。
- 网络:`DiscardedPayload` 发送会消耗缓冲,同一实例发两次第二次是空包,所以每人各包一份。NeoForge 客户端在配置阶段登记频道(那时玩家还没进世界),Fabric 客户端进游戏后才登记:`canSend` 跟着 `PlayerRegisterChannelEvent`,处理器要接受玩家还没进世界的情况。
- 翻译:Adventure 翻译源按玩家语言渲染系统聊天、动作栏、点击与悬停;语言文件载入时把 `%s`/`%2$s` 改写成 MessageFormat 的 `{n}`。**缺口**:根组件是字面量时,悬停里的可翻译文本不渲染(Paper 只认根是可翻译的)。
- 没测:死亡重生是否恢复 `isRealPlayer`;封禁、白名单、关服造成的移除;Vault;没有 LuckPerms 时的权限委托。

## 十三、分步

1. **平台无关化(模组侧)**:私有访问改服务;载具改 `isLocalPlayer`;common 的 `mixin` 包外不再引用 `mixin` 包;热索引取用时核对。模组行为不变,GameTest 全过。
2. **Paper 工程与引导**:两个子项目、重编 common、链接检查、run-paper;四个服务;生命周期事件;指令;网络;内容、语言、标签。服务端能起、两个插件能加载、指令在、原版客户端能进。
3. **身体**:先做两边共用的 common 部分(被踢走"身体被外部移除"一条路、击退、"是不是 Numen 管理员"成平台服务),模组上 GameTest 验;
   再做 Paper 部分(召唤补登录事件、摘出区块加载、权限委托、所有挂点的 Paper 实现、翻译源)。
4. **验证工具**:MCProtocolLib 无头客户端的端到端测试(先对 NeoForge 专用服,再对 Paper);现有 GameTest 拿到 Paper 上跑;真客户端实测;进 CI。
5. **收尾**:文档、发布(Hangar/Modrinth 的插件页)、`publishAll` 带上 Paper 产物。

参考与许可:Carpet、Axiom、LuckPerms、BlueMap、FancyNpcs、MCProtocolLib 是 MIT,tanyaofei/minecraft-fakeplayer 与 run-task 是 Apache-2.0,可以借代码(保留声明);
Paper、Leaves、spark、WorldEdit、PacketEvents、ProtocolLib 是 GPL,Citizens 是 OSL,只借思路;Simple Voice Chat 是 All Rights Reserved,不借。

## 附:第 2 步落地时的出入

- **run-paper 用 3.0.2**,不是 2.3.1:2.x 走已下线的 `api.papermc.io/v2`(410),找不到 Paper 版本;3.x 走 `fill.papermc.io`。本仓库 Gradle 是 9.2,paperweight-userdev 1.7.2 照常工作。
- **配置根是 `plugins/numen/`(小写)**,不是 `plugins/Numen/`:`NumenPaths.config()` 是"配置目录 + `Constants.CONFIG_ROOT`(`numen`)",Linux 区分大小写,
  只有小写是同一个答案。插件名也用模组 id(`numen_api`、`numen`),`modIds()` 因此直接等于资源命名空间;`modIds()` 列的是 Numen API 自己和声明依赖它的插件。
- **纯 JVM 模块不用 shadow 插件**,和 NeoForge / Fabric 的发行 jar 一样把各模块的输出平铺进 jar(Gson、slf4j 等用 Paper 自带的,本来就不该打进去),少一个构建插件。
- **`canSend` 不听 `PlayerRegisterChannelEvent`**:Paper 把客户端登记的频道(NeoForge 在配置阶段末尾、Fabric 在进入游戏之后)都记在这位玩家身上,
  `getListeningPluginChannels()` 随时是答案,没有要缓存的状态,也就没有要同步的事件。上行包在 Paper 里已经交到主线程再回调,`NETWORK` 与 `MAIN` 都直接调。
- **common 重编遇到的两处 Paper 补丁**(原版编得过、Paper 编不过):`CommandSource` 多了抽象方法 `getBukkitSender`(返回 Bukkit 类型),
  `BlockGetter` 多了 `getBlockStateIfLoaded` / `getFluidIfLoaded`。前者新增服务 `ICommandSources`,`Echo` 不再自己是 `CommandSource`,由平台造;
  后者在寻路的 `WorldView` 里给默认实现。**寻路模块也要在 Paper 上重编**(它同样对着原版),不只是 common。
- **common 里重复的加载器胶水收成 common 入口**:`PlayerSessions`(登入、登出、换维度)、`ChatRoutes.routed/deliver`(聊天判断与送达分两段,`take` 由前两者组成,模组上照用),
  系统提示词出厂值 `INumenConfig.DEFAULT_SYSTEM_PROMPT`;寻路调试的下行包改走 `Services.NETWORK` 登记与发送(原先各加载器各登记一次、common 直接构造原版的 `ClientboundCustomPayloadPacket`,
  那在 Paper 上编不出来)。`Services.load` 多一个按类加载器取的重载:Paper 上每个插件一个类加载器,Numen 的服务实现不在 Numen API 的加载器里。
- **标签**:Paper 1.21.1 的插件没有带数据包的 API,但有标签的生命周期事件 `LifecycleEvents.TAGS.preFlatten`(起服与 `/reload` 都会触发),只认引导阶段(`PluginBootstrap`)登记的处理器。
  做法是插件 jar 里本来就有的数据包写法的标签文件(手写的在 common 资源里,数据生成的由 `datagenAll` 产出,Paper 的 jar 取 NeoForge 子项目的生成目录)
  由 `PluginTags` 在引导阶段读出来加进去,内容只有一个出处。只支持方块与物品两个注册表。验证:控制台 `execute if block … #numen:natural`、`clear @a #numen:tame_foods 0` 解析通过,不存在的标签报 Unknown tag。
  联想到的另一种做法(在 `world/datapacks` 里写数据包文件、要重启或 `/reload`)主流插件里常见,但要写服主的世界目录,这里没采用。
- **链接检查默认对着编译用的开发包里的服务端**(`-PlinkCheckPaperServer=` 可换成实际运行的 `versions/<mc>/paper-<mc>.jar`);Paper 的 Paperclip 里是二进制补丁不是 jar,所以不解包。
  它读三样东西:成品、原版服务端 jar(取 common 编译用的那份,是"本来是覆写"的依据)、Paper 服务端 jar。对原版 jar 当目标时它会报出 `PlayerList.remove` 那类差异。
- **名册要等"在世界里 + 能收我们的包"都成立才推**:客户端收到名册才向服务端报到。模组上登入时两者同时成立;Paper 上 Fabric 客户端进游戏之后才登记频道,
  登入那一刻 `canSend` 为假。`PlayerSessions.channelReady` 是推名册的唯一入口(一次会话只推一次,重复调用无害),登入里调一次,Paper 再在 `PlayerRegisterChannelEvent` 里调。
  `canSend` 本身仍直接查 `getListeningPluginChannels()`。
- **`INumenConfig` 只留服务端的项**(代理、系统提示词,及管理员经客户端面板改它们的写入口,Paper 同样写回 `plugins/numen/config.json`);语音识别设置拆成客户端源码集里的服务
  `ISttSettings`(`ClientServices.STT`),NeoForge 与 Fabric 各实现,Paper 不需要。
- **纯 JVM 模块清单只在 buildSrc 的 `Modules` 里有一份**;`releaseJars` 把 Paper 的两个 jar 收进 `paper/`,并核对 datagen 产出的每个文件都在 jar 里。
