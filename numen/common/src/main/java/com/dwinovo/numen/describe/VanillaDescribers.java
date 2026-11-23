package com.dwinovo.numen.describe;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.platform.services.BlockStorageReading;
import com.dwinovo.numen.tools.Signs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原版里"这是什么"的回答者,一个只说一样。方块:告示牌上的字、一格方块里存着的物品流体能量、熔炉烧到哪了;实体:村民的职业与等级、
 * 驯没驯服、身上穿的拿的。经插件的门登记({@link #install}),和模组的回答者同一个写法;多认一样就是在这里多写一个、列进
 * {@link #install}。每个回答者说的是几个字段,归在它登记的名字下。方块、实体本身的事(id、状态、硬度、位置、血量、主人)不在这里,
 * 那是 {@code numen.scan} 自己读的。
 */
public final class VanillaDescribers {

    private VanillaDescribers() {}

    public static void install(NumenApi numen) {
        numen.answer(Questions.BLOCK, "sign", VanillaDescribers::sign);
        numen.answer(Questions.BLOCK, "storage", VanillaDescribers::storage);
        numen.answer(Questions.BLOCK, "furnace", VanillaDescribers::furnace);
        numen.answer(Questions.ENTITY, "villager", VanillaDescribers::villager);
        numen.answer(Questions.ENTITY, "tame", VanillaDescribers::tame);
        numen.answer(Questions.ENTITY, "equipment", VanillaDescribers::equipment);
    }

    // ---- 方块 ----

    /** 告示牌:两面的字,涂没涂蜡。 */
    private static Map<String, Object> sign(NumenPlayer her, BlockPos pos) {
        return her.level().getBlockEntity(pos) instanceof SignBlockEntity sign ? signFields(Signs.read(sign)) : null;
    }

    /** 一块告示牌的字:每面四行都在(空行是 {@code ""}),她要改哪一行数得出来。 */
    static Map<String, Object> signFields(Signs.Text text) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("front", text.front());
        out.put("back", text.back());
        out.put("waxed", text.waxed());
        return out;
    }

    /**
     * 一格方块里存着什么:经加载器的标准存储读,不开界面(箱子、熔炉、大多数模组的机器储罐电池)。不露出标准存储的方块没有这一条;
     * 存储网络的终端只露出它自己那一小块。
     */
    private static Map<String, Object> storage(NumenPlayer her, BlockPos pos) {
        return storageFields(Services.caps().read(her.level(), pos));
    }

    /**
     * 一份存储读数写成的字段,读数里没有的那一类不写:
     * <ul>
     *   <li>{@code inventories}:每个物品存储一项,{@code sides}(从哪几面露出)、{@code slots}(一共几格)、{@code items}(有东西的格:
     *       {@code slot} 从 0 起的格号、{@code item}、{@code count};空格不列);</li>
     *   <li>{@code fluids}:每个液体存储一项,{@code sides} 与 {@code tanks}(每槽 {@code amount_mb}、{@code capacity_mb},有液体时带
     *       {@code fluid});</li>
     *   <li>{@code energy}:{@code stored_fe}、{@code capacity_fe}、{@code accepts}、{@code provides}。</li>
     * </ul>
     * 同一份存储从几面露出只列一次(读数已经并好);一格方块从不同的面露出不同的几格时(熔炉)是几项,格子会重复出现在几项里。
     */
    static Map<String, Object> storageFields(BlockStorageReading reading) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!reading.items().isEmpty()) {
            List<Object> inventories = new ArrayList<>();
            for (BlockStorageReading.Exposed<BlockStorageReading.ItemInventory> exposed : reading.items()) {
                List<BlockStorageReading.ItemSlot> slots = exposed.storage().slots();
                List<Object> items = new ArrayList<>();
                for (int s = 0; s < slots.size(); s++) {
                    BlockStorageReading.ItemSlot slot = slots.get(s);
                    if (slot.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("slot", s);
                    item.put("item", slot.id());
                    item.put("count", slot.count());
                    items.add(item);
                }
                Map<String, Object> inventory = new LinkedHashMap<>();
                inventory.put("sides", exposed.sides());
                inventory.put("slots", slots.size());
                inventory.put("items", items);
                inventories.add(inventory);
            }
            out.put("inventories", inventories);
        }
        if (!reading.fluids().isEmpty()) {
            List<Object> fluids = new ArrayList<>();
            for (BlockStorageReading.Exposed<BlockStorageReading.FluidTanks> exposed : reading.fluids()) {
                List<Object> tanks = new ArrayList<>();
                for (BlockStorageReading.FluidTank tank : exposed.storage().tanks()) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    if (tank.id() != null) {
                        one.put("fluid", tank.id());
                    }
                    one.put("amount_mb", tank.amountMb());
                    one.put("capacity_mb", tank.capacityMb());
                    tanks.add(one);
                }
                Map<String, Object> storage = new LinkedHashMap<>();
                storage.put("sides", exposed.sides());
                storage.put("tanks", tanks);
                fluids.add(storage);
            }
            out.put("fluids", fluids);
        }
        BlockStorageReading.Energy energy = reading.energy();
        if (energy != null) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("stored_fe", energy.stored());
            one.put("capacity_fe", energy.max());
            one.put("accepts", energy.accepts());
            one.put("provides", energy.provides());
            out.put("energy", one);
        }
        return out;
    }

    /** 一秒几刻。 */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * 熔炉、高炉、烟熏炉:燃料还烧多久、正在烧的那一件烧到哪了。这三个数方块实体不对外给,读它自己存盘的那份数据
     * ({@code BurnTime}、{@code CookTime}、{@code CookTimeTotal},单位都是刻)。
     */
    private static Map<String, Object> furnace(NumenPlayer her, BlockPos pos) {
        if (!(her.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return null;
        }
        CompoundTag saved = furnace.saveWithoutMetadata(her.level().registryAccess());
        return furnaceFields(saved.getInt("BurnTime"), saved.getInt("CookTime"), saved.getInt("CookTimeTotal"));
    }

    /**
     * 一座炉的进度写成的字段,不成立的不写:{@code fuel_seconds} 是这份燃料还烧几秒(火着着才有);{@code cook_percent} 是正在烧的那一件
     * 烧了百分之几(有东西在烧才有);{@code cook_seconds_left} 是它还要几秒(火着着才有:没有火时原版让进度往回退,"还要几秒"不成立)。
     * 秒数往上取整。空着又没火的炉一个字段也没有。
     *
     * @param burnTicks  这份燃料还烧几刻
     * @param cookTicks  正在烧的那一件已经烧了几刻
     * @param totalTicks 烧好它一共要几刻
     */
    static Map<String, Object> furnaceFields(int burnTicks, int cookTicks, int totalTicks) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (burnTicks > 0) {
            out.put("fuel_seconds", seconds(burnTicks));
        }
        if (cookTicks > 0 && totalTicks > 0) {
            out.put("cook_percent", Math.min(100, cookTicks * 100 / totalTicks));
            if (burnTicks > 0) {
                out.put("cook_seconds_left", seconds(Math.max(0, totalTicks - cookTicks)));
            }
        }
        return out;
    }

    private static int seconds(int ticks) {
        return (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    // ---- 实体 ----

    /** 带着职业数据的(村民、僵尸村民):职业的 id 与等级。 */
    private static Map<String, Object> villager(NumenPlayer her, Entity entity) {
        if (!(entity instanceof VillagerDataHolder holder)) {
            return null;
        }
        VillagerData data = holder.getVillagerData();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profession", BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()).toString());
        out.put("level", data.getLevel());
        return out;
    }

    /**
     * 能驯服的动物:驯没驯服;原版有"坐下"这道命令的(狼、猫、鹦鹉)带上是不是被叫坐下了,马这一类带上备没备鞍(此刻备着,或原版说
     * 此刻能备:没驯服的、幼年的、羊驼都不能)。
     */
    private static Map<String, Object> tame(NumenPlayer her, Entity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (entity instanceof TamableAnimal pet) {
            out.put("tamed", pet.isTame());
            out.put("sitting", pet.isOrderedToSit());
        } else if (entity instanceof AbstractHorse horse) {
            out.put("tamed", horse.isTamed());
            if (horse.isSaddled() || horse.isSaddleable()) {
                out.put("saddled", horse.isSaddled());
            }
        }
        return out;
    }

    /** 手里拿的、身上穿的那六个位置,按这个先后;位置的名字用原版的叫法。 */
    private static final List<EquipmentSlot> EQUIPMENT = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /** 活物手里拿的、身上穿的:每个不空的位置一项({@code item}、{@code count}),空着的位置不列,六个都空就没有这一条。 */
    private static Map<String, Object> equipment(NumenPlayer her, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (EquipmentSlot slot : EQUIPMENT) {
            ItemStack stack = living.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Map<String, Object> held = new LinkedHashMap<>();
            held.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            held.put("count", stack.getCount());
            out.put(slot.getName(), held);
        }
        return out;
    }
}
