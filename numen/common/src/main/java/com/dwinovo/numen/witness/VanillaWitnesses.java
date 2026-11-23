package com.dwinovo.numen.witness;

import com.dwinovo.numen.api.Focus;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.entity.Belongings;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.sdk.EntityLabel;
import com.dwinovo.numen.api.witness.Witness;
import com.dwinovo.numen.tools.menu.StackLine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 原版里"一次动作前后变了什么"的回答者,一个只看一样:背包与经验、手里身上的东西里的数据、血量、饥饿、状态效果、骑乘、瞄着的那一格、
 * 对着的那只实体穿的拿的、身边新冒出来的实体、打开或合上的界面、开着的界面里的格子。经插件的门登记({@link #install}),和模组的
 * 回答者同一个写法;多看一样就是在这里多写一个、列进 {@link #install}。
 *
 * <p>都只报事实,不判成败——按键被吃掉不等于发生了什么(船可以吃掉点击,却因为生成位置和身体重叠被原版静默拒绝),什么算"成"只有
 * 意图知道,而意图在模型那边。
 */
public final class VanillaWitnesses {

    /** 新实体的观察半径:够罩住触及距离(4.5)内放出的船、掷物,不把远处的路人算进来。 */
    private static final double ENTITY_RADIUS = 6.0;

    /** 血量差小于它不算变:浮点误差。 */
    private static final float HEALTH_EPSILON = 1.0E-3f;

    private VanillaWitnesses() {}

    public static void install(NumenApi numen) {
        for (Witness witness : List.<Witness>of(VanillaWitnesses::carried, VanillaWitnesses::itemData,
                VanillaWitnesses::health, VanillaWitnesses::food, VanillaWitnesses::effects, VanillaWitnesses::riding,
                VanillaWitnesses::aimedBlock, VanillaWitnesses::targetGear, VanillaWitnesses::newcomers,
                VanillaWitnesses::window, VanillaWitnesses::windowSlots)) {
            numen.answer(Questions.WITNESS, witness);
        }
    }

    /** 背包(含穿戴与副手)里哪种物品多了少了,经验多了少了。 */
    private static Witness.Before carried(NumenPlayer her, Focus focus) {
        Belongings before = Belongings.of(her);
        return now -> {
            List<String> facts = new ArrayList<>();
            String items = before.itemChange(now);
            if (!items.isEmpty()) {
                facts.add("inventory: " + items);
            }
            String xp = before.experienceChange(now);
            if (!xp.isEmpty()) {
                facts.add(xp);
            }
            return facts;
        };
    }

    /**
     * 同一格里还是那种物品、里面的数据变了(耐久、药水、名字、模组存在物品上的状态):背包那一条只数"什么物品、几个",看不到这些。
     * 写法取 {@link StackLabel}(清单里就是这么写的);两边写出来一样时,点名变了的组件。
     */
    private static Witness.Before itemData(NumenPlayer her, Focus focus) {
        Inventory inv = her.getInventory();
        List<ItemStack> before = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            before.add(inv.getItem(i).copy());
        }
        return now -> {
            Inventory after = now.getInventory();
            List<String> facts = new ArrayList<>();
            for (int i = 0; i < before.size(); i++) {
                ItemStack was = before.get(i);
                ItemStack is = after.getItem(i);
                if (was.isEmpty() || !is.is(was.getItem()) || ItemStack.isSameItemSameComponents(was, is)) {
                    continue;
                }
                String from = StackLabel.of(was);
                String to = StackLabel.of(is);
                facts.add(where(after, i) + ": " + (from.equals(to)
                        ? from + " changed inside (" + String.join(", ", changedComponents(was, is)) + ")"
                        : from + " -> " + to));
            }
            return facts;
        };
    }

    private static String where(Inventory inv, int slot) {
        if (slot == inv.selected) {
            return "in your main hand";
        }
        if (slot == Inventory.SLOT_OFFHAND) {
            return "in your off hand";
        }
        return slot >= Inventory.INVENTORY_SIZE ? "worn" : "in your inventory";
    }

    /** 两叠之间值不一样的组件,写组件的注册名。 */
    private static List<String> changedComponents(ItemStack was, ItemStack is) {
        Set<DataComponentType<?>> types = new LinkedHashSet<>(was.getComponents().keySet());
        types.addAll(is.getComponents().keySet());
        List<String> names = new ArrayList<>();
        for (DataComponentType<?> type : types) {
            if (!Objects.equals(was.get(type), is.get(type))) {
                names.add(String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)));
            }
        }
        return names;
    }

    private static Witness.Before health(NumenPlayer her, Focus focus) {
        float before = her.getHealth();
        return now -> {
            String change = healthChange(before, now.getHealth());
            return change.isEmpty() ? List.of() : List.of(change);
        };
    }

    /** 血量从 {@code before} 到 {@code after} 的那一条事实;差在浮点误差以内算没变,是空串。 */
    static String healthChange(float before, float after) {
        return Math.abs(after - before) >= HEALTH_EPSILON ? "health: " + number(before) + " -> " + number(after) : "";
    }

    /** 血量按原版的点数写,整数不带小数点。 */
    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static Witness.Before food(NumenPlayer her, Focus focus) {
        int before = her.getFoodData().getFoodLevel();
        return now -> {
            int after = now.getFoodData().getFoodLevel();
            return after == before ? List.of() : List.of("food: " + before + " -> " + after);
        };
    }

    /** 状态效果有了、没了、换了等级;剩余时间每刻都在走,不比。 */
    private static Witness.Before effects(NumenPlayer her, Focus focus) {
        Map<String, Integer> before = effectLevels(her);
        return now -> {
            Map<String, Integer> after = effectLevels(now);
            List<String> facts = new ArrayList<>();
            after.forEach((id, level) -> {
                Integer was = before.get(id);
                if (was == null) {
                    facts.add("effect gained: " + id + (level > 1 ? " level " + level : ""));
                } else if (!was.equals(level)) {
                    facts.add("effect " + id + ": level " + was + " -> " + level);
                }
            });
            before.keySet().forEach(id -> {
                if (!after.containsKey(id)) {
                    facts.add("effect ended: " + id);
                }
            });
            return facts;
        };
    }

    /** 她身上每种状态效果的等级(从 1 起,原版的放大倍数加一)。 */
    private static Map<String, Integer> effectLevels(NumenPlayer her) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        her.getActiveEffectsMap().forEach((Holder<MobEffect> effect, MobEffectInstance on) ->
                levels.put(effect.unwrapKey().map(key -> key.location().toString()).orElse("unknown"), on.getAmplifier() + 1));
        return levels;
    }

    private static Witness.Before riding(NumenPlayer her, Focus focus) {
        Entity before = her.getVehicle();
        return now -> {
            Entity after = now.getVehicle();
            List<String> facts = new ArrayList<>();
            if (after != before) {
                if (before != null) {
                    facts.add("no longer riding " + EntityLabel.shown(before));
                }
                if (after != null) {
                    facts.add("now riding " + EntityLabel.shown(after));
                }
            }
            return facts;
        };
    }

    /** 瞄着的那一格变成了别的方块。 */
    private static Witness.Before aimedBlock(NumenPlayer her, Focus focus) {
        BlockPos aim = focus.block();
        if (aim == null) {
            return null;
        }
        BlockState before = her.level().getBlockState(aim);
        return now -> {
            BlockState after = now.level().getBlockState(aim);
            return after == before ? List.of() : List.of("block at " + aim.getX() + "," + aim.getY() + "," + aim.getZ()
                    + ": " + blockName(before) + " -> " + blockName(after));
        };
    }

    private static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** 对着的那只实体身上穿的拿的(盔甲架摆装备取装备就在这里变)。 */
    private static Witness.Before targetGear(NumenPlayer her, Focus focus) {
        if (!(focus.entity() instanceof LivingEntity target)) {
            return null;
        }
        List<ItemStack> before = equipment(target);
        return now -> {
            List<ItemStack> after = equipment(target);
            List<String> facts = new ArrayList<>();
            for (int i = 0; i < after.size(); i++) {
                if (!ItemStack.matches(before.get(i), after.get(i))) {
                    facts.add(EntityLabel.shown(target) + " " + EquipmentSlot.values()[i].getName() + ": "
                            + itemName(before.get(i)) + " -> " + itemName(after.get(i)));
                }
            }
            return facts;
        };
    }

    private static List<ItemStack> equipment(LivingEntity living) {
        List<ItemStack> out = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            out.add(living.getItemBySlot(slot).copy());
        }
        return out;
    }

    private static String itemName(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 身边新冒出来的实体(放下的船、掷出去的东西、刷出来的生物)。 */
    private static Witness.Before newcomers(NumenPlayer her, Focus focus) {
        Set<Integer> before = new HashSet<>();
        for (Entity e : nearby(her)) {
            before.add(e.getId());
        }
        return now -> {
            List<String> facts = new ArrayList<>();
            for (Entity e : nearby(now)) {
                if (!before.contains(e.getId())) {
                    facts.add("appeared: " + EntityLabel.shown(e));
                }
            }
            return facts;
        };
    }

    private static List<Entity> nearby(NumenPlayer her) {
        return her.level().getEntities(her, her.getBoundingBox().inflate(ENTITY_RADIUS));
    }

    /** 打开了一个界面,或开着的界面合上了(她自己的背包界面总开着,不算)。 */
    private static Witness.Before window(NumenPlayer her, Focus focus) {
        AbstractContainerMenu before = her.containerMenu;
        return now -> {
            AbstractContainerMenu after = now.containerMenu;
            List<String> facts = new ArrayList<>();
            if (after != before) {
                if (before != now.inventoryMenu) {
                    facts.add("the " + before.getClass().getSimpleName() + " window closed");
                }
                if (after != now.inventoryMenu) {
                    facts.add("opened a window: " + after.getClass().getSimpleName());
                }
            }
            return facts;
        };
    }

    /**
     * 开着的界面里不属于她背包的格子(附魔台里的剑上了附魔、青金石少了几块、铁砧的结果格换了名字——这些不在她的背包里,背包那一条
     * 看不到)。界面换了、合上了就不比,那是 {@link #window} 说的事。
     */
    private static Witness.Before windowSlots(NumenPlayer her, Focus focus) {
        AbstractContainerMenu menu = her.containerMenu;
        if (menu == her.inventoryMenu) {
            return null;
        }
        List<String> before = windowLines(her);
        return now -> {
            List<String> facts = new ArrayList<>();
            if (now.containerMenu == menu) {
                List<String> after = windowLines(now);
                for (int i = 0; i < before.size(); i++) {
                    if (!before.get(i).equals(after.get(i))) {
                        facts.add("window slot " + i + ": " + before.get(i) + " -> " + after.get(i));
                    }
                }
            }
            return facts;
        };
    }

    /** 界面里不属于她背包的每一格,写成一行;她背包的格是空串。 */
    private static List<String> windowLines(NumenPlayer her) {
        List<String> out = new ArrayList<>();
        for (Slot slot : her.containerMenu.slots) {
            out.add(slot.container == her.getInventory() ? "" : StackLine.of(slot.getItem()));
        }
        return out;
    }
}
