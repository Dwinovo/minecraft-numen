package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.core.scan.NearbyEntities;
import com.dwinovo.numen.core.tools.ScanOps;
import com.dwinovo.numen.core.tools.ToolParse;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Signals;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.sdk.BlockAt;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.CellOrEntity;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityInfo;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Flatten;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.ItemInfo;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.Positional;
import com.dwinovo.numen.sdk.Rest;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.Seen;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code numen.scan}:看她周围——脚下一圈的地形图、某几种方块在哪、附近有谁、一格方块是什么、一格方块里装着什么、看不看得见一处。
 * 六个函数都在服务端读世界,不动世界,不占身体,什么也不存;脚本拿到的是读到的值(方块、实体都带 Pos,原样能交给下一个函数)。
 */
public final class ScanApi {

    /** 找方块不写半径时搜多远:她"附近"的那一圈,一次搜索与一份结果都不大。 */
    private static final int DEFAULT_SEARCH_RADIUS = 16;
    /** 列实体不写半径时看多远。 */
    private static final int DEFAULT_ENTITY_RADIUS = 24;
    private static final double MIN_ENTITY_RADIUS = 1.0;
    private static final double MAX_ENTITY_RADIUS = 64.0;

    private ScanApi() {}

    public static void install(NumenApi numen) {
        numen.api("scan", "Look around you: the ground map, where blocks are, who is near, one block and what it "
                + "holds, whether you can see something.", ScanApi.class);
    }

    // ---- map ----

    /** 看多大一圈。 */
    public record MapArgs(@Doc("Half-width of the square view in blocks (" + LookAround.MIN_RADIUS + "-"
            + LookAround.MAX_RADIUS + ").") @Omitted("use " + LookAround.DEFAULT_RADIUS) Optional<Integer> radius) {}

    /** 脚下一圈的地形图。 */
    @Doc("A top-down map of the ground around you.")
    public record GroundMap(@Doc("The map, one string per row, north first; cells are separated by spaces.")
                            List<String> rows,
                            @Doc("The cell you stand in: the @.") BlockPos center,
                            String facing,
                            String legend) {}

    @Fn("A top-down map of the ground around you: where you can walk, step, drop, swim.")
    @Example("for _, row in ipairs(numen.scan.map().rows) do print(row) end")
    @Example("numen.scan.map({radius = 12})")
    @Note("Instant and read-only. @ is you, North is up, East is right, one cell is one block; each cell says how you "
            + "could move onto it and the legend comes with it. To route, trace it cell by cell: . ^ , are walkable; "
            + "# ~ ! v x block or endanger you.")
    @Note("One map instead of many single-block looks; for things further out use `numen.scan.blocks` or "
            + "`numen.scan.entities`.")
    @SeeAlso({"numen.scan.blocks", "numen.scan.entities", "numen.scan.block"})
    public static GroundMap map(ServerCall call, MapArgs args) {
        return LookAround.render(call.her(), args.radius().orElse(LookAround.DEFAULT_RADIUS));
    }

    // ---- blocks ----

    /** 找哪几种方块。 */
    public record Blocks(@Doc("The block ids or #tags to search for; name every variant.") @Rest List<String> blockIds,
                         @Doc("Spherical search radius in blocks (max " + BlockScan.MAX_RADIUS + ").")
                         @Omitted("search " + DEFAULT_SEARCH_RADIUS + " blocks around you") Optional<Integer> radius) {}

    @Fn("Find blocks of the given types near you, as clusters of touching blocks, nearest first.")
    @Example("local found = numen.scan.blocks(\"iron_ore\", \"deepslate_iron_ore\")\nprint(#found, found[1].count, "
            + "found[1].nearest.pos.x)")
    @Example("numen.scan.blocks({\"iron_ore\", \"deepslate_iron_ore\"}, {radius = 32})")
    @Example("numen.scan.blocks(\"#minecraft:beds\")")
    @Note("Read-only; it returns when the search is done. Name every variant you want.")
    @Note("A cluster is matching blocks that touch (diagonals count): every block of it nearest first (a Block: name "
            + "and pos), the nearest one, and how many. Hand a cluster on as it is: `numen.move.to` with arrive \"dig\" "
            + "walks within reach of it, `numen.work.dig` digs what still stands within reach, `numen.work.mine` walks "
            + "and digs until it is gone.")
    @Note("Nothing is kept: to use what it found again, keep the result in the program, or scan again.")
    @Note("Only loaded terrain is read: anything further out is UNKNOWN, not empty; an empty result there is not proof "
            + "that none is there — walk that way and scan again. When it could not read all of the radius, the "
            + "program's receipt says which part it missed.")
    @SeeAlso({"numen.work.dig", "numen.scan.block"})
    public static Pending<List<ScanOps.Cluster>> blocks(ServerCall call, Blocks args) {
        Set<Block> targets = ToolParse.parseBlocks(args.blockIds());
        if (targets.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "none of " + args.blockIds() + " is a block id or a #tag of "
                    + "blocks", null);
        }
        return ScanOps.scan(call.her(), args.radius().orElse(DEFAULT_SEARCH_RADIUS), targets);
    }

    // ---- entities ----

    /** 哪一类实体。 */
    public enum Kind { HOSTILE, PASSIVE, PLAYER, ITEM, ALL }

    /** 列哪些、看多远。 */
    public record Entities(@Doc("Which entities: hostile = monsters, passive = animals and other mobs, player = "
            + "players, item = items lying on the ground, all = everything.") @Omitted("list all of them")
                           @Positional Optional<Kind> typeFilter,
                           @Doc("Search radius in blocks (1-64).")
                           @Omitted("look " + DEFAULT_ENTITY_RADIUS + " blocks around you") Optional<Double> radius) {}

    /**
     * 半径内的实体由近及远。倒下、正在消失的(死亡动画那二十来刻)不列:它已经打不着、用不了,列出来脚本会对着一具尸体再打一场。
     */
    @Fn("List the entities near you, nearest first, with the ids other functions take.")
    @Example("numen.scan.entities(\"hostile\")")
    @Example("numen.scan.entities({radius = 12})")
    @Example("numen.scan.entities(\"item\", {radius = 8})")
    @Note("Going through them: `for _, e in ipairs(numen.scan.entities(\"item\", {radius = 8})) do numen.move.to(e.pos) "
            + "end`.")
    @Note("Instant and read-only. All of them, nearest first: each is an Entity (id, type, category, pos, distance, "
            + "hp); a dropped item is an Item (also item, count and pickup_delay, ticks before anyone can pick it up); "
            + "a tamed one has owner: you, your owner, or the other player's name.")
    @Note("Hand one on as it is: `local e = numen.scan.entities(\"hostile\")[1]; numen.fight.attack(e)`, and the same "
            + "with numen.use.entity(e) or numen.move.to(e.pos). The ids are runtime ids and do not survive a "
            + "restart.")
    @SeeAlso({"numen.scan.map", "numen.fight.attack", "numen.use.entity"})
    public static List<Seen> entities(ServerCall call, Entities args) {
        NumenPlayer self = call.her();
        double radius = Math.clamp(args.radius().orElse((double) DEFAULT_ENTITY_RADIUS), MIN_ENTITY_RADIUS,
                MAX_ENTITY_RADIUS);
        Kind filter = args.typeFilter().orElse(Kind.ALL);
        List<Entity> raw = NearbyEntities.within(self, radius, Entity.class, Entity::isAlive);
        raw = new ArrayList<>(raw);
        raw.sort(Comparator.comparingDouble(self::distanceTo));
        List<Seen> out = new ArrayList<>();
        for (Entity e : raw) {
            Kind kind = kindOf(e);
            if (filter != Kind.ALL && filter != kind) {
                continue;
            }
            Optional<Double> hp = e instanceof LivingEntity le ? Optional.of((double) le.getHealth()) : Optional.empty();
            Optional<Double> maxHp = e instanceof LivingEntity le ? Optional.of((double) le.getMaxHealth())
                    : Optional.empty();
            EntityInfo info = EntityInfo.of(e).seen(Optional.of(kind.name().toLowerCase(java.util.Locale.ROOT)),
                    Optional.of(Math.round(self.distanceTo(e) * 10.0) / 10.0), hp, maxHp,
                    Optional.ofNullable(ownerOf(e, self)));
            if (e instanceof ItemEntity item) {
                ItemStack stack = item.getItem();
                // 刚掉下来的东西原版要过一小段冷却才让人捡:还剩几刻,捡的模块据此判断走上去没捡到是不是在等冷却
                out.add(new ItemInfo(info, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
                        ((com.dwinovo.numen.core.mixin.ItemEntityAccessor) item).numen$getPickupDelay()));
            } else {
                out.add(info);
            }
        }
        return out;
    }

    private static Kind kindOf(Entity e) {
        if (e instanceof Player) {
            return Kind.PLAYER;
        }
        if (e instanceof Monster) {
            return Kind.HOSTILE;
        }
        return e instanceof ItemEntity ? Kind.ITEM : Kind.PASSIVE;
    }

    /**
     * 这只是谁的:{@code you}(她自己驯服的)、{@code your owner}(她主人的)、别的玩家的名字,名字查不到就是 UUID;没有主人为 null。
     * 主人只从权限层的同一处读({@link Signals#ownerOf}),{@code self_owned} 与这里说的是同一回事。
     */
    private static String ownerOf(Entity e, NumenPlayer self) {
        UUID owner = Signals.ownerOf(e);
        if (owner == null) {
            return null;
        }
        if (owner.equals(self.getUUID())) {
            return "you";
        }
        if (self.isOwnedByPlayer(owner)) {
            return "your owner";
        }
        String name = NumenPlayer.playerName(self.getServer(), owner);
        return name.isEmpty() ? owner.toString() : name;
    }

    // ---- block ----

    /** 哪一格。 */
    public record Cell(@Doc("The block's cell.") BlockPos cell) {}

    /** 一格方块的详情。 */
    @Doc("One block in full: what it is and what digging it takes.")
    public record BlockInfo(@Flatten BlockAt block,
                            @Doc("Its state, facing = \"north\" ...") Optional<Map<String, String>> properties,
                            boolean isAir,
                            boolean isSolid,
                            boolean isLiquid,
                            double hardness,
                            boolean unbreakable,
                            boolean needsCorrectTool,
                            boolean currentHandCorrectTool,
                            Optional<Integer> estimatedMiningTicks,
                            double distance,
                            boolean inReach) {}

    @Fn("One block: its id and state, hardness, whether your held tool is right, dig time, whether it is in reach.")
    @Example("numen.scan.block({x = 120, y = 64, z = -35})")
    @Note("Instant and read-only, from any distance: the block id and its state properties (an end_portal_frame's "
            + "has_eye), hardness, whether the tool in hand is right, an estimated dig time, and whether it is within "
            + "your reach.")
    @SeeAlso({"numen.scan.container", "numen.scan.blocks", "numen.scan.sight"})
    @SuppressWarnings("deprecation")  // BlockBehaviour.isSolid() 带的是 Mojang 的"别覆写"标记,不是要淘汰
    public static BlockInfo block(ServerCall call, Cell args) {
        NumenPlayer self = call.her();
        BlockPos pos = args.cell();
        BlockState state = self.level().getBlockState(pos);
        // 方块状态(末地门框的 has_eye/facing、楼梯朝向……):没有就不出这一项
        Optional<Map<String, String>> properties = Optional.empty();
        if (!state.getProperties().isEmpty()) {
            Map<String, String> props = new LinkedHashMap<>();
            for (Property<?> p : state.getProperties()) {
                props.put(p.getName(), propValue(state, p));
            }
            properties = Optional.of(props);
        }
        float hardness = state.getDestroySpeed(self.level(), pos);
        boolean needsTool = state.requiresCorrectToolForDrops();
        ItemStack hand = self.getMainHandItem();
        boolean handIsRightTool = hand.isCorrectToolForDrops(state);
        Optional<Integer> ticks = Optional.empty();
        if (!state.isAir() && hardness >= 0) {
            float toolSpeed = hand.getDestroySpeed(state);
            if (toolSpeed <= 0.0F) {
                toolSpeed = 1.0F;
            }
            // 原版的规矩:不要求对口工具的方块总按快的那个除数算
            float divisor = !needsTool || handIsRightTool ? 30.0F : 100.0F;
            ticks = Optional.of(hardness == 0.0F ? 1 : Math.max(1, (int) Math.ceil(hardness * divisor / toolSpeed)));
        }
        double distance = Math.sqrt(self.distanceToSqr(Vec3.atCenterOf(pos)));
        return new BlockInfo(BlockAt.of(pos, state), properties, state.isAir(), state.isSolid(),
                !state.getFluidState().isEmpty(), hardness, hardness < 0, needsTool, handIsRightTool, ticks,
                Math.round(distance * 10.0) / 10.0, self.canInteractWithBlock(pos, 0.0));
    }

    /** 一个方块状态属性写成的值("true"、"north")。 */
    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    // ---- container ----

    /** 一格方块里装着什么。 */
    public record Storage(BlockAt block,
                          @Doc("What it holds, one line per slot, tank or battery; empty when it exposes no storage "
                                  + "(not a machine, tank or battery, or it keeps its state elsewhere).")
                          List<String> storage) {}

    @Fn("What a block holds — items, fluid, energy — read without opening it.")
    @Example("numen.scan.container({x = 120, y = 64, z = -35})")
    @Note("Instant and read-only, from any distance; nothing is opened or moved.")
    @Note("Works on chests, furnaces and most modded machines, tanks and batteries. Storage-network terminals (AE2/RS) "
            + "show only their local buffer, not the whole network.")
    @Note("Use it instead of opening a machine's GUI when you only need its contents or fill levels; a block with a "
            + "GUI but no storage here is opened with numen.use.block and read with numen.gui.view.")
    @SeeAlso("numen.scan.block")
    public static Storage container(ServerCall call, Cell args) {
        NumenPlayer self = call.her();
        BlockPos pos = args.cell();
        BlockState state = self.level().getBlockState(pos);
        if (state.isAir()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "the block at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + " is air — nothing to read", null);
        }
        return new Storage(BlockAt.of(pos, state), Services.CAPS.describe(self.level(), pos));
    }

    // ---- sight ----

    /** 看哪儿。 */
    public record Look(@Doc("What to look at: a cell (a Pos, a Block) or an entity.") CellOrEntity target) {}

    /** 看不看得见。 */
    public record Sight(@Doc("A line from your eyes reaches it.") boolean visible,
                        @Doc("Blocks from your eyes.") double distance,
                        @Doc("When you cannot see it: the first block in the way, one to dig out before grass and the "
                                + "like.") Optional<BlockAt> blockedBy) {}

    /**
     * 她看不看得见:一格是从她的眼睛朝那一格冲着她的各面打视线({@link com.dwinovo.numen.pathing.world.Sight},看不看得见一格只在那里判),
     * 有一面碰上它、路上没隔着东西就看得见;空着的一格(没有轮廓)是视线到它中心不隔东西;一只实体照原版的视线判(看它的眼睛)。看不见时说
     * 挡着的第一格:先说要挖开的硬遮挡,只隔着草这类软遮挡时是那一格。
     */
    @Fn("Whether you can see a cell or an entity from where you stand, and what is in the way when you cannot.")
    @Example("local s = numen.scan.sight({x = 120, y = 64, z = -35})\nprint(s.visible, s.blocked_by)")
    @Example("numen.scan.sight(184)")
    @Note("Instant and read-only. A cell is seen when a line from your eyes reaches one of its faces turned to you (an "
            + "empty cell: its middle) with nothing in between; an entity, when you see its eyes.")
    @SeeAlso({"numen.scan.block", "numen.scan.entities"})
    public static Sight sight(ServerCall call, Look args) {
        NumenPlayer her = call.her();
        ServerLevel level = her.serverLevel();
        Vec3 eye = her.getEyePosition();
        CellOrEntity target = args.target();
        boolean visible;
        com.dwinovo.numen.pathing.world.Sight.Trace seen;
        Vec3 to;
        if (target.entity() != null) {
            Entity entity = target.entity().in(level);
            if (entity == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "there is no entity " + target.entity() + " near you",
                        Call.of("numen.scan.entities"));
            }
            to = entity.getEyePosition();
            visible = her.hasLineOfSight(entity);
            seen = com.dwinovo.numen.pathing.world.Sight.trace(level, eye, to, null);
        } else {
            BlockPos cell = target.cell();
            boolean solid = com.dwinovo.numen.pathing.world.Sight.clickable(level, cell);
            to = Vec3.atCenterOf(cell);
            seen = null;
            visible = false;
            for (Vec3 point : solid ? com.dwinovo.numen.pathing.world.Sight.faces(level, eye, cell) : List.of(to)) {
                com.dwinovo.numen.pathing.world.Sight.Trace trace =
                        com.dwinovo.numen.pathing.world.Sight.trace(level, eye, point, cell);
                boolean clear = solid ? trace.clear(null) : trace.hard().isEmpty() && trace.soft().isEmpty();
                if (clear || seen == null || trace.hard().size() < seen.hard().size()) {
                    seen = trace;
                    visible = clear;
                }
                if (clear) {
                    break;
                }
            }
        }
        BlockPos blocker = visible || seen == null ? null : !seen.hard().isEmpty() ? seen.hard().get(0)
                : seen.soft().isEmpty() ? null : seen.soft().get(0);
        return new Sight(visible, Math.round(eye.distanceTo(to) * 10.0) / 10.0,
                blocker == null ? Optional.empty() : Optional.of(BlockAt.of(blocker, level.getBlockState(blocker))));
    }
}
