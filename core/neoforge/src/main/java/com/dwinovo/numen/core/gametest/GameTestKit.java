package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
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

    /** scan_blocks 在半径 {@code radius} 内找 {@code blockId},回执落进返回数组的第一格。 */
    static String[] scan(NumenPlayer companion, int radius, String blockId) {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("radius", radius);
        com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        ids.add(blockId);
        args.add("block_ids", ids);
        String[] reply = new String[1];
        new com.dwinovo.numen.core.tools.perception.ScanBlocksTool().onServerCall("gametest-scan", args, companion,
                r -> reply[0] = r);
        return reply;
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
    static TaskRecord click(GameTestHelper helper, NumenPlayer companion, String button, BlockPos rel,
                                    String id) {
        BlockPos at = helper.absolutePos(rel);
        TaskRecord record = new BlockActionOps().interactAt(button, at.getX(), at.getY(), at.getZ(), null, null,
                TaskDispatch.ctx(id, companion));
        TaskDispatch.runSync(companion, record, reply -> {});
        return record;
    }
}
