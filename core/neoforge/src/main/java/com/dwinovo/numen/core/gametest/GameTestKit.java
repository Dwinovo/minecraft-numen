package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * 同伴行为的游戏内自动化用例的共用件。用例在无头 gameTestServer 里跑({@code gradlew :neoforge:runGameTestServer}):
 * 在结构模板圈出的场地里,用真实的生成路径拉起同伴、经真实任务队列下发指令,按 tick 轮询断言
 * 世界状态——退出码 = 失败用例数,可直接进 CI。
 *
 * <p>结构模板以 SNBT 文本存于仓库 {@code neoforge/gameteststructures/}(运行配置经系统属性
 * {@code numen.gametest.structures} 指路),不提交二进制 .nbt。注意两件事:模板必须是
 * gametest 的"打包" SNBT 形态(palette 为字符串、方块表叫 {@code data}——裸结构 NBT 形态
 * 会被 {@code NbtUtils.unpackStructureTemplate} 静默丢弃,一块不放);且模板方块落位在
 * {@code 测试原点+1+rel},而 {@link GameTestHelper#absolutePos} 只加 {@code rel}——引用
 * 模板内 rel y 的格子时要再 +1。
 *
 * <h2>场地之间隔多远</h2>
 * 服务器把全部用例的场地按一行八块铺开,批次之间接着往下铺、不清场;原版只在两块结构之间留 5 格(行间 6 格)。
 * 而她的感知是按半径的:collect_items 默认捡 16 格内的掉落物,钓鱼收战果看 18 格,战斗看 12 格内的怪。
 * 5 格的缝挡不住这些半径,她就会去捡隔壁场地的东西——结论取决于隔壁那条用例跑到了哪一步、后台寻路多快。
 *
 * <p>所以每块地板模板在 +x、+z 两侧多出 16 格垫场(结构尺寸大 16,原版铺场地时就按大的尺寸隔开),垫场与
 * 可走的地板之间立一圈到顶的屏障,站在原来包围墙的位置——可走的地板、墙、rel 坐标都和原来一样。任意两块
 * 场地可走部分之间因此至少隔 21 格,比上面这些半径都大。改模板或加新模板时守住这一条。
 *
 * <p>更远的感知(goto 找方块扫 32 个 chunk、{@code scan blocks} 按用例给的半径、mine 在工作区里捡掉落物、逃跑看 32~40 格)
 * 隔不开:这类用例靠场景用别的用例不会留下的东西(独一种方块、物品)来保证只看见自己的。
 *
 * <h2>步骤一律经 {@link #steps} 与 {@link #succeedWhen}</h2>
 * 原版的 {@code helper.startSequence()} 与 {@code helper.succeedWhen} 在一步失败之后照样往下跑、也接不住断言以外的异常,
 * 一条用例的失败会把整台测试服带崩({@link Steps})。用例不直接用它们。
 *
 * <p>用例按领域分在同包的各个 {@code *GameTests} 类里;两个以上的类都要用的身体生成与场景搭建放在这里。
 * 这个类自己没有用例,仍挂着 {@link GameTestHolder}:NeoForge 登记用例时加载并初始化每个挂着它的类,
 * 静态块因此赶在任何结构模板加载之前把模板目录指到仓库里。
 */
@GameTestHolder(Constants.MOD_ID)
public final class GameTestKit {

    private GameTestKit() {}

    /** 正午:白天的活都在这时候跑。 */
    static final long NOON = 6000;
    /** 半夜:僵尸不会被太阳晒死,床睡得着。 */
    static final long MIDNIGHT = 18000;
    /** 批次开场定下的晴天维持多久(刻):一整天,够一批跑完。 */
    private static final int CLEAR_WEATHER_TICKS = 24000;

    /**
     * 批次开场把世界定下来:难度、时刻、晴天,并关掉自然刷怪。每个批次都自己定,不继承上一批留下的——批次按名字的
     * 哈希排序,谁在谁前面跑说不准;和平难度会把战斗用例里的僵尸当场收走,那条用例就成了空转。
     */
    static void settleWorld(ServerLevel level, Difficulty difficulty, long dayTime) {
        level.getServer().setDifficulty(difficulty, true);
        level.setDayTime(dayTime);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.setWeatherParameters(CLEAR_WEATHER_TICKS, 0, false, false);
    }

    static {
        String dir = System.getProperty("numen.gametest.structures");
        if (dir != null) {
            StructureUtils.testStructuresDir = dir;
        }
    }

    /** 这条用例的步骤,代替原版的 {@code helper.startSequence()}:一步失败只让这一条用例失败,见 {@link Steps}。 */
    static Steps steps(GameTestHelper helper) {
        return new Steps(helper);
    }

    /** 等到 {@code check} 成立就算通过,代替原版的 {@code helper.succeedWhen}:它抛出别的异常也只让这一条用例失败。 */
    static void succeedWhen(GameTestHelper helper, Runnable check) {
        steps(helper).thenWaitUntil(check).thenSucceed();
    }

    /** 把她提到 rel 那一格上空放手。 */
    static void drop(GameTestHelper helper, NumenPlayer companion, BlockPos rel) {
        BlockPos at = helper.absolutePos(rel);
        companion.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                companion.getYRot(), companion.getXRot());
    }

    static boolean carries(NumenPlayer companion, net.minecraft.world.item.Item item) {
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    static NumenPlayer armedCompanion(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(rel);
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_fighter", UUID.randomUUID(), level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        companion.getFoodData().setFoodLevel(20);
        return companion;
    }

    /** rel 起点 + 尺寸圈出的长方体格集;hollow = 只留外壳。 */
    static List<BlockPos> boxCells(BlockPos origin, int sx, int sy, int sz, boolean hollow) {
        List<BlockPos> cells = new ArrayList<>();
        for (int dy = 0; dy < sy; dy++) {
            for (int dx = 0; dx < sx; dx++) {
                for (int dz = 0; dz < sz; dz++) {
                    if (hollow && dx != 0 && dx != sx - 1 && dy != 0 && dy != sy - 1
                            && dz != 0 && dz != sz - 1) {
                        continue;
                    }
                    cells.add(origin.offset(dx, dy, dz));
                }
            }
        }
        return cells;
    }

    /** floor20 上拉起同伴的公共步骤;creative = 召后切创造档。 */
    static NumenPlayer spawnAt(GameTestHelper helper, String name, BlockPos rel,
                                       boolean creative) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(rel);
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                name, UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        if (creative) {
            companion.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        }
        return companion;
    }

    /** 图纸夹具从测试结构目录拷进蓝图目录(幂等)。 */
    static void copyCottageFixture(ServerLevel level) throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                StructureUtils.testStructuresDir, "japanese_cottage.litematic");
        java.nio.file.Files.copy(src,
                com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer())
                        .resolve("japanese_cottage.litematic"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** 一间 5×5、三格高、无顶的木板屋,她在屋里。生存、空手:垫不高,只能拆墙或不出去。 */
    static void plankRoomAround(GameTestHelper helper, int cx, int cz) {
        ServerLevel level = helper.getLevel();
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                boolean perimeter = x == cx - 2 || x == cx + 2 || z == cz - 2 || z == cz + 2;
                if (!perimeter) continue;
                for (int y = 2; y <= 4; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.OAK_PLANKS.defaultBlockState());
                }
            }
        }
    }

    static int plankCount(GameTestHelper helper, int cx, int cz) {
        ServerLevel level = helper.getLevel();
        int n = 0;
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                for (int y = 2; y <= 4; y++) {
                    if (level.getBlockState(helper.absolutePos(new BlockPos(x, y, z))).is(Blocks.OAK_PLANKS)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** scan_blocks 在半径 {@code radius} 内找 {@code blockId}(回执稍后才到)。 */
    static ToolRun scan(NumenPlayer companion, int radius, String blockId) {
        return call(companion, "scan_blocks", args("radius", radius, "block_ids", List.of(blockId)));
    }

    /**
     * 先建一块区域,再把 {@code blockId} 扫进去({@code scan blocks … --into}):回执里每团的编号就是 {@code 区域/g1} 这种写法。
     * 建区域当场回;扫的回执稍后才到。
     */
    static ToolRun scanInto(NumenPlayer companion, int radius, String blockId, String area) {
        ToolRun made = command(companion, "area new " + area);
        if (!made.succeeded()) {
            throw new IllegalStateException("area new " + area + " failed: " + made.reply());
        }
        return command(companion, "scan blocks " + radius + " " + blockId + " --into " + area);
    }

    /** {@link #mineScanned} 把找到的方块扫进的那块区域。 */
    static final String MINED_AREA = "targets";

    /**
     * 照模型挖矿的三步走一遍:建区域 {@link #MINED_AREA}、在半径 {@code radius} 内把 {@code blockId} 扫进去
     * ({@code scan blocks … --into}),扫的回执一到就调 work_dig 挖这块区域;{@code extra} 是 work_dig 的其余参数
     * (count),键、值交替。扫描被拒或失败时不挖,这次挖矿的结论就是扫描的回执。
     */
    static Mining mineScanned(GameTestHelper helper, NumenPlayer companion, int radius, String blockId,
                              Object... extra) {
        ToolRun scan = scanInto(companion, radius, blockId, MINED_AREA);
        Object[] keyValues = new Object[extra.length + 2];
        keyValues[0] = "place";
        keyValues[1] = List.of(MINED_AREA);
        System.arraycopy(extra, 0, keyValues, 2, extra.length);
        Mining mining = new Mining(scan);
        helper.onEachTick(() -> {
            if (mining.run.get() == null && scan.reply() != null) {
                mining.run.set(scan.succeeded() ? call(companion, "work_dig", args(keyValues)) : scan);
            }
        });
        return mining;
    }

    /**
     * {@link #mineScanned} 的那次挖矿:扫描回来之前还没派,{@link #done} 为假、{@link #outcome} 为 null;派下去之后与
     * {@link ToolRun} 读法相同。
     */
    static final class Mining {

        private final ToolRun scan;
        private final AtomicReference<ToolRun> run = new AtomicReference<>();

        private Mining(ToolRun scan) {
            this.scan = scan;
        }

        /** 扫进区域的那一次。 */
        ToolRun scan() {
            return scan;
        }

        boolean done() {
            ToolRun r = run.get();
            return r != null && r.done();
        }

        boolean succeeded() {
            ToolRun r = run.get();
            return r != null && r.succeeded();
        }

        String outcome() {
            ToolRun r = run.get();
            return r == null ? null : r.outcome();
        }

        /** 派发当场的回执:受理的"已受理"或拒收的原因;还没派是 null。 */
        String reply() {
            ToolRun r = run.get();
            return r == null ? null : r.reply();
        }

        /** 派下去的那件活;还没派或拒收了是 null。 */
        TaskRecord task() {
            ToolRun r = run.get();
            return r == null ? null : r.task();
        }
    }

    /** 回执这一页列出的团:消息里一团一行,每行一个 JSON 对象(抬头、翻页提示与结尾不是)。 */
    static com.google.gson.JsonArray groupsIn(String reply) {
        return rowsIn(reply);
    }

    /** 回执消息里一条一行的 JSON 对象({@code scan blocks}、{@code scan entities} 的清单)。 */
    static com.google.gson.JsonArray rowsIn(String reply) {
        String message = com.google.gson.JsonParser.parseString(reply).getAsJsonObject().get("message").getAsString();
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (String line : message.split("\n")) {
            if (line.startsWith("{")) {
                rows.add(com.google.gson.JsonParser.parseString(line));
            }
        }
        return rows;
    }

    /** 列出了 {@code cell} 这一格的那一团;没有为 null。 */
    static com.google.gson.JsonObject groupHolding(com.google.gson.JsonArray groups, BlockPos cell) {
        String wanted = cell.getX() + "," + cell.getY() + "," + cell.getZ();
        for (var element : groups) {
            var group = element.getAsJsonObject();
            if (!group.has("positions")) {
                continue;
            }
            for (var position : group.getAsJsonArray("positions")) {
                if (position.getAsString().equals(wanted)) {
                    return group;
                }
            }
        }
        return null;
    }

    /** {@code use block} 对着 {@code rel} 那一格按一下,同步调用。 */
    static TaskRecord click(GameTestHelper helper, NumenPlayer companion, String button, BlockPos rel) {
        return command(companion, "use block " + button + " " + at(helper, rel)).task();
    }

    /** {@code rel} 那一格的绝对坐标,写成命令行上的 {@code x y z}。 */
    static String at(GameTestHelper helper, BlockPos rel) {
        return xyz(helper.absolutePos(rel));
    }

    /** 一格的坐标写成命令行上的 {@code x y z}。 */
    static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }


    /** 一口自然箱子(不是玩家放的),第一格装着 {@code count} 颗钻石。 */
    static BlockPos chestWithDiamonds(GameTestHelper helper, BlockPos rel, int count) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(rel);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest))
                .setItem(0, new ItemStack(Items.DIAMOND, count));
        return chest;
    }

    /**
     * 让主人"在场":另起一具身体进玩家列表当主人。登记处只认主人在不在线——不在就当场按拒绝,
     * 答不答复就无从测起。答复由用例直接调登记处,等于主人在卡片上按了键。
     */
    static NumenPlayer presentOwner(GameTestHelper helper, NumenPlayer companion, String name) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(0, 2, 0));
        NumenPlayer owner = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(), name, UUID.randomUUID(),
                level, new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        companion.setOwnerUuid(owner.getUUID());
        return owner;
    }
    /**
     * 一个真玩家,不是同伴:主人要在聊天栏里敲 {@code /numen} 的管理指令(权限、征询)时用它——那些指令只给不是同伴的
     * 来源,{@link #presentOwner} 那具替身敲不了。没有客户端,连接照同伴的假连接丢掉一切下行包。{@code companion}
     * 非空时认他作主人。用完经 {@link #leave} 离开。
     */
    static net.minecraft.server.level.ServerPlayer presentPlayer(GameTestHelper helper, NumenPlayer companion,
                                                                 String name) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var player = new net.minecraft.server.level.ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new com.dwinovo.numen.entity.FakeConnection(), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        if (companion != null) {
            companion.setOwnerUuid(player.getUUID());
        }
        return player;
    }

    /** {@link #presentPlayer} 请来的玩家离开服务器。 */
    static void leave(net.minecraft.server.level.ServerPlayer player) {
        player.getServer().getPlayerList().remove(player);
    }

    /**
     * 按模型的样子调一次工具:按名字从工具表里取(和网络入口是同一张表),交同一份 JSON 参数,走同一个
     * {@link NumenTool#serve}。查询当场回执;身体动作派下去的那件活按调用 id 从调度器里取出来,
     * 收尾后读它交给模型的那句话。测的是工具本身,不经过模型。
     *
     * <p>派活的调用受理之前先准备,要搜索的准备结论出来那一刻才回执:那件活在回执到的那一刻取(受理时它已经进了槽);准备没过
     * 的调用没有活,回执就是它的结论。同步动作在回执到之前已经结算离槽,所以派下去当场先取一次。
     */
    static ToolRun call(NumenPlayer body, String toolName, JsonObject args) {
        NumenTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("no tool named " + toolName);
        }
        String id = "gametest-" + toolName + "-" + UUID.randomUUID();
        AtomicReference<String> replied = new AtomicReference<>();
        AtomicReference<TaskRecord> task = new AtomicReference<>();
        tool.serve(id, args, body, json -> {
            task.compareAndSet(null, CompanionTickDispatcher.taskOf(body.getUUID(), id));
            replied.set(json);
        });
        task.compareAndSet(null, CompanionTickDispatcher.taskOf(body.getUUID(), id));
        return new ToolRun(toolName, replied, task);
    }

    /**
     * 一件直接交执行器的建造活,不经命令:测的是执行器本身(施工顺序、扣料、落定、续建)。按设计或蓝图施工、当场执行原语
     * 从 {@code build} 命令进来的,见 BuildGameTests 里经 {@link #command} 调的那些。
     */
    static com.dwinovo.numen.core.task.build.BuildTaskRecord buildJob(String callId, long deadline,
            com.dwinovo.numen.core.build.Layout layout, boolean consume, boolean partial) {
        return new com.dwinovo.numen.core.task.build.BuildTaskRecord("build", callId, deadline, layout, consume,
                partial, null);
    }

    /** 只有方块的一件建造活,见 {@link #buildJob(String, long, com.dwinovo.numen.core.build.Layout, boolean, boolean)}。 */
    static com.dwinovo.numen.core.task.build.BuildTaskRecord buildJob(String callId, long deadline,
            List<com.dwinovo.numen.core.task.build.BuildTaskRecord.Target> targets, boolean consume, boolean partial) {
        return buildJob(callId, deadline, com.dwinovo.numen.core.build.Layout.of(targets, 0), consume, partial);
    }

    /**
     * 按模型的样子派一轮调用:模型一次回复里写了这几条,交给内脑派发的同一个顺序({@link SerialCalls})——一条做完才派
     * 下一条,后台活等它的 task_finished 进了队列才往下走。每条照 {@link #call} 从工具表取、走 {@link NumenTool#serve};
     * 主人不在线,收尾的事件进出箱,每刻把新到的条目按收件箱的急件规则交给这一轮一次,和内核转给派发器的一样。
     */
    static Round round(GameTestHelper helper, NumenPlayer body, LlmToolCall... calls) {
        Round round = new Round(body);
        helper.onEachTick(round::feed);
        round.calls.run(List.of(calls), round);
        return round;
    }

    /** 一轮里的一条工具调用。 */
    static LlmToolCall toolCall(String toolName, JsonObject args) {
        return new LlmToolCall("gametest-" + toolName + "-" + UUID.randomUUID(), toolName, args.toString());
    }

    /** 一轮里的一行指令:一条 {@code command} 工具调用。 */
    static LlmToolCall commandCall(String line) {
        return toolCall(com.dwinovo.numen.cli.CommandTool.NAME, args("command", line));
    }

    /** 一轮调用的现场:每条的结果、派出那一刻她站在哪、这一轮结算没有。 */
    static final class Round implements ToolPort.Sink {

        private final NumenPlayer body;
        /** 她的收件箱:进来的条目急不急由它的规则算,和主人客户端上同一条。 */
        private final EventQueue inbox = new EventQueue(EventQueue.Journal.NONE);
        private final SerialCalls calls;
        private final java.util.Map<String, String> results = new java.util.HashMap<>();
        private final java.util.Map<String, Vec3> startedAt = new java.util.HashMap<>();
        /** 出箱里已经交给这一轮的条目数。 */
        private int fed;
        private boolean settled;

        private Round(NumenPlayer body) {
            this.body = body;
            this.calls = new SerialCalls((call, done) -> ToolRegistry.get(call.name()).serve(call.id(),
                    JsonParser.parseString(call.arguments()).getAsJsonObject(), body, done),
                    com.dwinovo.numen.task.TaskDispatch::runningTaskOf,
                    com.dwinovo.numen.event.NumenEvents::finishedTaskOf);
        }

        /** 出箱里新到的事件交给这一轮。 */
        private void feed() {
            List<EventQueue.Entry> out = com.dwinovo.numen.entity.EventOutbox.get(body.getServer())
                    .peek(body.getUUID()).entries();
            for (; fed < out.size(); fed++) {
                arrive(out.get(fed));
            }
        }

        /** 主人开口说一句,和他在聊天框里说的一样进她的收件箱。 */
        void ownerSays(String words) {
            arrive(new EventQueue.Entry(com.dwinovo.numen.agent.inbox.EventTypes.QUERY,
                    "<query>" + words + "</query>", System.currentTimeMillis(), false));
        }

        private void arrive(EventQueue.Entry entry) {
            calls.arrived(entry, inbox.push(entry.type(), entry.text(), entry.ts(), entry.urgent()));
        }

        @Override
        public void started(LlmToolCall call) {
            startedAt.put(call.id(), body.position());
        }

        @Override
        public void finished(LlmToolCall call, String resultJson) {
            results.put(call.id(), resultJson);
        }

        @Override
        public void settled() {
            settled = true;
        }

        /** 这条调用的结果;还没有是 null。 */
        String result(LlmToolCall call) {
            return results.get(call.id());
        }

        /** 这条调用派出那一刻她站在哪;还没派出是 null。 */
        Vec3 startedAt(LlmToolCall call) {
            return startedAt.get(call.id());
        }

        /** 这一轮结算了:每条调用都有了结果。 */
        boolean hasSettled() {
            return settled;
        }
    }

    /** 按模型的样子执行一行指令:就是调一次 {@code command} 工具,和 {@link #call} 同一个入口。 */
    static ToolRun command(NumenPlayer body, String line) {
        return call(body, com.dwinovo.numen.cli.CommandTool.NAME, args("command", line));
    }

    /**
     * 一张按输出预算分页的清单,从第一页往后翻,直到哪一页里有 {@code needle}:清单跨次攒下来,要找的那条落在第几页由
     * 前面有多少条定。翻到最后一页也没有、或者哪一页失败了,返回那一页,由用例的断言说明白。
     */
    static ToolRun pageWith(NumenPlayer body, String line, String needle) {
        for (int page = 1; ; page++) {
            ToolRun run = command(body, line + " --page " + page);
            if (!run.succeeded() || run.reply().contains(needle)
                    || !run.reply().contains(" --page " + (page + 1) + " to continue.]")) {
                return run;
            }
        }
    }

    /** 拼工具参数:键、值交替;值是字符串、数字、布尔、列表(成 JSON 数组)或现成的 JSON。 */
    static JsonObject args(Object... keyValues) {
        JsonObject out = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.add((String) keyValues[i], json(keyValues[i + 1]));
        }
        return out;
    }

    private static JsonElement json(Object value) {
        if (value instanceof JsonElement e) return e;
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            list.forEach(v -> array.add(json(v)));
            return array;
        }
        throw new IllegalArgumentException("not a tool argument value: " + value);
    }

    /**
     * 一次工具调用:当场的回执,以及它派下去的那件活(查询类没有)。
     *
     * @param replied 当场的回执:查询的结果、后台任务的"已受理"、派发被拒的原因;同步动作不当场回执
     * @param task    派下去的那件活;没派活是 null
     */
    record ToolRun(String tool, AtomicReference<String> replied, AtomicReference<TaskRecord> taken) {

        String reply() {
            return replied.get();
        }

        /** 派下去的那件活;还没回执(准备还没结论)、或没派活(查询、准备没过)是 null。 */
        TaskRecord task() {
            return taken.get();
        }

        /** 受理了:回执到了,而且派下了一件活。 */
        boolean accepted() {
            return task() != null;
        }

        /** 当场拒绝:回执到了,是失败,没有派活——没有任务编号,也不会有 task_finished。 */
        boolean refused() {
            String r = replied.get();
            return r != null && task() == null
                    && !JsonParser.parseString(r).getAsJsonObject().get("success").getAsBoolean();
        }

        /** 有结论了:派了活的看那件活收没收尾,没派活的看回没回执。 */
        boolean done() {
            TaskRecord task = task();
            return task != null ? task.getResult() != null : replied.get() != null;
        }

        /** 结论的原话:派了活的是收尾时交给模型的那句话,没派活的是回执。还没有结论是 null。 */
        String outcome() {
            TaskRecord task = task();
            if (task != null) {
                return task.getResult() == null ? null : task.getResult().message();
            }
            return replied.get();
        }

        /** 结论是成功。回执不带 success 的查询(直接回一份数据)回了就算成功。 */
        boolean succeeded() {
            TaskRecord task = task();
            if (task != null) {
                return task.getResult() != null && task.getResult().success();
            }
            String r = replied.get();
            if (r == null) {
                return false;
            }
            JsonObject o = JsonParser.parseString(r).getAsJsonObject();
            return !o.has("success") || o.get("success").getAsBoolean();
        }
    }
}
