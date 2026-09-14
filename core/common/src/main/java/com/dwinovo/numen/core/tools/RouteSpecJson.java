package com.dwinovo.numen.core.tools;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.core.pathing.spec.CellClass;
import com.dwinovo.numen.core.pathing.spec.PositionCosts;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 工具面的路线规格:{@code goto} 与 {@code plan_route} 的 {@code spec} 参数长什么样
 * ({@link #schema})、怎么变成 {@link RouteSpec}({@link #parse})——JSON 到规格的翻译全仓
 * 只此一处。旋钮名用模型看得懂的普通词,按规格的四组组织:
 * <ul>
 *   <li>能力:{@code alter}(none/natural)、{@code parkour}、{@code climb_vines}、
 *       {@code max_fall}、{@code max_alterations};</li>
 *   <li>每类代价:{@code avoid}——要排除的格子类型({@link CellClass} 名);</li>
 *   <li>按位置 / 按种类:{@code avoid_break}、{@code avoid_place}、{@code avoid_step}——
 *       方块 id、{@code #标签},或坐标 {@code x,y,z} / 坐标盒 {@code x1,y1,z1..x2,y2,z2};</li>
 *   <li>动作代价:{@code penalties} 里的 {@code place}/{@code break}/{@code jump}/{@code wade}。</li>
 * </ul>
 * 全部可选,不给就是出厂值(只走不改)。每一条错都报教学式错误——名字打错、坐标格式不对、
 * 方块 id 不存在——模型下一次就写对。
 */
public final class RouteSpecJson {

    private RouteSpecJson() {}

    /** 坐标盒两角之间的分隔。 */
    private static final String BOX_SEPARATOR = "..";
    private static final double MAX_PENALTY = 1000.0;

    /** {@code spec} 对象的字段(挂在调用方的 {@code optionalObject} 里)。 */
    public static void schema(Schema.Builder b) {
        b.optionalEnum("alter", "Whether the walk may change the world: 'none' (default) never "
                        + "breaks or places a block; 'natural' may dig, bridge and pillar through "
                        + "natural terrain and every change is itemised in the result.",
                        "none", "natural")
                .optionalStringArray("avoid", "Cell types to keep out of entirely. Names: "
                        + cellNames() + ". E.g. ['water'] to stay dry, ['door'] to never pass doors.")
                .optionalObject("penalties", "Extra cost per action (higher = the planner prefers "
                        + "a longer route over doing it). Defaults: place 20, break 30, jump 2, wade 3.",
                        p -> p.optionalNumber("place", "Per block placed.", 0, MAX_PENALTY)
                                .optionalNumber("break", "Per block broken, on top of dig time.", 0, MAX_PENALTY)
                                .optionalNumber("jump", "Per jump — raise it for a flatter walk.", 0, MAX_PENALTY)
                                .optionalNumber("wade", "Per block of water walked.", 0, MAX_PENALTY))
                .optionalStringArray("avoid_break", "Never break these: block ids ('minecraft:chest'), "
                        + "tags ('#minecraft:logs'), a cell 'x,y,z' or a box 'x1,y1,z1..x2,y2,z2'.")
                .optionalStringArray("avoid_place", "Never place a block into cells holding these "
                        + "(e.g. 'minecraft:water'), or into this cell/box — same forms as avoid_break.")
                .optionalStringArray("avoid_step", "Never stand on these blocks (e.g. 'minecraft:farmland', "
                        + "'#minecraft:crops') or on this cell/box — same forms as avoid_break.")
                .optionalBool("parkour", "Allow running jumps over 2-4 block gaps. Default false.")
                .optionalBool("climb_vines", "Allow climbing vines. Default false.")
                .optionalInteger("max_fall", "Highest drop she may take without water below, in blocks. "
                        + "Default 3; she may still fall further when her health can take it.", 0, 64)
                .optionalInteger("max_alterations", "At most this many blocks changed on the whole route "
                        + "(only meaningful with alter:natural).", 0, 10_000);
    }

    /** {@code null} 或空对象即出厂规格。 */
    public static RouteSpec parse(JsonObject json) {
        RouteSpec spec = RouteSpec.defaults();
        if (json == null) {
            return spec;
        }
        if (json.has("alter")) {
            spec = spec.withAlter(alter(string(json, "alter")));
        }
        if (json.has("avoid")) {
            for (JsonElement e : array(json, "avoid")) {
                spec = spec.withCellCost(cellClass(e.getAsString()), RouteSpec.FORBID);
            }
        }
        if (json.has("penalties")) {
            JsonObject p = object(json, "penalties");
            if (p.has("place")) {
                spec = spec.withPlaceCost(penalty(p, "place"));
            }
            if (p.has("break")) {
                spec = spec.withBreakPenalty(penalty(p, "break"));
            }
            if (p.has("jump")) {
                spec = spec.withJumpPenalty(penalty(p, "jump"));
            }
            if (p.has("wade")) {
                spec = spec.withWadePenalty(penalty(p, "wade"));
            }
        }
        Bans breaking = bans(json, "avoid_break");
        Bans placing = bans(json, "avoid_place");
        Bans standing = bans(json, "avoid_step");
        PositionCosts.Builder cells = PositionCosts.builder();
        breaking.cells.forEach((long c) -> cells.dig(c, RouteSpec.FORBID));
        placing.cells.forEach((long c) -> cells.place(c, RouteSpec.FORBID));
        standing.cells.forEach((long c) -> cells.stand(c, RouteSpec.FORBID));
        spec = spec.withPositions(spec.positions().plus(cells.build()))
                .withBans(new RouteSpec.BlockBans(breaking.blocks, placing.blocks, standing.blocks));
        if (json.has("parkour")) {
            spec = spec.withParkour(bool(json, "parkour"));
        }
        if (json.has("climb_vines")) {
            spec = spec.withClimbVines(bool(json, "climb_vines"));
        }
        if (json.has("max_fall")) {
            spec = spec.withMaxFallHeightNoWater(nonNegativeInt(json, "max_fall"));
        }
        if (json.has("max_alterations")) {
            spec = spec.withMaxAlterations(nonNegativeInt(json, "max_alterations"));
        }
        return spec;
    }

    // ==================== 单项翻译 ====================

    private static RouteSpec.Alter alter(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "none" -> RouteSpec.Alter.NONE;
            case "natural" -> RouteSpec.Alter.NATURAL;
            default -> throw new IllegalArgumentException(
                    "spec.alter must be 'none' or 'natural', got '" + raw + "'");
        };
    }

    private static CellClass cellClass(String raw) {
        try {
            return CellClass.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "spec.avoid: unknown cell type '" + raw + "'; valid names: " + cellNames());
        }
    }

    private static String cellNames() {
        StringBuilder sb = new StringBuilder();
        for (CellClass c : CellClass.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static double penalty(JsonObject penalties, String name) {
        double v = number(penalties, name);
        if (v < 0 || v > MAX_PENALTY) {
            throw new IllegalArgumentException(
                    "spec.penalties." + name + " must be between 0 and " + (int) MAX_PENALTY + ", got " + v);
        }
        return v;
    }

    /** 一栏禁令:按种类的方块集合 + 按位置的格子集合。 */
    private record Bans(Set<Block> blocks, it.unimi.dsi.fastutil.longs.LongSet cells) {}

    private static Bans bans(JsonObject json, String field) {
        Set<Block> blocks = new LinkedHashSet<>();
        it.unimi.dsi.fastutil.longs.LongSet cells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if (!json.has(field)) {
            return new Bans(blocks, cells);
        }
        for (JsonElement e : array(json, field)) {
            String raw = e.getAsString().trim();
            if (looksLikeCoordinates(raw)) {
                cells.addAll(cellsOf(field, raw));
            } else {
                blocks.addAll(blocksOf(field, raw));
            }
        }
        return new Bans(blocks, cells);
    }

    /** 以数字或负号开头的就是坐标——方块 id 从字母或 # 开头。 */
    private static boolean looksLikeCoordinates(String raw) {
        return !raw.isEmpty() && (Character.isDigit(raw.charAt(0)) || raw.charAt(0) == '-');
    }

    /** {@code x,y,z} 一格,或 {@code x1,y1,z1..x2,y2,z2} 一个盒子(两角任意顺序)。 */
    private static it.unimi.dsi.fastutil.longs.LongSet cellsOf(String field, String raw) {
        it.unimi.dsi.fastutil.longs.LongSet out = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        int sep = raw.indexOf(BOX_SEPARATOR);
        BlockPos a = corner(field, sep < 0 ? raw : raw.substring(0, sep));
        BlockPos b = sep < 0 ? a : corner(field, raw.substring(sep + BOX_SEPARATOR.length()));
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            out.add(p.asLong());
        }
        return out;
    }

    private static BlockPos corner(String field, String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("spec." + field + ": a cell is 'x,y,z' and a box is"
                    + " 'x1,y1,z1..x2,y2,z2', got '" + raw + "'");
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("spec." + field + ": coordinates must be integers, got '"
                    + raw + "'");
        }
    }

    /** {@code #ns:tag} 展开成成员;{@code ns:block} 一种。不存在的报错,不静默跳过。 */
    private static Set<Block> blocksOf(String field, String raw) {
        Set<Block> out = new LinkedHashSet<>();
        TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
        if (tag != null) {
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                out.add(holder.value());
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("spec." + field + ": tag '" + raw + "' has no blocks");
            }
            return out;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            throw new IllegalArgumentException("spec." + field + ": unknown block '" + raw
                    + "' — use a namespaced id like minecraft:chest, a tag like #minecraft:logs,"
                    + " or a cell/box like 12,60,8 or 12,60,8..15,63,10");
        }
        out.add(block);
        return out;
    }

    // ==================== JSON 取值(类型错就报教学式错误) ====================

    private static String string(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("spec." + name + " must be a string");
        }
        return e.getAsString();
    }

    private static boolean bool(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("spec." + name + " must be true or false");
        }
        return e.getAsBoolean();
    }

    private static double number(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("spec.penalties." + name + " must be a number");
        }
        return e.getAsDouble();
    }

    private static int nonNegativeInt(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("spec." + name + " must be a whole number");
        }
        int v = e.getAsInt();
        if (v < 0) {
            throw new IllegalArgumentException("spec." + name + " must be 0 or more, got " + v);
        }
        return v;
    }

    private static JsonArray array(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (!e.isJsonArray()) {
            throw new IllegalArgumentException("spec." + name + " must be an array of strings");
        }
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("spec." + name + " must be an array of strings");
            }
        }
        return e.getAsJsonArray();
    }

    private static JsonObject object(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("spec." + name + " must be an object");
        }
        return e.getAsJsonObject();
    }
}
