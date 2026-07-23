package com.dwinovo.numen.client.screen.items;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.data.ClientNumenInventory;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The Items tab: a vanilla-inventory-style "companion sheet" — armor column + offhand, a live
 * mouse-following portrait, the synced 2×2 craft grid + result, segmented heart/drumstick vitals,
 * and the read-only checkerboard 3×9 storage + hotbar. Body data is fetched on demand (the screen
 * re-requests it every second while this tab is open); HP + equipment come off the live client
 * entity. Stateless — everything is drawn fresh from the snapshot each frame.
 */
public final class ItemsView {

    private static final int ICON = 9;        // native vitals-icon size
    private static final int ICON_STEP = 9;   // touching = one chunky bar
    /** Armor column (top → bottom); offhand is drawn separately below it. */
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static ResourceLocation spr(String name) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
    }
    private static final ResourceLocation SLOT_SPRITE = spr("slot");
    private static final ResourceLocation SLOT_ALT = spr("slot_alt");        // checkerboard
    private static final ResourceLocation HEART_FULL = spr("heart_full");
    private static final ResourceLocation HEART_HALF = spr("heart_half");
    private static final ResourceLocation HEART_EMPTY = spr("heart_empty");
    private static final ResourceLocation FOOD_FULL = spr("food_full");
    private static final ResourceLocation FOOD_HALF = spr("food_half");
    private static final ResourceLocation FOOD_EMPTY = spr("food_empty");

    private ItemsView() {}

    public static void render(GuiGraphics g, Font font, UUID uuid,
                              int left, int top, int panelW, int panelH, int headerH,
                              int mouseX, int mouseY) {
        UiTheme th = UiTheme.current();
        var snap = ClientNumenInventory.get(uuid).orElse(null);
        AbstractClientPlayer e = ClientNumenLookup.resolve(uuid);
        List<ItemStack> craft = snap != null ? snap.craft() : List.of();

        // Two centred columns: LEFT = big portrait + armor column + offhand; RIGHT = craft + vitals +
        // 3×9 storage + hotbar. Symmetric framing margins (no lopsided whitespace).
        final int STORAGE_W = 9 * 18;                     // 162 — the widest element (caps the band)
        final int COMP_W = 130 + STORAGE_W;               // left col (130) + right col (storage)
        final int COMP_H = 152;
        int startX = left + (panelW - COMP_W) / 2;
        int cTop = top + headerH + (panelH - headerH - COMP_H) / 2;
        int rightX = startX + 130;

        // -- LEFT: portrait socket, armor column + offhand (vertically centred against the portrait) --
        renderPortrait(g, e, startX + 22, cTop, 84, COMP_H, mouseX, mouseY);
        int armorTop = cTop + (COMP_H - 5 * 18) / 2;
        for (int i = 0; i < ARMOR.length; i++) {
            drawEquip(g, font, e, ARMOR[i], startX, armorTop + i * 18, mouseX, mouseY);
        }
        drawEquip(g, font, e, EquipmentSlot.OFFHAND, startX, armorTop + 4 * 18, mouseX, mouseY);

        // -- RIGHT top: synced 2×2 craft grid (+ arrow + result) --
        for (int i = 0; i < 4; i++) {
            int cx = rightX + (i % 2) * 18, cy = cTop + (i / 2) * 18;
            slotBg(g, SLOT_SPRITE, cx, cy);
            stackOn(g, font, i < craft.size() ? craft.get(i) : ItemStack.EMPTY, cx, cy, mouseX, mouseY);
        }
        Nb.text(g, font, "→", rightX + 38, cTop + 13, th.textDim());
        int resultX = rightX + 54, resultY = cTop + 9;
        slotBg(g, SLOT_SPRITE, resultX, resultY);
        stackOn(g, font, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY, resultX, resultY, mouseX, mouseY);

        // -- RIGHT mid: segmented hearts + drumsticks --
        if (e != null) renderStatRow(g, rightX, cTop + 46, e.getHealth(), e.getMaxHealth(),
                HEART_FULL, HEART_HALF, HEART_EMPTY);
        int food = (snap != null && snap.loaded()) ? snap.foodLevel() : 0;
        renderStatRow(g, rightX, cTop + 46 + ICON + 2, food, 20, FOOD_FULL, FOOD_HALF, FOOD_EMPTY);

        // -- RIGHT bottom: checkerboard 3×9 storage + hotbar --
        int storeY = cTop + 74;
        if (snap == null) {
            Nb.text(g, font, I18n.get("numen.status.loading"), rightX, storeY + 4, th.faint());
            return;
        }
        if (!snap.loaded() || snap.items().isEmpty()) {
            Nb.text(g, font, I18n.get("numen.status.asleep"), rightX, storeY + 4, th.faint());
            return;
        }
        List<ItemStack> items = snap.items();
        for (int i = 9; i < 36; i++) {                     // storage rows (slots 9..35)
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int x = rightX + col * 18, y = storeY + row * 18;
            slotBg(g, ((col + row) & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, y);
            stackOn(g, font, items.get(i), x, y, mouseX, mouseY);
        }
        int hotbarY = storeY + 3 * 18 + 6;                 // hotbar (slots 0..8)
        for (int i = 0; i < 9; i++) {
            int x = rightX + i * 18;
            slotBg(g, (i & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, hotbarY);
            stackOn(g, font, items.get(i), x, hotbarY, mouseX, mouseY);
        }
    }

    /** A row of segmented icons for a 0..max stat (2 units per icon): empty sockets first, then
     *  full / half overlaid. Used for hearts (HP) and drumsticks (hunger). */
    private static void renderStatRow(GuiGraphics g, int x, int y, float value, float max,
                                      ResourceLocation full, ResourceLocation half, ResourceLocation empty) {
        int units = Math.max(1, (int) Math.ceil(max / 2f));
        for (int i = 0; i < units; i++) {
            int ix = x + i * ICON_STEP;
            g.blitSprite(empty, ix, y, ICON, ICON);
            float v = value - i * 2f;
            if (v >= 2f)      g.blitSprite(full, ix, y, ICON, ICON);
            else if (v >= 1f) g.blitSprite(half, ix, y, ICON, ICON);
        }
    }

    /** Live mouse-following 3D portrait of the companion — the body IS a client player entity, so the
     *  vanilla player renderer draws it for free. Sits in a recessed socket (slot_alt stretched). */
    private static void renderPortrait(GuiGraphics g, AbstractClientPlayer e,
                                       int x, int y, int w, int h, int mouseX, int mouseY) {
        g.blitSprite(SLOT_ALT, x, y, w, h);
        if (e == null) return;
        int scale = (int) (h * 0.45f);
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(
                g, x + 2, y + 2, x + w - 2, y + h - 2, scale, 0.0625f,
                (float) mouseX, (float) mouseY, e);
    }

    private static void slotBg(GuiGraphics g, ResourceLocation sprite, int x, int y) {
        g.blitSprite(sprite, x, y, 16, 16);
    }

    private static void stackOn(GuiGraphics g, Font font, ItemStack st, int x, int y, int mouseX, int mouseY) {
        if (st == null || st.isEmpty()) return;
        g.renderItem(st, x, y);
        g.renderItemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            g.renderTooltip(font, st, mouseX, mouseY);
        }
    }

    /** One equipment/armor socket, read off the live client entity (equipment IS client-synced). */
    private static void drawEquip(GuiGraphics g, Font font, AbstractClientPlayer e, EquipmentSlot slot,
                                  int x, int y, int mouseX, int mouseY) {
        slotBg(g, SLOT_SPRITE, x, y);
        if (e != null) stackOn(g, font, e.getItemBySlot(slot), x, y, mouseX, mouseY);
    }
}
