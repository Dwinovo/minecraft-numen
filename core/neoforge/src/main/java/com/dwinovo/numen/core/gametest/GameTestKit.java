package com.dwinovo.numen.core.gametest;

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

    /** goto/plan_route 的 spec:可自然改动。 */
    static com.google.gson.JsonObject naturalSpec() {
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.addProperty("alter", "natural");
        return spec;
    }

    /** 回执里点名的第一个路线 id(r1、r2……)。 */
    static String firstRouteId(String reply) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\br\\d+\\b").matcher(reply);
        return m.find() ? m.group() : null;
    }

    /** scan_blocks 在半径 {@code radius} 内找 {@code blockId}(回执稍后才到)。 */
    static ToolRun scan(NumenPlayer companion, int radius, String blockId) {
        return call(companion, "scan_blocks", args("radius", radius, "block_ids", List.of(blockId)));
    }

    static com.google.gson.JsonArray groupsIn(String reply) {
        return com.google.gson.JsonParser.parseString(reply).getAsJsonObject().getAsJsonArray("groups");
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

    /** interact_at 对着 {@code rel} 那一格按一下,同步调用。 */
    static TaskRecord click(GameTestHelper helper, NumenPlayer companion, String button, BlockPos rel) {
        BlockPos at = helper.absolutePos(rel);
        return call(companion, "interact_at", args("button", button, "x", at.getX(), "y", at.getY(), "z", at.getZ()))
                .task();
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
     * 按模型的样子调一次工具:按名字从工具表里取(和网络入口是同一张表),交同一份 JSON 参数,走同一个
     * {@link NumenTool#serve}。查询当场回执;身体动作派下去的那件活按调用 id 从调度器里取出来,
     * 收尾后读它交给模型的那句话。测的是工具本身,不经过模型。
     */
    static ToolRun call(NumenPlayer body, String toolName, JsonObject args) {
        NumenTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("no tool named " + toolName);
        }
        String id = "gametest-" + toolName + "-" + UUID.randomUUID();
        AtomicReference<String> replied = new AtomicReference<>();
        tool.serve(id, args, body, replied::set);
        return new ToolRun(toolName, replied, CompanionTickDispatcher.taskOf(body.getUUID(), id));
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
    record ToolRun(String tool, AtomicReference<String> replied, TaskRecord task) {

        String reply() {
            return replied.get();
        }

        /** 有结论了:派了活的看那件活收没收尾,没派活的看回没回执。 */
        boolean done() {
            return task != null ? task.getResult() != null : replied.get() != null;
        }

        /** 结论的原话:派了活的是收尾时交给模型的那句话,没派活的是回执。还没有结论是 null。 */
        String outcome() {
            if (task != null) {
                return task.getResult() == null ? null : task.getResult().message();
            }
            return replied.get();
        }

        /** 结论是成功。回执不带 success 的查询(直接回一份数据)回了就算成功。 */
        boolean succeeded() {
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
