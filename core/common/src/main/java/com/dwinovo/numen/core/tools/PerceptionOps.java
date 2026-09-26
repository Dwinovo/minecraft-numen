package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
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
 * 读身体、主人、世界与一格方块:{@code status self|owner|world} 与 {@code scan block} 的处理函数交到这里,
 * 命令的名字、说明与参数在 {@link com.dwinovo.numen.core.tools.perception.StatusCommands} 与
 * {@link com.dwinovo.numen.core.tools.perception.ScanCommands}。每个方法回一份 JSON,原样作回执。
 */
public final class PerceptionOps {

    public String getSelfStatus(NumenPlayer self) {
        JsonObject root = new JsonObject();
        root.addProperty("entity_id", self.getId());
        root.addProperty("name", self.getName().getString());
        root.addProperty("game_mode", self.gameMode.getGameModeForPlayer().getName());
        root.addProperty("hp", self.getHealth());
        root.addProperty("max_hp", self.getMaxHealth());
        root.addProperty("hunger", self.getFoodData().getFoodLevel());
        root.addProperty("saturation", self.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", self.getX());
        pos.addProperty("y", self.getY());
        pos.addProperty("z", self.getZ());
        root.add("position", pos);

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
        JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack s = self.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            if (s.getCount() > 1) o.addProperty("count", s.getCount());
            equipment.add(slot.getName(), o);
        }
        root.add("equipment", equipment);

        // 背包不在这里。它是「状态」不是「事件」——工具结果会沉进对话历史,而历史里的
        // 状态永远不会过期:十轮之后她读到那份快照,上面写的还是十轮前的东西,而且和这一轮
        // 挂在请求里的实时背包对不上。全量背包只有一个来源(runtime_state 的 <inventory>),
        // 那一份永远是现在。要精确到槽位就调 inspect_gui。
        var inv = self.getInventory();
        JsonObject slots = new JsonObject();
        int used = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) used++;
        }
        slots.addProperty("used", used);
        slots.addProperty("total", inv.getContainerSize());
        root.add("backpack_slots", slots);

        root.add("target", JsonNull.INSTANCE);
        root.addProperty("on_ground", self.onGround());
        root.addProperty("in_water", self.isInWater());
        // Remaining breath — the one stat whose absence let a body drown while its
        // mind calmly planned an 870-block trip (frozen-ocean death, 2026-07-15).
        root.addProperty("air", self.getAirSupply() + "/" + self.getMaxAirSupply() + " ticks");
        root.addProperty("in_lava", self.isInLava());
        // 身体状态片段:<worn>(穿戴位置,原版与模组同一份)打头,其后是插件从身体上读的片段。
        // 与挂进 runtime_state 的是同一个汇总,一段都没有就不出这个字段。
        String bodyState = com.dwinovo.numen.api.NumenPlugins.bodyStateFragments(self);
        if (!bodyState.isEmpty()) {
            root.addProperty("body_state", bodyState);
        }
        return root.toString();
    }

    @SuppressWarnings("deprecation")  // BlockBehaviour.isSolid() carries Mojang's
                                     // "deprecated for override" marker, not phased out.
    public String inspectBlock(int x,
int y,
int z,
                               NumenPlayer self) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = self.level().getBlockState(pos);

        JsonObject root = new JsonObject();
        root.addProperty("x", x);
        root.addProperty("y", y);
        root.addProperty("z", z);
        root.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
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
        double distSqr = self.distanceToSqr(center);
        root.addProperty("distance_to_me", Math.sqrt(distSqr));
        root.addProperty("in_reach", self.canInteractWithBlock(pos, 0.0));

        return root.toString();
    }

    /** Serialized value of one block-state property (e.g. "true", "north"). */
    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    public String getOwnerStatus(NumenPlayer self) {
        JsonObject root = new JsonObject();
        java.util.UUID ownerUuid = self.getOwnerUuid();
        if (ownerUuid == null) {
            root.addProperty("online", false);
            root.addProperty("message", "no owner (untamed)");
            return root.toString();
        }
        root.addProperty("owner_uuid", ownerUuid.toString());

        // Server-wide resolution: vanilla getOwner() is scoped to the PET's
        // level and would report a cross-dimension owner as "offline".
        Player player = self.resolveOwnerPlayer();
        if (player == null) {
            root.addProperty("online", false);
            root.addProperty("message", "owner offline");
            return root.toString();
        }

        root.addProperty("online", true);
        root.addProperty("name", player.getName().getString());
        root.addProperty("hp", player.getHealth());
        root.addProperty("max_hp", player.getMaxHealth());
        root.addProperty("hunger", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", player.getX());
        pos.addProperty("y", player.getY());
        pos.addProperty("z", player.getZ());
        root.add("position", pos);

        boolean sameDimension = self.level().dimension().equals(player.level().dimension());
        root.addProperty("same_dimension", sameDimension);
        root.addProperty("owner_dimension", player.level().dimension().location().toString());
        if (sameDimension) {
            root.addProperty("distance_to_me", self.distanceTo(player));
        } else {
            root.addProperty("note", "owner is in a different dimension — their "
                    + "position is in THAT dimension's coordinates, not yours");
        }
        root.addProperty("main_hand", itemKey(player.getMainHandItem()));
        root.addProperty("off_hand", itemKey(player.getOffhandItem()));

        return root.toString();
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public String getWorldInfo(NumenPlayer self) {
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

        return root.toString();
    }
}
