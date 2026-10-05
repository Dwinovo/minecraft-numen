package com.dwinovo.numen.core.gear;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 原版的四件甲,作为第一个 {@link GearSource} 经 {@code NumenApi.registerGear} 登记——和模组的饰品栏同一扇门。
 *
 * <p>判据全部取自身体物品栏菜单里真实的 {@code ArmorSlot}:放入问 {@link Slot#mayPlace}(NeoForge 改写成
 * {@code stack.canEquip},Fabric 是原版判据,各加载器用各自的原版规则),取出问 {@link Slot#mayPickup}
 * (绑定诅咒),写入走 {@link Slot#setByPlayer}(触发 {@code onEquipItem},有穿戴音效)。
 * 和玩家在背包界面里拖动盔甲是同一条路径,这里不另写一份规则。
 */
public final class VanillaArmor implements GearSource {

    /** 物品栏菜单里 {@code ARMOR_SLOT_START + i} 对应的部位,顺序即 {@code <worn>} 与自动选位的顺序。 */
    private static final EquipmentSlot[] PIECES = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** 四个槽名,按 {@link #PIECES} 的顺序。{@code slot=armor} 展开成的就是它。 */
    public static final List<String> NAMES = List.of(
            PIECES[0].getName(), PIECES[1].getName(), PIECES[2].getName(), PIECES[3].getName());

    @Override
    public List<GearSlot> slots(NumenPlayer body) {
        List<GearSlot> out = new ArrayList<>(PIECES.length);
        for (int i = 0; i < PIECES.length; i++) {
            out.add(new Piece(NAMES.get(i), body.inventoryMenu.getSlot(InventoryMenu.ARMOR_SLOT_START + i), body));
        }
        return out;
    }

    /** 哪几个部位的真实格子肯收它——与 {@link Piece#refuseWear} 是同一个判据。 */
    @Override
    public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < PIECES.length; i++) {
            if (body.inventoryMenu.getSlot(InventoryMenu.ARMOR_SLOT_START + i).mayPlace(stack)) {
                kinds.add(NAMES.get(i));
            }
        }
        return kinds;
    }

    private record Piece(String name, Slot slot, NumenPlayer body) implements GearSlot {

        @Override
        public ItemStack worn() {
            return slot.getItem();
        }

        @Override
        public Optional<String> refuseWear(ItemStack one) {
            return slot.mayPlace(one) ? Optional.empty()
                    : Optional.of(id(one) + " can't be worn on " + name);
        }

        /** 原版 {@code ArmorSlot} 只在一种情况下不许摘:带"阻止换甲"效果的附魔,也就是绑定诅咒。 */
        @Override
        public Optional<String> refuseRemove() {
            ItemStack worn = slot.getItem();
            return worn.isEmpty() || slot.mayPickup(body) ? Optional.empty()
                    : Optional.of(id(worn) + " is stuck on " + name + " (curse of binding)");
        }

        @Override
        public ItemStack swap(ItemStack in) {
            ItemStack old = slot.getItem();
            slot.setByPlayer(in, old);
            return old;
        }

        private static String id(ItemStack stack) {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        }
    }
}
