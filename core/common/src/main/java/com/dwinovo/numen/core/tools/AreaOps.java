package com.dwinovo.numen.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.AreaStore;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Names;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.core.route.Itinerary;
import com.dwinovo.numen.core.route.Routes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 区域这个名词的增删改查与运算({@code area} 组),连同别的命令点名区域时的解析。区域跟着主人存({@link AreaStore}):同一个
 * 主人的同伴都认得、都能改;每一行都点名区域,没有"当前区域"。
 *
 * <p>查询(看、列)当场回,不占身体、不经权限层。改区域(新建、加一部分、删一部分、复核后划掉格、删掉、运算结果存成新的一块)
 * 是动作 {@code edit_area}:先经 {@link ServerSource#authorize} 由权限层裁决——主人的规则点名的区域要问主人,这次调用悬着等
 * 答复;这里不写死哪块能改、哪块不能。写之前的检查(有没有这块、维度对不对)当场说清,不去打扰主人;主人答复之后按那一刻的存档
 * 再算一遍才写,等答复的时候别人改过也不会写回旧值。
 */
public final class AreaOps {

    /**
     * 框一个盒子最多这么多格(2²⁴,位图约 2MB):一整座基地(几百万格)框得下,再大的一框就是几十 MB 的存档,拆成几块框。
     */
    static final long MAX_BOX_CELLS = 1L << 24;

    private AreaOps() {}

    // ==================== 找区域 ====================

    /**
     * 主人名下的区域。
     *
     * @throws IllegalArgumentException 她还没有主人:区域归主人
     */
    public static AreaStore store(NumenPlayer her) {
        UUID owner = her.getOwnerUuid();
        if (owner == null) {
            throw new IllegalArgumentException("areas belong to your owner, and you have no owner yet");
        }
        return AreaStore.of(her.getServer(), owner);
    }

    /**
     * {@code ref} 指的那一块或那一部分(整块,或只剩那一部分的区域),在她此刻所在的维度里:按名字找主人的区域只经
     * {@link NamedAreas},路线与别的命令点名区域也是它。
     *
     * @throws IllegalArgumentException 没有这块区域、区域里没有这一部分、区域在别的维度;说清有哪些
     */
    public static Area resolve(NumenPlayer her, AreaRef ref) {
        return NamedAreas.of(her).resolve(ref);
    }

    /**
     * 几处点名的并({@code ores/g1 ores/g3}):一处就是它自己。
     *
     * @throws IllegalArgumentException 哪一处点不到
     */
    public static Area resolveAll(NumenPlayer her, List<AreaRef> refs) {
        NamedAreas areas = NamedAreas.of(her);
        Area out = null;
        for (AreaRef ref : refs) {
            Area area = areas.resolve(ref);
            out = out == null ? area : out.union(area);
        }
        if (out == null) {
            throw new IllegalArgumentException("name at least one area");
        }
        return out;
    }

    /** 叫这个名字的那一整块,在她此刻所在的维度里;没有就说有哪些、怎么建。 */
    static Area existing(NumenPlayer her, String name) {
        return resolve(her, AreaRef.parse(name));
    }

    /**
     * {@code --into} 点名的那一整块能不能写:有就得在她此刻的维度,没有就在写的时候新建(像 shell 的 {@code >}),名字这里先验。
     *
     * @throws IllegalArgumentException 区域在别的维度,或名字不合规矩
     */
    static void into(NumenPlayer her, String name) {
        Area area = store(her).get(name);
        if (area == null) {
            Names.checked("area", name);
            return;
        }
        sameDimension(name, area, her.level().dimension());
    }

    // ==================== 改区域 ====================

    /** 新建一块空的区域,在她此刻所在的维度。 */
    public static void create(ServerSource src, String name, String what) {
        NumenPlayer her = src.companion();
        Names.checked("area", name);
        requireNew(her, name);
        ResourceKey<Level> dimension = her.level().dimension();
        src.authorize(Action.editArea(name), what, allowed -> {
            requireNew(her, name);
            store(her).create(name, Area.empty(dimension));
            allowed.reply(TaskResult.ok("made area " + name + " in " + dimension.location() + ", empty. Add to it with "
                    + "area add " + name + " (--box, --at, --built or --route), or scan blocks <radius> <block ids> "
                    + "--into " + name + " to add what a scan finds.").toJson());
        });
    }

    private static void requireNew(NumenPlayer her, String name) {
        if (store(her).get(name) != null) {
            throw new IllegalArgumentException("there is already an area named " + name + "; area show " + name
                    + " shows it, area delete " + name + " removes it");
        }
    }

    /** {@code area add} 加进来的一部分:怎么来的、哪些格、在哪个维度、回执里怎么说它。 */
    public record Source(Area.Kind kind, Cells cells, ResourceKey<Level> dimension, String words) {}

    /** 盒子 {@code x1,y1,z1..x2,y2,z2}(两角任意顺序),在她此刻的维度。 */
    public static Source box(NumenPlayer her, String written) {
        return new Source(Area.Kind.BOX, boxCells(written), her.level().dimension(), "box " + written.strip());
    }

    /**
     * 读盒子的写法 {@code x1,y1,z1..x2,y2,z2}(两角任意顺序)成格子。
     *
     * @throws IllegalArgumentException 写法不对,或大过 {@link #MAX_BOX_CELLS}
     */
    static Cells boxCells(String written) {
        String raw = written.strip();
        int sep = raw.indexOf(AreaText.BOX_SEPARATOR);
        if (sep < 0) {
            throw new IllegalArgumentException("a box is two corners x1,y1,z1..x2,y2,z2, got \"" + written + "\"");
        }
        BlockPos a = corner(raw.substring(0, sep), written);
        BlockPos b = corner(raw.substring(sep + AreaText.BOX_SEPARATOR.length()), written);
        long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1)
                * (Math.abs(a.getZ() - b.getZ()) + 1);
        if (volume > MAX_BOX_CELLS) {
            throw new IllegalArgumentException("a box holds at most " + MAX_BOX_CELLS + " cells and " + written
                    + " holds " + volume + "; frame it in smaller boxes");
        }
        return Cells.box(a, b);
    }

    private static BlockPos corner(String text, String written) {
        String[] xyz = text.split(",");
        if (xyz.length != 3) {
            throw new IllegalArgumentException("a box is two corners x1,y1,z1..x2,y2,z2, got \"" + written + "\"");
        }
        try {
            return new BlockPos(Integer.parseInt(xyz[0].strip()), Integer.parseInt(xyz[1].strip()),
                    Integer.parseInt(xyz[2].strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("a box is two corners x1,y1,z1..x2,y2,z2 of whole numbers, got \""
                    + written + "\"");
        }
    }

    /** 一个点 {@code x y z},在她此刻的维度。 */
    public static Source point(NumenPlayer her, List<Integer> at) {
        if (at.size() != 3) {
            throw new IllegalArgumentException("--at takes one cell: x y z; got " + at.size() + " numbers");
        }
        BlockPos pos = new BlockPos(at.get(0), at.get(1), at.get(2));
        return new Source(Area.Kind.POINT, Cells.point(pos), her.level().dimension(), "the cell "
                + AreaText.cell(pos));
    }

    /** 一栋建成的房子({@code house#1})放下、现在还记着的格:{@code Built} 是这件事唯一的出处。 */
    public static Source built(NumenPlayer her, String name) {
        List<Built.Building> all = Built.of(her.getServer()).all();
        for (Built.Building building : all) {
            if (building.name().equals(name)) {
                if (building.cells().isEmpty()) {
                    throw new IllegalArgumentException(name + " has no cells on record: nothing of it is standing");
                }
                List<BlockPos> cells = building.cells().keySet().stream().map(BlockPos::of).toList();
                return new Source(Area.Kind.CELLS, Cells.of(cells), dimension(building.dimension()),
                        "the " + cells.size() + " cells " + name + " was built of");
            }
        }
        throw new IllegalArgumentException("there is no building named " + name + "; build built lists them"
                + (all.isEmpty() ? " (none yet)" : ""));
    }

    /** 一条路线最近一次计划要改的格:要挖的与要放的。 */
    public static Source route(NumenPlayer her, String name) {
        store(her);
        Itinerary route = Routes.of(her.getServer(), her.getOwnerUuid()).get(name);
        if (route == null) {
            throw new IllegalArgumentException("there is no route named " + name + "; route list shows the routes");
        }
        if (route.plan() == null) {
            throw new IllegalArgumentException("route " + name + " has no plan yet; route plan " + name + " plans it");
        }
        LongSet cells = new LongOpenHashSet(route.plan().digs());
        cells.addAll(route.plan().places());
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("the plan of route " + name + " changes no cell");
        }
        List<BlockPos> list = new ArrayList<>(cells.size());
        cells.forEach((long p) -> list.add(BlockPos.of(p)));
        return new Source(Area.Kind.CELLS, Cells.of(list), dimension(route.dimension()),
                "the " + list.size() + " cells the plan of route " + name + " changes");
    }

    private static ResourceKey<Level> dimension(ResourceLocation id) {
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    /** 给一块区域加一部分。 */
    public static void add(ServerSource src, String name, Source source, String what) {
        NumenPlayer her = src.companion();
        Area area = existing(her, name);
        sameDimension(name, area, source.dimension());
        src.authorize(Action.editArea(name), what, allowed -> {
            Area next = existing(her, name).with(source.kind(), source.cells());
            store(her).replace(name, next);
            Area.Part added = next.parts().get(next.parts().size() - 1);
            allowed.reply(TaskResult.ok("added " + name + "/" + added.id() + ": " + source.words() + ", "
                    + added.cells().size() + " cells. " + AreaText.summary(name, next) + ".").toJson());
        });
    }

    private static void sameDimension(String name, Area area, ResourceKey<Level> dimension) {
        if (!area.dimension().equals(dimension)) {
            throw new IllegalArgumentException("area " + name + " is in " + area.dimension().location() + " and this is in "
                    + dimension.location() + "; an area belongs to one dimension");
        }
    }

    /** 删掉一部分;别的部分编号不变。 */
    public static void drop(ServerSource src, String name, String part, String what) {
        NumenPlayer her = src.companion();
        resolve(her, AreaRef.parse(name + "/" + part));
        src.authorize(Action.editArea(name), what, allowed -> {
            Area next = existing(her, name).without(part);
            store(her).replace(name, next);
            allowed.reply(TaskResult.ok("dropped " + name + "/" + part + ". " + AreaText.summary(name, next) + ".")
                    .toJson());
        });
    }

    /** 删掉一整块。 */
    public static void delete(ServerSource src, String name, String what) {
        NumenPlayer her = src.companion();
        existing(her, name);
        src.authorize(Action.editArea(name), what, allowed -> {
            existing(her, name);
            store(her).delete(name);
            allowed.reply(TaskResult.ok("deleted area " + name).toJson());
        });
    }

    /**
     * 按活世界复核附带方块的格:现在已经不是当时那种方块的({@link Cells.Seen#holds})划掉。框出来的格不附带方块,不复核;
     * 没加载的格读不到,留着并照实说。一格都没变就不改区域,也就不经权限层。
     */
    public static void refresh(ServerSource src, String name, String what) {
        NumenPlayer her = src.companion();
        Area area = existing(her, name);
        Recheck first = recheck(area, her.serverLevel());
        if (first.stale().isEmpty()) {
            src.reply(TaskResult.ok("all " + first.checked() + " scanned cells of " + name + " still hold what was "
                    + "seen" + first.unloadedClause() + "; nothing to strike.").toJson());
            return;
        }
        src.authorize(Action.editArea(name), what, allowed -> {
            Area now = existing(her, name);
            Recheck check = recheck(now, her.serverLevel());
            Area next = check.stale().isEmpty() ? now
                    : now.minus(Area.of(now.dimension(), Area.Kind.CELLS, Cells.of(check.stale())));
            store(her).replace(name, next);
            allowed.reply(TaskResult.ok("struck off " + check.stale().size() + " of the " + check.checked()
                    + " scanned cells of " + name + ": they no longer hold what was seen" + check.unloadedClause()
                    + ". " + AreaText.summary(name, next) + ".").toJson());
        });
    }

    /** 一次复核:看了几格附带方块的格,哪些变了,几格没加载没看。 */
    private record Recheck(int checked, List<BlockPos> stale, int unloaded) {
        String unloadedClause() {
            return unloaded == 0 ? "" : " (" + unloaded + " lie in unloaded terrain and were kept unchecked)";
        }
    }

    private static Recheck recheck(Area area, ServerLevel level) {
        int[] counts = new int[2];
        List<BlockPos> stale = new ArrayList<>();
        area.cells().forEach((x, y, z, seen) -> {
            if (seen == null) {
                return;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                counts[1]++;
                return;
            }
            counts[0]++;
            if (!seen.holds(level.getBlockState(pos))) {
                stale.add(pos);
            }
        });
        return new Recheck(counts[0] + counts[1], stale, counts[1]);
    }

    /**
     * 运算结果存成一块新的区域 {@code result}:{@code operation} 拿点名的几块算出它。结果当场算好、存的是格子,不存算式。
     *
     * @param operation 从点名的区域算出新区域;点不到、维度不同时抛出,说清为什么
     */
    public static void derive(ServerSource src, String result, String what, UnaryOperator<Area> operation,
                              Area operand) {
        NumenPlayer her = src.companion();
        Names.checked("area", result);
        requireNew(her, result);
        Area computed = operation.apply(operand);
        src.authorize(Action.editArea(result), what, allowed -> {
            requireNew(her, result);
            store(her).create(result, computed);
            allowed.reply(TaskResult.ok("made area " + AreaText.summary(result, computed) + ".").toJson());
        });
    }

    /** 按方块筛:留附带的方块是这几种(或这几类)的格;框出来的格不知道是什么,不留。 */
    public static UnaryOperator<Area> filter(List<String> blocks) {
        Set<Block> kinds = ToolParse.parseBlocks(blocks);
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("--blocks names no block this server knows: " + blocks);
        }
        return area -> area.filter(state -> kinds.contains(state.getBlock()));
    }

    // ==================== 查询 ====================

    /**
     * 一块区域(或其中一部分):抬头是整块的小结,之后每部分一行——格数、附带的方块、最近一格、包围盒、挖它此刻许不许
     * (逐格用挖掘落点会提交的同一个动作问,在她此刻的世界里)。按输出预算分页。
     */
    public static String show(NumenPlayer her, AreaRef ref, CommandArgs args, String again) {
        Area whole = existing(her, ref.name());
        Area shown = resolve(her, ref);
        ServerLevel level = her.serverLevel();
        Gate gate = Permission.gateFor(her);
        BlockPos feet = her.blockPosition();
        List<String> rows = new ArrayList<>(shown.parts().size());
        for (Area.Part part : shown.parts()) {
            JsonObject row = AreaText.part(ref.name() + "/" + part.id(), part.cells(), feet);
            row.addProperty("box", AreaText.box(part.cells().bounds()));
            judgeNow(row, part.cells(), gate, level);
            rows.add(row.toString());
        }
        String head = "area " + AreaText.summary(ref.name(), whole)
                + (shown.parts().isEmpty() ? "." : (ref.part() == null ? "" : "; showing " + ref.part()) + ". One part "
                        + "per line: blocks are as they were seen when added (framed parts carry none), permission is "
                        + "asked now for breaking what stands in each cell:");
        return new Listing(head, rows, "", again, AreaText.PAGE_BYTES).result(args).toJson();
    }

    /**
     * 这些格此刻挖它许不许:逐格用挖掘落点会提交的 {@link Action#breakBlock} 问 {@link Gate#judgeLive},说法相同的数在一起。
     * 空气没东西可挖,没加载的读不到,各自数出来。说法只有一种时写成和扫描一样的 {@code permission} + {@code reason}。
     */
    private static void judgeNow(JsonObject row, Cells cells, Gate gate, ServerLevel level) {
        Map<String, Integer> said = new LinkedHashMap<>();
        Map<String, Verdict> first = new LinkedHashMap<>();
        int[] other = new int[2];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        cells.forEach((x, y, z, seen) -> {
            pos.set(x, y, z);
            if (!level.isLoaded(pos)) {
                other[1]++;
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                other[0]++;
                return;
            }
            Verdict verdict = gate.judgeLive(Action.breakBlock(pos, state), level);
            String key = verdict.kind().name().toLowerCase(java.util.Locale.ROOT)
                    + (verdict.allowed() ? "" : " (" + verdict.reason() + ")");
            said.merge(key, 1, Integer::sum);
            first.putIfAbsent(key, verdict);
        });
        if (said.size() == 1) {
            Verdict verdict = first.values().iterator().next();
            row.addProperty("permission", verdict.kind().name().toLowerCase(java.util.Locale.ROOT));
            if (!verdict.allowed()) {
                row.addProperty("reason", verdict.reason());
            }
        } else if (!said.isEmpty()) {
            JsonObject mixed = new JsonObject();
            said.forEach(mixed::addProperty);
            row.add("permission", mixed);
        }
        if (other[0] > 0) {
            row.addProperty("air_now", other[0]);
        }
        if (other[1] > 0) {
            row.addProperty("not_loaded", other[1]);
        }
    }

    /** 主人的全部区域,一块一行。 */
    public static String list(NumenPlayer her, CommandArgs args, String again) {
        List<String> rows = new ArrayList<>();
        store(her).all().forEach((name, area) -> rows.add(AreaText.summary(name, area)));
        String head = rows.isEmpty()
                ? "No areas yet: scan blocks <radius> <block ids> --into <name> makes one from what a scan finds; area new <name> makes an empty one."
                : "Areas of your owner, shared by all of their companions:";
        return new Listing(head, rows, "", again).result(args).toJson();
    }
}
