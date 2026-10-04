package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.JsonValues;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.sdk.Positions;
import com.dwinovo.numen.sdk.ProgramPort;
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
 * <p>更远的感知(goto 找方块扫 32 个 chunk、{@code numen.scan.blocks} 按用例给的半径、numen.work.collect 在工作区里捡掉落物、逃跑看 32~40 格)
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

    /**
     * numen 自己的 GameTest 这次开没开。NeoForge 登记用例时会初始化每一个带 {@code @GameTestHolder} 的类,不管它的命名空间
     * 开没开;夹具(只给用例用的命令组与任务)在这些类的静态块里登记,得先问这一句——评测与插件的 GameTest 只开自己的命名空间,
     * 也会加载这些类,她的工具表与命令索引里不该多出夹具。判据与 NeoForge 的 {@code GameTestHooks} 读同一个属性:没给或
     * 全是空白 = 全开。
     */
    static boolean numenTestsEnabled() {
        String property = System.getProperty("neoforge.enabledGameTestNamespaces");
        List<String> enabled = property == null ? List.of()
                : java.util.Arrays.stream(property.split(",")).filter(s -> !s.isBlank()).toList();
        return enabled.isEmpty() || enabled.contains(Constants.MOD_ID);
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

    /** {@code numen.scan.blocks} 在半径 {@code radius} 内找 {@code blockId}(回执稍后才到)。 */
    static ToolRun scan(NumenPlayer companion, int radius, String blockId) {
        return lua(companion, "numen.scan.blocks(\"" + blockId + "\", {radius = " + radius + "})");
    }

    /**
     * 照模型挖矿的写法走一遍:在半径 {@code radius} 内找 {@code blockId}({@code numen.scan.blocks}),扫的回执一到,就把找到的
     * 全部方块原样写进程序({@link #blocksIn}){@linkplain #mine 挖}够 {@code count} 格。扫描失败时不挖,这次挖矿的结论就是扫描的回执。
     */
    static Mining mineScanned(GameTestHelper helper, NumenPlayer companion, int radius, String blockId, int count) {
        Mining mining = new Mining(companion, scan(companion, radius, blockId), null, count);
        helper.onEachTick(mining::tick);
        return mining;
    }

    /**
     * 当场就挖 {@code blocks}(一团,或一串 Block 或 Pos 的 Lua 写法),和库里的 {@code numen.work.mine} 同一个流程、拆成原子调用一轮轮
     * 组合:先走——{@code numen.move.to(blocks, {arrive = "dig", costs = …})} 走到一次够得着最多格的地方,再挖——
     * {@code numen.work.dig(blocks, {count = 还差几格})} 挖手够得着的,{@code numen.work.collect} 许挖许放地捡这一挖还落在地上的
     * 掉落物(挖的结果里的 {@code drops});那一团还有没挖的格,就再来一轮。每一步是一段一行的程序,方块每次原样写进去;挖了几格读那件活的收尾数据,脚本里拿不到它。
     * 不另挂每刻的回调,每次问 {@link Mining#done} 时往下推一步。
     *
     * <p>哪一步(走、挖)失败,这次挖矿就以那一步的结论收场;挖够了、那一团不剩了,或一轮一格也没挖到,以最后一次挖的结论收场。
     *
     * @param count 挖够几格;0 是手边与够不着的都挖完为止
     */
    static Mining mine(NumenPlayer companion, String blocks, int count) {
        return new Mining(companion, null, blocks, count);
    }

    /**
     * {@link #mine} 那一次挖矿:每刻看当前这一步有没有结论,有就派下一步。收场之前 {@link #done} 为假、{@link #outcome} 为
     * null;收场之后与收场那一步的 {@link ToolRun} 读法相同。
     */
    static final class Mining {

        /** 一团最多走几轮:每轮至少挖掉一格,轮数到了还没挖完就以最后一次挖的结论收场。 */
        private static final int MAX_ROUNDS = 16;

        private enum Step { BEFORE, GOTO, DIG, COLLECT }

        private final NumenPlayer companion;
        /** 挖的那些方块的 Lua 写法;先扫再挖的,扫的回执到了才有。 */
        private String blocks;
        private final int count;
        private Step step = Step.BEFORE;
        private ToolRun current;
        private ToolRun lastDig;
        /** 收场的那一步;没收场是 null。 */
        private ToolRun last;
        private int dug;
        private int dugThisRound;
        private int rounds;

        /** 许挖许放、要问主人的格当墙:走去挖、走去捡都按它。 */
        private static final String COSTS = "costs = {dig = true, place = true, consent = false}";

        private Mining(NumenPlayer companion, ToolRun before, String blocks, int count) {
            this.companion = companion;
            this.blocks = blocks;
            this.count = count;
            this.current = before;
            if (before == null) {
                walk();
            }
        }

        private void tick() {
            if (last != null || !current.done()) {
                return;
            }
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] mine {} -> {}", step, current.outcome());
            switch (step) {
                case BEFORE, GOTO -> {
                    if (!current.succeeded()) {
                        last = current;
                    } else if (step == Step.BEFORE) {
                        blocks = blocksIn(current.reply());
                        walk();
                    } else {
                        run(Step.DIG, "numen.work.dig(" + blocks + (count > 0 ? ", {count = " + (count - dug) + "}" : "")
                                + ")");
                    }
                }
                case DIG -> {
                    if (!current.succeeded()) {
                        last = current;
                        return;
                    }
                    lastDig = current;
                    dugThisRound = current.result(DigCompanionTask.Dug.class).dug();
                    dug += dugThisRound;
                    run(Step.COLLECT, "numen.work.collect({items = " + dropIds(current) + ", " + COSTS + "})");
                }
                // 捡的库函数改地形走到每一件跟前;捡不到的(走不到、包满)不算挖矿失败,接着下一轮
                case COLLECT -> nextRound();
            }
        }

        /** 挖够了、那一团不剩了,或这一轮一格也没挖到,就以最后一次挖的结论收场;否则再走一轮。 */
        private void nextRound() {
            long left = lastDig.result(DigCompanionTask.Dug.class).left();
            if ((count > 0 && dug >= count) || left == 0 || dugThisRound == 0 || ++rounds >= MAX_ROUNDS) {
                last = lastDig;
            } else {
                walk();
            }
        }

        /** 那一挖还在世界里的掉落物({@code drops} 里带 {@code id} 的),写成 Lua 的一张表:{@code {{id = 12}, {id = 15}}}。 */
        private static String dropIds(ToolRun dig) {
            List<String> ids = new ArrayList<>();
            for (var drop : dig.result(DigCompanionTask.Dug.class).drops()) {
                drop.id().ifPresent(id -> ids.add("{id = " + id + "}"));
            }
            return "{" + String.join(", ", ids) + "}";
        }

        private void run(Step next, String code) {
            step = next;
            current = lua(companion, code);
        }

        /** 走到一次够得着这些方块最多格的地方。 */
        private void walk() {
            run(Step.GOTO, "numen.move.to(" + blocks + ", {arrive = \"dig\", " + COSTS + "})");
        }

        /** 挖的那些方块的 Lua 写法:再挖同样的那些时照抄;先扫再挖的,扫的回执到之前是 null。 */
        String blocks() {
            return blocks;
        }

        /** 各次 {@code numen.work.dig} 一共挖掉的格。 */
        int dug() {
            return dug;
        }

        /** 收场了。先往下推一步:当前这一步有了结论就派下一步。 */
        boolean done() {
            tick();
            return last != null;
        }

        boolean succeeded() {
            return last != null && last.succeeded();
        }

        String outcome() {
            return last == null ? null : last.outcome();
        }

        /** 收场那一步当场的回执:受理的"已受理"或拒收的原因;还没收场是 null。 */
        String reply() {
            return last == null ? null : last.reply();
        }

        /** 收场那一步派下的活;还没收场或那一步当场被拒是 null。 */
        TaskRecord task() {
            return last == null ? null : last.task();
        }
    }

    /** {@code numen.scan.blocks} 交回的团,近的在前。 */
    static com.google.gson.JsonArray clustersIn(String reply) {
        return valueIn(reply).getAsJsonArray();
    }

    /** 一团的方块写成 Lua 的一串 Block,和程序把扫描结果原样交出去一样。 */
    static String blocksOf(com.google.gson.JsonObject cluster) {
        List<String> blocks = new java.util.ArrayList<>();
        for (var element : cluster.getAsJsonArray("blocks")) {
            var block = element.getAsJsonObject();
            var pos = block.getAsJsonObject("pos");
            blocks.add("{name = \"" + block.get("name").getAsString() + "\", pos = {x = " + pos.get("x").getAsInt()
                    + ", y = " + pos.get("y").getAsInt() + ", z = " + pos.get("z").getAsInt() + "}}");
        }
        return "{" + String.join(", ", blocks) + "}";
    }

    /** 一次扫描找到的全部方块(各团连在一起)写成 Lua 的一串 Block。 */
    static String blocksIn(String scanReply) {
        List<String> all = new java.util.ArrayList<>();
        for (var cluster : clustersIn(scanReply)) {
            String blocks = blocksOf(cluster.getAsJsonObject());
            if (blocks.length() > 2) {
                all.add(blocks.substring(1, blocks.length() - 1));
            }
        }
        return "{" + String.join(", ", all) + "}";
    }

    /** 一次调用交回的值(JSON);失败时是错误值的 {@code data};都没有是 JSON 的 null。 */
    static JsonElement valueIn(String reply) {
        ApiReply.Parsed parsed = ApiReply.parse(reply);
        Object value = parsed.ok() ? parsed.value() : parsed.error().get("data");
        return value == null ? com.google.gson.JsonNull.INSTANCE : JsonValues.toJson(value);
    }

    /** 一段程序回执里的数据({@code status}、{@code calls}、{@code returned}、{@code error});没带是空表。 */
    static com.google.gson.JsonObject receiptData(String receipt) {
        com.google.gson.JsonObject o = JsonParser.parseString(receipt).getAsJsonObject();
        return o.has("data") ? o.getAsJsonObject("data") : new com.google.gson.JsonObject();
    }

    /** 一次调用交回的那张表,见 {@link #valueIn};不是一张表是空表。 */
    static com.google.gson.JsonObject dataIn(String reply) {
        JsonElement value = valueIn(reply);
        return value.isJsonObject() ? value.getAsJsonObject() : new com.google.gson.JsonObject();
    }

    /** 有 {@code cell} 这一格的那一团(它的方块里有一块的 pos 是这一格);没有为 null。 */
    static com.google.gson.JsonObject clusterHolding(com.google.gson.JsonArray clusters, BlockPos cell) {
        JsonElement wanted = JsonValues.toJson(Positions.value(cell));
        for (var element : clusters) {
            var cluster = element.getAsJsonObject();
            for (var block : cluster.getAsJsonArray("blocks")) {
                if (block.getAsJsonObject().get("pos").equals(wanted)) {
                    return cluster;
                }
            }
        }
        return null;
    }

    /** 对着 {@code rel} 那一格按一下,同步调用:左键是 {@code numen.use.hit},右键是 {@code numen.use.block}。 */
    static ToolRun click(GameTestHelper helper, NumenPlayer companion, String button, BlockPos rel) {
        return lua(companion, ("left".equals(button) ? "numen.use.hit(" : "numen.use.block(") + at(helper, rel) + ")");
    }

    /** {@code rel} 那一格的绝对坐标,写成脚本里的一格 Pos。 */
    static String at(GameTestHelper helper, BlockPos rel) {
        return xyz(helper.absolutePos(rel));
    }

    /** 一格写成交回的值里的样子(JSON):和程序拿到的一个 Pos 比对时用。 */
    static JsonElement posJson(BlockPos pos) {
        return JsonValues.toJson(Positions.value(pos));
    }

    /** 一格的坐标照回执里说一处地方的写法:{@code x y z}。 */
    static String words(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** 一格的坐标写成脚本里的一格 Pos({@link Positions#literal})。 */
    static String xyz(BlockPos pos) {
        return Positions.literal(pos);
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
     * 让主人"在场":一个玩家站到场地角上,认作她的主人。登记处只认主人在不在线——不在就当场按拒绝,答不答复就无从测起。
     * 答复由用例直接调登记处,等于主人在卡片上按了键。用完经 {@link #leave} 离开。
     */
    static net.minecraft.server.level.ServerPlayer presentOwner(GameTestHelper helper, NumenPlayer companion,
                                                                String name) {
        var owner = presentPlayer(helper, companion, name);
        BlockPos at = helper.absolutePos(new BlockPos(0, 2, 0));
        owner.teleportTo(helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        return owner;
    }
    /**
     * 一个真玩家,不是同伴:主人要在聊天栏里敲 {@code /numen} 的管理指令(权限、征询)时也用它。没有客户端,连接是
     * {@link OwnerLine}:她的世界事件照主人客户端那样交给在跑的程序,别的下行包丢掉。{@code companion} 非空时认他作主人。
     * 用完经 {@link #leave} 离开。
     */
    static net.minecraft.server.level.ServerPlayer presentPlayer(GameTestHelper helper, NumenPlayer companion,
                                                                 String name) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var player = new net.minecraft.server.level.ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new OwnerLine(), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        if (companion != null) {
            companion.setOwnerUuid(player.getUUID());
        }
        return player;
    }

    /**
     * 在场主人的连接:没有客户端。主人在线时她的世界事件({@code NumenEventPayload})直送主人客户端、不进出箱,主人客户端把它们
     * 交给在跑的那段程序——收尾事件就是这样让程序接着跑的;这里照做,交给她那几轮({@link #LIVE})。别的下行包没人要,丢掉。
     * 和评测的模拟主人同一个做法:内存通道、回环地址、不跑心跳、不断线;{@code configureMockConnection} 给它一张频道表,下行的
     * 模组载荷过得了 NeoForge 的频道检查。
     */
    private static final class OwnerLine extends net.minecraft.network.Connection {

        private static final java.net.InetSocketAddress LOOPBACK =
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0);

        OwnerLine() {
            super(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            new io.netty.channel.embedded.EmbeddedChannel(this);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(this);
        }

        @Override
        public java.net.SocketAddress getRemoteAddress() {
            return LOOPBACK;
        }

        @Override
        public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener listener,
                         boolean flush) {
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof com.dwinovo.numen.network.payload.NumenEventPayload events) {
                for (Round round : LIVE) {
                    if (round.body.getUUID().equals(events.entityUuid())) {
                        events.entries().forEach(round::arrive);
                    }
                }
            }
        }

        @Override
        public void runOnceConnected(java.util.function.Consumer<net.minecraft.network.Connection> action) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void tick() {
        }

        @Override
        public void disconnect(net.minecraft.network.chat.Component message) {
        }

        @Override
        public void disconnect(net.minecraft.network.DisconnectionDetails details) {
        }

        @Override
        public void handleDisconnection() {
        }

        @Override
        public void flushChannel() {
        }

        @Override
        public void setReadOnly() {
        }
    }

    /** {@link #presentPlayer} 请来的玩家离开服务器。 */
    static void leave(net.minecraft.server.level.ServerPlayer player) {
        player.getServer().getPlayerList().remove(player);
    }

    /**
     * 按模型的样子跑一段程序:交给内脑派发的同一个顺序({@link SerialCalls}),经同一个执行口逐个派 API 调用({@link ProgramPort}:
     * 服务端函数走派发、客户端函数当场在这里答),和产品里同样那几处。程序等它派的
     * 每件身体活收尾:主人不在线,收尾的事件进出箱,每个服务器刻把新到的条目按收件箱的急件规则交给这段程序({@link #LIVE});
     * 主人在场时事件直送他的连接,{@link OwnerLine} 照主人客户端那样交给这段程序。
     *
     * <p>返回的 {@link ToolRun} 看的是程序里最后一次派出的 API 调用(一行的程序就是那一次)——它当场的回执、它派下的活——和整段
     * 程序的回执。
     */
    static ToolRun lua(NumenPlayer body, String code) {
        ToolRun run = new ToolRun(code);
        Round round = new Round(body, run);
        LIVE.add(round);
        LlmToolCall program = programCall(code);
        round.calls.run(List.of(program), round);
        return run;
    }

    /** 在跑的程序:每个服务器刻把出箱里新到的事件交给它们,结算了的摘掉。 */
    private static final List<Round> LIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        // 她的 Lua 模块落在这一次 GameTest 运行专用的空目录里:只用内置原版,不读主人目录里的
        try {
            java.nio.file.Path modules = java.nio.file.Files.createTempDirectory("numen-gametest-lua-");
            com.dwinovo.numen.script.Modules.init(uuid -> modules);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> {
                    for (Round round : LIVE) {
                        round.feed();
                        if (round.hasSettled()) {
                            LIVE.remove(round);
                        }
                    }
                });
    }

    /**
     * 一件直接交执行器的建造活,不经脚本:测的是执行器本身(施工顺序、扣料、落定、续建)。按设计或蓝图施工、当场执行原语
     * 从 {@code build} 的函数进来的,见 BuildGameTests 里经 {@link #lua} 调的那些。
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
     * 按模型的样子派一轮调用:模型一次回复里写了这几段程序,交给内脑派发的同一个顺序({@link SerialCalls})——一段跑完才派下一段。
     * 收尾的事件照 {@link #lua} 每刻交给这一轮。
     */
    static Round round(GameTestHelper helper, NumenPlayer body, LlmToolCall... calls) {
        Round round = new Round(body, new ToolRun(""));
        LIVE.add(round);
        round.calls.run(List.of(calls), round);
        return round;
    }

    /** 一轮里的一段程序:一条跑脚本的工具调用。 */
    static LlmToolCall programCall(String code) {
        String tool = com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.toolName();
        return new LlmToolCall("gametest-" + tool + "-" + UUID.randomUUID(), tool,
                com.dwinovo.numen.agent.tool.ScriptTool.args(code).toString());
    }

    /** 看着一段程序的每次调用:派出时记一笔,回来时记下回执与它派下的活(调度器按调用 id 认得出)。 */
    private record Watch(NumenPlayer body, ToolRun run) implements ProgramPort.Observer {

        @Override
        public void dispatched(LlmToolCall line, Invocation invocation) {
            run.dispatched(line.id(), invocation.function());
        }

        @Override
        public void sent(LlmToolCall line) {
            run.call(line.id()).look(body);
        }

        @Override
        public void replied(LlmToolCall line, String reply) {
            ToolRun.Call call = run.call(line.id());
            call.look(body);
            call.replied.set(reply);
        }
    }

    /** 一轮调用的现场:每条的结果、派出那一刻她站在哪、这一轮结算没有。 */
    static final class Round implements ToolPort.Sink {

        private final NumenPlayer body;
        private final ToolRun run;
        /** 她的收件箱:进来的条目急不急由它的规则算,和主人客户端上同一条。 */
        private final EventQueue inbox = new EventQueue(EventQueue.Journal.NONE);
        private final SerialCalls calls;
        private final java.util.Map<String, String> results = new java.util.HashMap<>();
        private final java.util.Map<String, Vec3> startedAt = new java.util.HashMap<>();
        /**
         * 出箱里这一轮已经见过的条目(按对象认):开跑那一刻已在出箱里的不是它的。不按下标数:出箱有上限,攒满了旧的被挤掉,
         * 条数不再变,新来的照样要交给它——跑几百次调用的长程序就是这样攒满的。
         */
        private final java.util.Set<EventQueue.Entry> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private boolean settled;

        private Round(NumenPlayer body, ToolRun run) {
            this.body = body;
            this.run = run;
            this.calls = new SerialCalls(new ProgramPort(body, body.getUUID(),
                    com.dwinovo.numen.script.Modules.of(body.getUUID()), true, new Watch(body, run)));
            seen.addAll(outbox(body));
        }

        private static List<EventQueue.Entry> outbox(NumenPlayer body) {
            return com.dwinovo.numen.entity.EventOutbox.get(body.getServer()).peek(body.getUUID()).entries();
        }

        /** 出箱里新到的事件交给这一轮;还没回的调用看一眼它派下的活(等主人点头之后才派的短活在这时出现)。 */
        private void feed() {
            run.lookAll(body);
            for (EventQueue.Entry entry : outbox(body)) {
                if (seen.add(entry)) {
                    arrive(entry);
                }
            }
        }

        /** 主人开口说一句,和他在聊天框里说的一样进她的收件箱。 */
        void ownerSays(String words) {
            arrive(new EventQueue.Entry(com.dwinovo.numen.agent.inbox.EventTypes.QUERY,
                    EventQueue.query(words), System.currentTimeMillis(), false));
        }

        /**
         * 主人按停止,和主人客户端上同一个顺序:先收这一轮(在跑的脚本交出停在哪一行的回执),再叫停身体(主人客户端经
         * {@code CancelTasksPayload} 叫停的就是这一处)。
         */
        void ownerStops() {
            calls.cancel(true);
            CompanionTickDispatcher.cancelFor(body);
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
            run.receipt.set(resultJson);
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

    /**
     * 一张按输出预算分页的清单,从第一页往后翻,直到哪一页里有 {@code needle}:清单跨次攒下来,要找的那条落在第几页由
     * 前面有多少条定。翻到最后一页也没有、或者哪一页失败了,返回那一页,由用例的断言说明白。
     *
     * @param function 不带别的参数的那个函数,如 {@code numen.module.list}
     */
    static ToolRun pageWith(NumenPlayer body, String function, String needle) {
        for (int page = 1; ; page++) {
            ToolRun run = lua(body, function + "({page = " + page + "})");
            if (!run.succeeded() || run.reply().contains(needle)
                    || !run.reply().contains("page = " + (page + 1) + " to continue.]")) {
                return run;
            }
        }
    }

    /** 拼一份 JSON:键、值交替;值是字符串、数字、布尔、列表(成 JSON 数组)或现成的 JSON。 */
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
        throw new IllegalArgumentException("not a JSON value: " + value);
    }

    /**
     * 一段程序:它最后派出的那次 API 调用(当场的回执、派下去的那件活),派出过的每一次,和整段程序跑完的回执。一行的程序看的
     * 就是那一次调用,读法与产品里模型读到的一样:查询的结果、后台任务的"已受理"、派发被拒的原因;后台活收尾时交给模型的那句话。
     * 一次调用都没派出(脚本写错、调用的参数读不成)时,"那一次"的回执就是程序的回执。
     */
    static final class ToolRun {

        /** 派出过的一次 API 调用。 */
        static final class Call {
            final String id;
            final String function;
            final AtomicReference<String> replied = new AtomicReference<>();
            final AtomicReference<TaskRecord> taken = new AtomicReference<>();

            private Call(String id, String function) {
                this.id = id;
                this.function = function;
            }

            /** 调度器此刻认得出它派下的活就记下:受理的、正在做的短活,或等主人点头之后才派的那件。 */
            void look(NumenPlayer body) {
                if (taken.get() == null) {
                    taken.compareAndSet(null, CompanionTickDispatcher.taskOf(body.getUUID(), id));
                }
            }
        }

        private final String code;
        private final List<Call> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicReference<String> receipt = new AtomicReference<>();

        ToolRun(String code) {
            this.code = code;
        }

        private void dispatched(String id, String function) {
            calls.add(new Call(id, function));
        }

        private Call call(String id) {
            for (Call call : calls) {
                if (call.id.equals(id)) {
                    return call;
                }
            }
            throw new IllegalStateException("no call " + id + " was dispatched");
        }

        private Call last() {
            return calls.isEmpty() ? null : calls.get(calls.size() - 1);
        }

        /** 还没回的每次调用看一眼它派下的活。 */
        private void lookAll(NumenPlayer body) {
            for (Call call : calls) {
                if (call.replied.get() == null) {
                    call.look(body);
                }
            }
        }

        /** 这段程序。 */
        String code() {
            return code;
        }

        /** 最后一次调用当场的回执({@link ApiReply} 写的那一份);一次都没派出时是程序的回执;都还没有是 null。 */
        String reply() {
            Call last = last();
            return last != null ? last.replied.get() : receipt.get();
        }

        /** 最后一次调用的回执读成的样子;没派出、或还没回是 null。 */
        private ApiReply.Parsed parsed() {
            Call last = last();
            String r = last == null ? null : last.replied.get();
            return r == null ? null : ApiReply.parse(r);
        }

        /** 最后一次调用交回的值(脚本里的值:null、布尔、数、字符串、列表、表);占身体的活看 {@link #result}。 */
        Object value() {
            ApiReply.Parsed p = parsed();
            return p == null || !p.ok() ? null : p.value();
        }

        /** 最后一次调用交回的那张表的一个字段;不是表、没有这个字段是 null。 */
        Object field(String name) {
            return value() instanceof java.util.Map<?, ?> table ? table.get(name) : null;
        }

        /** 最后一次调用派下去的那件活收尾时的值(它的函数声明的返回类型);还没收尾是 null。 */
        <T> T result(Class<T> type) {
            TaskRecord task = task();
            return task == null || task.getResult() == null ? null : type.cast(task.getResult().value());
        }

        /** 最后一次调用派下去的那件活;还没回执、或没派活是 null。 */
        TaskRecord task() {
            Call last = last();
            return last == null ? null : last.taken.get();
        }

        /** 每次调用 {@code function} 派下去的活,按先后;没派活的那几次不在里面。 */
        List<TaskRecord> tasks(String function) {
            List<TaskRecord> out = new ArrayList<>();
            for (Call call : calls) {
                if (call.function.equals(function) && call.taken.get() != null) {
                    out.add(call.taken.get());
                }
            }
            return out;
        }

        /** 整段程序的回执(跑完、出错或被停下);还在跑是 null。 */
        String receipt() {
            return receipt.get();
        }

        /** 整段程序跑完了,而且跑到了最后。 */
        boolean ranToTheEnd() {
            String r = receipt.get();
            return r != null && JsonParser.parseString(r).getAsJsonObject().get("success").getAsBoolean();
        }

        /** 受理了:回执到了,是一件占身体的活的任务编号。 */
        boolean accepted() {
            ApiReply.Parsed p = parsed();
            return p != null && p.job() != null;
        }

        /** 当场失败:回执到了,是失败——没有任务编号,也不会有 task_finished。 */
        boolean refused() {
            ApiReply.Parsed p = parsed();
            return p != null && !p.ok();
        }

        /**
         * 有结论了:整段程序跑完了。只看最后一次调用的回执不行:一段几次调用的程序(库函数 numen.move.to 是两次)头一次回了执,
         * 后面的还没派。
         */
        boolean done() {
            return receipt.get() != null;
        }

        /**
         * 最后一次调用派下的那件活收尾了。跟随这类常驻的活不拦着程序:程序早跑完了,活还在跑——问活本身有没有结论看这里。
         */
        boolean ended() {
            TaskRecord task = task();
            return task != null && task.getResult() != null;
        }

        /**
         * 结论的原话,模型读到的那一句:受理了一件占身体的活的是收尾时那段实际账;别的调用,失败是错误的那句话,成功是等的这段时间里
         * 身体做了什么(短活的账、主人允许了什么),什么都没做就是交回的值写成脚本里的样子。一次都没派出时是程序回执里的那段话。还没有
         * 结论是 null。
         */
        String outcome() {
            if (last() == null) {
                String r = receipt.get();
                return r == null ? null : JsonParser.parseString(r).getAsJsonObject().get("message").getAsString();
            }
            ApiReply.Parsed p = parsed();
            if (p == null) {
                return null;
            }
            if (p.job() != null) {
                TaskRecord task = task();
                return task == null || task.getResult() == null ? null : task.getResult().message();
            }
            if (!p.ok()) {
                return String.valueOf(p.error().get("message"));
            }
            return p.account() != null && !p.account().isBlank() ? p.account() : ScriptEngine.IN_USE.value(p.value());
        }

        /** 失败的种类({@code out_of_reach}…):受理了活的是收尾结果的,别的是回执里的;成功或还没结论是 null。 */
        String kind() {
            if (accepted()) {
                TaskRecord task = task();
                return task == null || task.getResult() == null || task.getResult().kind() == null ? null
                        : task.getResult().kind().wire();
            }
            return failure("kind");
        }

        /** 失败时能照抄的下一步,取法同 {@link #kind};没有是 null。 */
        String hint() {
            if (accepted()) {
                TaskRecord task = task();
                return task == null || task.getResult() == null ? null : task.getResult().hint();
            }
            return failure("hint");
        }

        /** 没派活时失败的一项:一次调用的错误值里的;一次都没派出(脚本写错)时是整段程序回执的 {@code data.error} 里的。 */
        private String failure(String key) {
            ApiReply.Parsed p = parsed();
            if (p != null) {
                return p.ok() || p.error().get(key) == null ? null : String.valueOf(p.error().get(key));
            }
            String r = receipt.get();
            if (r == null) {
                return null;
            }
            JsonObject o = JsonParser.parseString(r).getAsJsonObject();
            JsonObject data = o.has("data") ? o.getAsJsonObject("data") : null;
            JsonObject error = data != null && data.has("error") ? data.getAsJsonObject("error") : null;
            return error != null && error.has(key) ? error.get(key).getAsString() : null;
        }

        /** 结论是成功:受理了活的看收尾,别的看回执。 */
        boolean succeeded() {
            if (accepted()) {
                TaskRecord task = task();
                return task != null && task.getResult() != null && task.getResult().success();
            }
            ApiReply.Parsed p = parsed();
            return p != null && p.ok();
        }
    }
}
