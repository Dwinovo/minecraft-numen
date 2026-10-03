package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptType;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static com.dwinovo.numen.agent.script.ScriptType.field;
import static com.dwinovo.numen.agent.script.ScriptType.optional;

/**
 * API 里共用的几种值:位置、方块、实体、掉落物、错误值。它们的样子只在这里定——查询返回的数据经这里的写法造({@link #pos}、
 * {@link #block}、{@link #entity}),参数经 {@link ArgType} 按同一种样子读,帮助与系统提示里的类声明也是这里的({@link #CLASSES})。
 *
 * <h2>一种位置</h2>
 * 位置只有一种写法,带键的表 {@code {x = 120, y = 64, z = -35}}(Pos),和 mineflayer 的 Vec3 一样是 x、y、z 三个字段。格子的三个数是
 * 整数;身体、实体的位置是小数。收一格的参数读到小数时按 Minecraft 自己的定义换成它所在的那一格({@code BlockPos.containing},向下
 * 取整),所以 {@code numen.status.self().pos}、一只实体的 {@code pos} 原样就能交给要一格的参数。
 *
 * <h2>对象原样往下传</h2>
 * 方块、实体、掉落物都带 {@code pos} 字段:要一格的参数收下带 {@code pos} 的表就是那一格({@code numen.work.dig(b)} 与
 * {@code numen.work.dig(b.pos)} 一样);要一只实体的参数收下带 {@code id} 的表就是那一只({@code numen.fight.attack(e)})。
 */
public final class Shapes {

    private Shapes() {}

    /** 一个位置。 */
    public static final ScriptType.Class POS = new ScriptType.Class("Pos",
            "A position. A cell's x, y, z are whole numbers; a body's or an entity's are decimals. Wherever a Pos is "
                    + "taken, anything with a pos field (a Block, an Entity, an Item) goes too, and decimals mean the "
                    + "cell they fall in (rounded down, as Minecraft does).",
            null, List.of(field("x", ScriptType.NUMBER, null), field("y", ScriptType.NUMBER, null),
                    field("z", ScriptType.NUMBER, null)));

    /** 一格方块。 */
    public static final ScriptType.Class BLOCK = new ScriptType.Class("Block", "One block in the world.", null,
            List.of(field("name", ScriptType.STRING, "Its id, minecraft:iron_ore."),
                    field("pos", POS.type(), "Its cell.")));

    /** 一只实体。 */
    public static final ScriptType.Class ENTITY = new ScriptType.Class("Entity", "An entity near you.", null,
            List.of(field("id", ScriptType.INTEGER, "Its runtime id, which numen.fight.attack, numen.use.entity and numen.move.follow "
                            + "take (or pass the whole Entity). It does not survive a restart."),
                    field("type", ScriptType.STRING, "Its type id, minecraft:zombie."),
                    field("pos", POS.type(), "Where it is (decimals)."),
                    optional("category", ScriptType.STRING, "hostile, passive, player or item."),
                    optional("name", ScriptType.STRING, "Its name, when it has one."),
                    optional("distance", ScriptType.NUMBER, "Blocks from you."),
                    optional("hp", ScriptType.NUMBER, "Health, for a living one."),
                    optional("max_hp", ScriptType.NUMBER, null),
                    optional("owner", ScriptType.STRING, "Whose it is, for a tamed one: you, your owner, or another "
                            + "player's name.")));

    /** 地上的一个掉落物。 */
    public static final ScriptType.Class ITEM = new ScriptType.Class("Item",
            "A dropped item lying on the ground: an Entity with what it is.", ENTITY.name(),
            List.of(field("item", ScriptType.STRING, "The item id, minecraft:raw_iron."),
                    field("count", ScriptType.INTEGER, "How many."),
                    field("pickup_delay", ScriptType.INTEGER, "Ticks before anyone can pick it up; 0 = now.")));

    /** 失败的调用抛出的错误值。 */
    public static final ScriptType.Class ERROR = new ScriptType.Class("Error",
            "What a failed call raises. local ok, err = pcall(numen.work.dig, b) catches it; tostring(err) reads it. "
                    + "raise(kind, message, hint) raises one of your own.",
            null, List.of(
                    field("kind", ScriptType.STRING, "bad_argument, no_function, not_found, out_of_reach, no_path, "
                            + "no_material, needs_consent, denied, interrupted, timeout or failed."),
                    field("message", ScriptType.STRING, "What went wrong."),
                    optional("hint", ScriptType.STRING, "A next line to run."),
                    optional("fn", ScriptType.STRING, "The function that failed, numen.work.dig."),
                    optional("data", new ScriptType.Simple("table"), "What the call knew when it failed (the "
                            + "nearest cell out of reach …).")));

    /** 帮助与系统提示里列出的共用类,按这个顺序。 */
    public static final List<ScriptType.Class> CLASSES = List.of(POS, BLOCK, ENTITY, ITEM, ERROR);

    /** 一格写成脚本里的值:带键的表,三个整数。 */
    public static java.util.Map<String, Object> value(BlockPos cell) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("x", (long) cell.getX());
        out.put("y", (long) cell.getY());
        out.put("z", (long) cell.getZ());
        return out;
    }

    /** 一格在回执与提示里写成的那一段程序:{@code {x = 120, y = 64, z = -35}}。 */
    public static String literal(BlockPos cell) {
        return com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.value(value(cell));
    }

    /** 一格:三个整数。 */
    public static JsonObject pos(BlockPos cell) {
        JsonObject o = new JsonObject();
        o.addProperty("x", cell.getX());
        o.addProperty("y", cell.getY());
        o.addProperty("z", cell.getZ());
        return o;
    }

    /**
     * 身体或实体的位置:小数,留两位(厘米级,读得清又不冗长)。往下舍,不四舍五入:原样交回收位置的参数时按所在的一格读
     * ({@code BlockPos.containing}),落在 -72.004 的掉落物入成 -72.00 就成了上面一格。
     */
    public static JsonObject pos(Vec3 at) {
        JsonObject o = new JsonObject();
        o.addProperty("x", truncate(at.x));
        o.addProperty("y", truncate(at.y));
        o.addProperty("z", truncate(at.z));
        return o;
    }

    private static double truncate(double v) {
        return Math.floor(v * 100.0) / 100.0;
    }

    /** 身体或实体的位置在回执里的写法:{@code -10537096.5,64,20.25},和 {@link #pos(Vec3)} 同一份数,不写成科学计数法。 */
    public static String coords(Vec3 at) {
        return plain(truncate(at.x)) + "," + plain(truncate(at.y)) + "," + plain(truncate(at.z));
    }

    private static String plain(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** 一格方块:{@code name} 与 {@code pos};用的一方可以再加字段。 */
    public static JsonObject block(BlockPos cell, BlockState state) {
        JsonObject o = new JsonObject();
        o.addProperty("name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        o.add("pos", pos(cell));
        return o;
    }

    /** 一只实体:{@code id}、{@code type}、{@code pos},有名字的加 {@code name};用的一方可以再加字段(距离、血量、掉落物的物品)。 */
    public static JsonObject entity(Entity entity) {
        JsonObject o = new JsonObject();
        o.addProperty("id", entity.getId());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        o.add("pos", pos(entity.position()));
        if (entity.hasCustomName() || entity instanceof net.minecraft.world.entity.player.Player) {
            o.addProperty("name", entity.getName().getString());
        }
        return o;
    }
}
