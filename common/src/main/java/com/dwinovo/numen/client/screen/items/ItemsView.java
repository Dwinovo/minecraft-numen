package com.dwinovo.numen.client.screen.items;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.data.ClientNumenInventory;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.RoundRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.UUID;

/**
 * Items 页:同伴的"人物卡"。全部件用当前主题色程序化绘制(与 G 面板
 * 同一 BlockFrame 方言),不再依赖烘焙的槽位贴图;心/鸡腿保留原版式
 * 分段图标。左列 = 立绘卡 + 档案卡(人设/模型/记忆规模/忙闲);右列 =
 * 体征 + 模式芯片、装备排 + 合成格、棋盘格储物 + 快捷栏。
 *
 * <p>tooltip 规矩:槽位循环里只<b>收集</b>悬停物品,整页画完最后才画
 * tooltip——就地画会被后画的槽位盖住(重做前的老 bug)。
 */
public final class ItemsView {

    private static final int ICON = 9;        // native vitals-icon size
    private static final int ICON_STEP = 9;   // touching = one chunky bar
    private static final int SLOT = 18;
    /** Equipment row (left → right): armor head→feet, then offhand. */
    private static final EquipmentSlot[] EQUIP = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

    // 布局骨架(左列 110 + 缝 12 + 右列 162)
    private static final int LEFT_W = 110;
    private static final int GAP = 12;
    private static final int RIGHT_W = 9 * SLOT;          // 162
    private static final int COMP_W = LEFT_W + GAP + RIGHT_W;
    private static final int COMP_H = 160;
    private static final int CHIP_W = 58;
    private static final int CHIP_H = 19;

    private static ResourceLocation spr(String name) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
    }
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

        int startX = left + (panelW - COMP_W) / 2;
        int cTop = top + headerH + (panelH - headerH - COMP_H) / 2;
        int rightX = startX + LEFT_W + GAP;
        ItemStack[] hover = {ItemStack.EMPTY};   // tooltip 收集器:整页最后统一画

        // ---- 左列:立绘卡 ----
        RoundRect.card(g, startX, cTop, startX + LEFT_W, cTop + 72, 4,
                th.surface(), th.surfaceBorder());
        if (e != null) {
            net.minecraft.client.gui.screens.inventory.InventoryScreen
                    .renderEntityInInventoryFollowsMouse(g, startX + 2, cTop + 2,
                            startX + LEFT_W - 2, cTop + 70, 28, 0.0625f,
                            (float) mouseX, (float) mouseY, e);
        }

        // ---- 左列:Agent 状态卡——原版体征之外的一切 ----
        int aY = cTop + 76;
        RoundRect.card(g, startX, aY, startX + LEFT_W, cTop + COMP_H, 4,
                th.surface(), th.surfaceBorder());
        var loop = AgentLoopRegistry.get(uuid).orElse(null);
        int lx = startX + 6;
        int ly = aY + 5;
        int lw = LEFT_W - 12;
        // 人设
        String persona = loop != null && loop.personaName() != null && !loop.personaName().isBlank()
                ? loop.personaName() : "默认人设";
        Nb.text(g, font, clip(font, persona, lw), lx, ly, th.text());
        ly += 11;
        // 模型条目
        String model = loop != null && loop.providerEntryId() != null && !loop.providerEntryId().isBlank()
                ? loop.providerEntryId() : "未绑定模型";
        Nb.text(g, font, clip(font, model, lw), lx, ly, th.textDim());
        ly += 11;
        // 声线
        var voice = com.dwinovo.numen.client.voice.VoiceLibrary.instance().resolve(uuid);
        Nb.text(g, font, clip(font, voice != null ? "♪ " + voice.name() : "无声线", lw),
                lx, ly, th.textDim());
        ly += 11;
        if (loop != null) {
            // 记忆规模:对话条数 + 上下文水位
            Nb.text(g, font, clip(font, "记忆 " + loop.display().size() + " 条 · 上下文 "
                    + loop.contextPercent() + "%", lw), lx, ly, th.textDim());
            ly += 11;
            // 累计消耗
            Nb.text(g, font, clip(font, "累计 " + fmtTokens(loop.totalTokensUsed()) + " tok", lw),
                    lx, ly, th.textDim());
            ly += 11;
            // 位置(实体在附近才有)
            Nb.text(g, font, clip(font, e != null
                    ? "坐标 " + e.getBlockX() + ", " + e.getBlockY() + ", " + e.getBlockZ()
                    : "不在附近", lw), lx, ly, th.textDim());
            ly += 11;
            // 状态行:外接大脑 > 整理记忆 > 忙碌 > 积压 > 空闲
            String state;
            int stateColor;
            if (loop.isExternallyDriven()) { state = "外接大脑驱动中"; stateColor = th.run(); }
            else if (loop.isCompacting())  { state = "整理记忆中"; stateColor = th.run(); }
            else if (loop.isBusy())        { state = "忙碌中"; stateColor = th.run(); }
            else if (loop.hasQueuedPrompts()) {
                state = "积压 " + loop.queuedPrompts().size() + " 条";
                stateColor = th.run();
            } else { state = "空闲"; stateColor = th.ok(); }
            Nb.text(g, font, state, lx, ly, stateColor);
        } else {
            Nb.text(g, font, "尚未对话", lx, ly, th.faint());
        }

        // ---- 右列 A/B:体征(原版式分段心/鸡腿)+ 模式芯片 ----
        if (e != null) renderStatRow(g, rightX, cTop, e.getHealth(), e.getMaxHealth(),
                HEART_FULL, HEART_HALF, HEART_EMPTY);
        int food = (snap != null && snap.loaded()) ? snap.foodLevel() : 0;
        renderStatRow(g, rightX, cTop + ICON + 2, food, 20, FOOD_FULL, FOOD_HALF, FOOD_EMPTY);

        int[] rc = modeChipRect(left, top, panelW, panelH, headerH);
        boolean chipHover = mouseX >= rc[0] && mouseX < rc[0] + rc[2]
                && mouseY >= rc[1] && mouseY < rc[1] + rc[3];
        GameType mode = gameModeOf(uuid);
        String modeLabel = mode == null ? "…" : (mode == GameType.CREATIVE ? "创造" : "生存");
        RoundRect.card(g, rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3], 3,
                th.surface(), chipHover ? th.cta() : th.surfaceBorder());
        Nb.text(g, font, modeLabel, rc[0] + (rc[2] - font.width(modeLabel)) / 2, rc[1] + 6, th.text());

        // ---- 右列 C:装备排(头/胸/腿/脚/副手)+ 2×2 合成格 ----
        int eqY = cTop + 26;
        for (int i = 0; i < EQUIP.length; i++) {
            int x = rightX + i * SLOT;
            slot(g, th, x, eqY, (i & 1) == 1);
            if (e != null) {
                collect(g, font, e.getItemBySlot(EQUIP[i]), x + 1, eqY + 1, mouseX, mouseY, hover);
            }
        }
        for (int i = 0; i < 4; i++) {
            int cx = rightX + 96 + (i % 2) * SLOT, cy = eqY + (i / 2) * SLOT - 9;
            slot(g, th, cx, cy, false);
            collect(g, font, i < craft.size() ? craft.get(i) : ItemStack.EMPTY,
                    cx + 1, cy + 1, mouseX, mouseY, hover);
        }
        Nb.text(g, font, "→", rightX + 96 + 38, eqY, th.faint());
        int resX = rightX + RIGHT_W - SLOT, resY = eqY - 1;
        slot(g, th, resX, resY, true);
        collect(g, font, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY,
                resX + 1, resY + 1, mouseX, mouseY, hover);

        // ---- 右列 D:棋盘格 3×9 储物 + 快捷栏 ----
        int storeY = cTop + 66;
        if (snap == null) {
            Nb.text(g, font, I18n.get("numen.status.loading"), rightX, storeY + 4, th.faint());
            tooltipLast(g, font, hover, mouseX, mouseY);
            return;
        }
        if (!snap.loaded() || snap.items().isEmpty()) {
            Nb.text(g, font, I18n.get("numen.status.asleep"), rightX, storeY + 4, th.faint());
            tooltipLast(g, font, hover, mouseX, mouseY);
            return;
        }
        List<ItemStack> items = snap.items();
        for (int i = 9; i < 36; i++) {                     // storage rows (slots 9..35)
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int x = rightX + col * SLOT, y = storeY + row * SLOT;
            slot(g, th, x, y, ((col + row) & 1) == 1);
            collect(g, font, items.get(i), x + 1, y + 1, mouseX, mouseY, hover);
        }
        int hotbarY = storeY + 3 * SLOT + 6;               // hotbar (slots 0..8)
        for (int i = 0; i < 9; i++) {
            int x = rightX + i * SLOT;
            slot(g, th, x, hotbarY, (i & 1) == 1);
            collect(g, font, items.get(i), x + 1, hotbarY + 1, mouseX, mouseY, hover);
        }

        tooltipLast(g, font, hover, mouseX, mouseY);
    }

    /**
     * 模式芯片点击:以主人身份执行原版 {@code /gamemode <模式> <同伴名>}
     * ——权限判定与成败反馈全部交给原版(没权限时原版红字回话),这里
     * 零权限代码,天然兼容各类权限插件。生存 ↔ 创造往返切换。
     *
     * @return true = 点在芯片上(无论命令结果如何)
     */
    public static boolean click(double mouseX, double mouseY, UUID uuid, String companionName,
                                int left, int top, int panelW, int panelH, int headerH) {
        int[] rc = modeChipRect(left, top, panelW, panelH, headerH);
        if (mouseX < rc[0] || mouseX >= rc[0] + rc[2] || mouseY < rc[1] || mouseY >= rc[1] + rc[3]) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null
                || companionName == null || companionName.isBlank()) {
            return true;
        }
        // 只在生存/创造间往返:创造 → 生存,其余(含生存)→ 创造
        GameType cur = gameModeOf(uuid);
        String next = cur == GameType.CREATIVE ? "survival" : "creative";
        mc.player.connection.sendCommand("gamemode " + next + " " + companionName);
        return true;
    }

    /** 体征区右端的模式芯片矩形 {x, y, w, h}(与 render 同一套布局推导)。 */
    private static int[] modeChipRect(int left, int top, int panelW, int panelH, int headerH) {
        int startX = left + (panelW - COMP_W) / 2;
        int cTop = top + headerH + (panelH - headerH - COMP_H) / 2;
        int rightX = startX + LEFT_W + GAP;
        return new int[]{rightX + RIGHT_W - CHIP_W, cTop, CHIP_W, CHIP_H};
    }

    /** 同伴当前游戏模式:tab 列表的 PlayerInfo 白拿(服务端改模式时原版自动同步)。 */
    private static GameType gameModeOf(UUID uuid) {
        var conn = Minecraft.getInstance().getConnection();
        var info = conn == null ? null : conn.getPlayerInfo(uuid);
        return info == null ? null : info.getGameMode();
    }

    /** 主题色程序化槽位:浅底粗边小方格,棋盘格用 field/surface 两档交替。 */
    private static void slot(GuiGraphics g, UiTheme th, int x, int y, boolean alt) {
        RoundRect.card(g, x, y, x + SLOT - 1, y + SLOT - 1, 2,
                alt ? th.field() : th.surface(), th.surfaceBorder());
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

    /** 画物品并收集悬停(不在此画 tooltip——会被后画的槽位盖住)。 */
    private static void collect(GuiGraphics g, Font font, ItemStack st, int x, int y,
                                int mouseX, int mouseY, ItemStack[] hover) {
        if (st == null || st.isEmpty()) return;
        g.renderItem(st, x, y);
        g.renderItemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            hover[0] = st;
        }
    }

    /** 整页收尾:悬停物品的 tooltip 压最上层画。 */
    private static void tooltipLast(GuiGraphics g, Font font, ItemStack[] hover,
                                    int mouseX, int mouseY) {
        if (!hover[0].isEmpty()) {
            g.renderTooltip(font, hover[0], mouseX, mouseY);
        }
    }

    private static String clip(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String out = font.plainSubstrByWidth(s, maxW - font.width("…"));
        return out + "…";
    }

    private static String fmtTokens(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fk", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }
}
