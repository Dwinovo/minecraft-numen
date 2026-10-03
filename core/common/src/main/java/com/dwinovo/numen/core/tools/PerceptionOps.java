package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.cli.CellOrEntity;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.world.Sight;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

/**
 * 读身体、主人、世界、一格方块与视线:{@code status self|owner|world}、{@code scan block} 与 {@code scan sight} 的处理函数交到这里,
 * 命令的名字、说明与参数在 {@link com.dwinovo.numen.core.tools.perception.StatusCommands} 与
 * {@link com.dwinovo.numen.core.tools.perception.ScanCommands}。每个方法回一份结果:数据是那份读数(位置是 {@link Shapes} 的 Pos),
 * 那句话是一行摘要。
 */
public final class PerceptionOps {

    public TaskResult getSelfStatus(NumenPlayer self) {
        JsonObject root = new JsonObject();
        root.addProperty("id", self.getId());
        root.addProperty("name", self.getName().getString());
        root.addProperty("game_mode", self.gameMode.getGameModeForPlayer().getName());
        root.addProperty("hp", self.getHealth());
        root.addProperty("max_hp", self.getMaxHealth());
        root.addProperty("hunger", self.getFoodData().getFoodLevel());
        root.addProperty("saturation", self.getFoodData().getSaturationLevel());
        root.add("pos", Shapes.pos(self.position()));
        root.addProperty("dimension", self.level().dimension().location().toString());
        root.addProperty("biome", self.level().getBiome(self.blockPosition())
                .unwrapKey().map(k -> k.location().toString()).orElse("unknown"));

        JsonArray structures = new JsonArray();
        if (self.level() instanceof ServerLevel sl) {
            Registry<Structure> reg = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure s : sl.structureManager().getAllStructuresAt(self.blockPosition()).keySet()) {
                ResourceLocation key = reg.getKey(s);
                if (key != null) structures.add(key.toString());
            }
        }
        root.add("structures", structures);

        // 只报两只手:身上穿戴的归 body_state 里的 <worn> 一处管,原版的甲和模组的饰品同一份
        JsonObject hands = new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack s = self.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            o.addProperty("count", s.getCount());
            hands.add(slot.getName(), o);
        }
        root.add("hands", hands);

        // 背包不在这里。它是「状态」不是「事件」——工具结果会沉进对话历史,而历史里的
        // 状态永远不会过期:十轮之后她读到那份快照,上面写的还是十轮前的东西,而且和这一轮
        // 挂在请求里的实时背包对不上。全量背包只有一个来源(runtime_state 的 <inventory>),
        // 那一份永远是现在。要精确到槽位就用 gui view。
        var inv = self.getInventory();
        JsonObject slots = new JsonObject();
        int used = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) used++;
        }
        slots.addProperty("used", used);
        slots.addProperty("total", inv.getContainerSize());
        root.add("backpack_slots", slots);

        root.addProperty("on_ground", self.onGround());
        root.addProperty("in_water", self.isInWater());
        // Remaining breath — the one stat whose absence let a body drown while its
        // mind calmly planned an 870-block trip (frozen-ocean death, 2026-07-15).
        root.addProperty("air", self.getAirSupply());
        root.addProperty("max_air", self.getMaxAirSupply());
        root.addProperty("in_lava", self.isInLava());
        // 身体状态片段:<worn>(穿戴位置,原版与模组同一份)打头,其后是插件从身体上读的片段。
        // 与挂进 runtime_state 的是同一个汇总,一段都没有就不出这个字段。
        String bodyState = com.dwinovo.numen.api.NumenPlugins.bodyStateFragments(self);
        if (!bodyState.isEmpty()) {
            root.addProperty("body_state", bodyState);
        }
        return TaskResult.ok(self.getName().getString() + " at " + at(self.position()) + ", " + Math.round(
                self.getHealth()) + "/" + Math.round(self.getMaxHealth()) + " hp, hunger "
                + self.getFoodData().getFoodLevel() + "/20.", root);
    }

    /** 一个位置在那句话里的写法:{@code 12.5,64,-3.2}。 */
    private static String at(Vec3 v) {
        return Shapes.coords(v);
    }

    @SuppressWarnings("deprecation")  // BlockBehaviour.isSolid() carries Mojang's
                                     // "deprecated for override" marker, not phased out.
    public TaskResult inspectBlock(BlockPos pos, NumenPlayer self) {
        BlockState state = self.level().getBlockState(pos);

        JsonObject root = Shapes.block(pos, state);
        // Block-state properties (e.g. end_portal_frame's has_eye/facing, so the
        // model can tell which of the 12 frames still need an ender_eye; stairs
        // facing; etc.). Omitted when the block has no properties.
        if (!state.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Property<?> p : state.getProperties()) {
                props.addProperty(p.getName(), propValue(state, p));
            }
            root.add("properties", props);
        }
        root.addProperty("is_air", state.isAir());
        root.addProperty("is_solid", state.isSolid());
        root.addProperty("is_liquid", !state.getFluidState().isEmpty());

        float hardness = state.getDestroySpeed(self.level(), pos);
        root.addProperty("hardness", hardness);
        root.addProperty("unbreakable", hardness < 0);

        boolean needsTool = state.requiresCorrectToolForDrops();
        root.addProperty("needs_correct_tool", needsTool);
        ItemStack hand = self.getMainHandItem();
        boolean handIsRightTool = hand.isCorrectToolForDrops(state);
        root.addProperty("current_hand_correct_tool", handIsRightTool);

        if (!state.isAir() && hardness >= 0) {
            float toolSpeed = hand.getDestroySpeed(state);
            if (toolSpeed <= 0.0F) toolSpeed = 1.0F;
            // Vanilla rule: a block that doesn't require the correct tool
            // always uses the fast divisor.
            boolean fast = !needsTool || handIsRightTool;
            float divisor = fast ? 30.0F : 100.0F;
            int ticks = hardness == 0.0F
                    ? 1
                    : Math.max(1, (int) Math.ceil(hardness * divisor / toolSpeed));
            root.addProperty("estimated_mining_ticks", ticks);
        }

        Vec3 center = Vec3.atCenterOf(pos);
        double distance = Math.sqrt(self.distanceToSqr(center));
        root.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        boolean inReach = self.canInteractWithBlock(pos, 0.0);
        root.addProperty("in_reach", inReach);

        return TaskResult.ok(root.get("name").getAsString() + " at " + pos.getX() + "," + pos.getY() + ","
                + pos.getZ() + (inReach ? ", within reach." : ", out of reach."), root);
    }

    /** Serialized value of one block-state property (e.g. "true", "north"). */
    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    public TaskResult getOwnerStatus(NumenPlayer self) {
        JsonObject root = new JsonObject();
        java.util.UUID ownerUuid = self.getOwnerUuid();
        if (ownerUuid == null) {
            root.addProperty("online", false);
            return TaskResult.ok("You have no owner (untamed).", root);
        }

        // Server-wide resolution: vanilla getOwner() is scoped to the PET's
        // level and would report a cross-dimension owner as "offline".
        Player player = self.resolveOwnerPlayer();
        if (player == null) {
            root.addProperty("online", false);
            return TaskResult.ok("Your owner is offline.", root);
        }

        root.addProperty("online", true);
        root.addProperty("id", player.getId());
        root.addProperty("name", player.getName().getString());
        root.addProperty("hp", player.getHealth());
        root.addProperty("max_hp", player.getMaxHealth());
        root.addProperty("hunger", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());
        root.add("pos", Shapes.pos(player.position()));

        boolean sameDimension = self.level().dimension().equals(player.level().dimension());
        root.addProperty("same_dimension", sameDimension);
        root.addProperty("dimension", player.level().dimension().location().toString());
        String where;
        if (sameDimension) {
            double distance = Math.round(self.distanceTo(player) * 10.0) / 10.0;
            root.addProperty("distance", distance);
            where = "at " + at(player.position()) + ", " + distance + " blocks from you";
        } else {
            where = "in " + player.level().dimension().location() + " — their pos is in THAT dimension's "
                    + "coordinates, not yours";
        }
        root.addProperty("main_hand", itemKey(player.getMainHandItem()));
        root.addProperty("off_hand", itemKey(player.getOffhandItem()));

        return TaskResult.ok(player.getName().getString() + " is online " + where + ", " + Math.round(
                player.getHealth()) + "/" + Math.round(player.getMaxHealth()) + " hp.", root);
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public TaskResult getWorldInfo(NumenPlayer self) {
        var level = self.level();

        JsonObject root = new JsonObject();
        root.addProperty("dimension", level.dimension().location().toString());
        root.addProperty("game_time", level.getLevelData().getGameTime());
        root.addProperty("is_bright_outside", level.isDay());
        root.addProperty("is_dark_outside", level.isNight());

        String weather;
        if (level.isThundering()) weather = "thunder";
        else if (level.isRaining()) weather = "rain";
        else weather = "clear";
        root.addProperty("weather", weather);

        return TaskResult.ok(level.dimension().location() + ", " + (level.isDay() ? "bright" : "dark") + " outside, "
                + weather + ".", root);
    }

    /**
     * 她看不看得见:一格是从她的眼睛朝那一格冲着她的各面打视线({@link Sight},看不看得见一格只在那里判),有一面碰上它、路上
     * 没隔着东西就看得见;空着的一格(没有轮廓)是视线到它中心不隔东西;一只实体照原版的视线判(看它的眼睛)。看不见时说挡着的
     * 第一格:先说要挖开的硬遮挡,只隔着草这类软遮挡时是那一格。只读。
     */
    public TaskResult sight(NumenPlayer her, CellOrEntity target) {
        ServerLevel level = her.serverLevel();
        Vec3 eye = her.getEyePosition();
        boolean visible;
        Sight.Trace seen;
        Vec3 to;
        String what;
        if (target.entity() != null) {
            net.minecraft.world.entity.Entity entity = target.entity().in(level);
            if (entity == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "there is no entity " + target.entity().written() + " near you",
                        "numen.scan.entities()");
            }
            to = entity.getEyePosition();
            visible = her.hasLineOfSight(entity);
            seen = Sight.trace(level, eye, to, null);
            what = entity.getName().getString();
        } else {
            BlockPos cell = target.cell();
            boolean solid = Sight.clickable(level, cell);
            to = Vec3.atCenterOf(cell);
            seen = null;
            visible = false;
            for (Vec3 point : solid ? Sight.faces(level, eye, cell) : java.util.List.of(to)) {
                Sight.Trace trace = Sight.trace(level, eye, point, cell);
                boolean clear = solid ? trace.clear(null) : trace.hard().isEmpty() && trace.soft().isEmpty();
                if (clear || seen == null || trace.hard().size() < seen.hard().size()) {
                    seen = trace;
                    visible = clear;
                }
                if (clear) {
                    break;
                }
            }
            what = BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock()).getPath() + " at "
                    + cell.getX() + "," + cell.getY() + "," + cell.getZ();
        }
        JsonObject data = new JsonObject();
        data.addProperty("visible", visible);
        data.addProperty("distance", Math.round(eye.distanceTo(to) * 10.0) / 10.0);
        BlockPos blocker = visible || seen == null ? null : !seen.hard().isEmpty() ? seen.hard().get(0)
                : seen.soft().isEmpty() ? null : seen.soft().get(0);
        if (blocker != null) {
            data.add("blocked_by", Shapes.block(blocker, level.getBlockState(blocker)));
        }
        String said = visible ? "You can see " + what + "."
                : "You cannot see " + what + (blocker == null ? "." : ": " + BuiltInRegistries.BLOCK.getKey(
                        level.getBlockState(blocker).getBlock()).getPath() + " at " + blocker.getX() + ","
                        + blocker.getY() + "," + blocker.getZ() + " is in the way.");
        return TaskResult.ok(said, data);
    }
}
