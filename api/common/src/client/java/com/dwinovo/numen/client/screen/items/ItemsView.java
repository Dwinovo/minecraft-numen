package com.dwinovo.numen.client.screen.items;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.RoundRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Items 页:同伴的"人物卡",布局贴着原版物品栏的肌肉记忆走——左边
 * 盔甲柱 + 立绘,右边体征、合成、3×9 储物与快捷栏,底部一条横贯的
 * Agent 状态带。心/鸡腿用原版 HUD 贴图;槽位是统一的深色凹槽(半透黑,
 * 任何主题下都读得出"这是格子");卡片用当前主题色程序化绘制。
 *
 * <p>tooltip 规矩:槽位循环里只<b>收集</b>悬停物品,整页画完最后才画
 * ——就地画会被后画的槽位盖住。
 */
public final class ItemsView {

    private static final int ICON = 9;
    private static final int ICON_STEP = 9;
    private static final int SLOT = 18;
    /** Armor column (top → bottom); offhand drawn below it. */
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    // 布局骨架:左块(盔甲柱 22 + 立绘 96)+ 缝 12 + 右块 162;底部 Agent 带
    private static final int LEFT_W = 118;
    private static final int GAP = 12;
    private static final int RIGHT_W = 9 * SLOT;          // 162
    private static final int COMP_W = LEFT_W + GAP + RIGHT_W;   // 292
    private static final int TOP_H = 116;                 // 上半(立绘/储物)
    private static final int AGENT_H = 46;                // Agent 状态带
    private static final int COMP_H = TOP_H + 6 + AGENT_H;

    // 原版 HUD 贴图:心与鸡腿
    private static final Identifier HEART_BG = Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
    private static final Identifier FOOD_BG = Identifier.withDefaultNamespace("hud/food_empty");
    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
    private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");

    private ItemsView() {}

    public static void render(GuiGraphicsExtractor g, Font font, UUID uuid,
                              int left, int top, int panelW, int panelH, int headerH,
                              int mouseX, int mouseY) {
        UiTheme th = UiTheme.current();
        var snap = ClientNumenState.get(uuid).orElse(null);
        AbstractClientPlayer e = ClientNumenLookup.resolve(uuid);
        List<ItemStack> craft = snap != null ? snap.craft() : List.of();

        int startX = left + (panelW - COMP_W) / 2;
        int cTop = top + headerH + (panelH - headerH - COMP_H) / 2;
        int rightX = startX + LEFT_W + GAP;
        ItemStack[] hover = {ItemStack.EMPTY};

        // ---- 左块:盔甲柱(纵向,原版语序头→脚+副手)+ 立绘卡 ----
        int armorTop = cTop + (TOP_H - 5 * SLOT) / 2;
        for (int i = 0; i < ARMOR.length; i++) {
            slot(g, th, startX, armorTop + i * SLOT);
            if (e != null) collect(g, font, e.getItemBySlot(ARMOR[i]),
                    startX + 1, armorTop + i * SLOT + 1, mouseX, mouseY, hover);
        }
        slot(g, th, startX, armorTop + 4 * SLOT);
        if (e != null) collect(g, font, e.getItemBySlot(EquipmentSlot.OFFHAND),
                startX + 1, armorTop + 4 * SLOT + 1, mouseX, mouseY, hover);

        RoundRect.card(g, startX + 22, cTop, startX + LEFT_W, cTop + TOP_H, 4,
                th.surface(), th.surfaceBorder());
        if (e != null) {
            net.minecraft.client.gui.screens.inventory.InventoryScreen
                    .extractEntityInInventoryFollowsMouse(g, startX + 24, cTop + 2,
                            startX + LEFT_W - 2, cTop + TOP_H - 2, 42, 0.0625f,
                            (float) mouseX, (float) mouseY, e);
        }

        // ---- 右块顶行:体征(原版心/鸡腿)左侧,2×2 合成 + 结果右侧 ----
        if (e != null) renderStatRow(g, rightX, cTop, e.getHealth(), e.getMaxHealth(),
                HEART_FULL, HEART_HALF, HEART_BG);
        int food = (snap != null && snap.loaded()) ? snap.foodLevel() : 0;
        renderStatRow(g, rightX, cTop + ICON + 2, food, 20, FOOD_FULL, FOOD_HALF, FOOD_BG);

        for (int i = 0; i < 4; i++) {
            int cx = rightX + 96 + (i % 2) * SLOT, cy = cTop + (i / 2) * SLOT;
            slot(g, th, cx, cy);
            collect(g, font, i < craft.size() ? craft.get(i) : ItemStack.EMPTY,
                    cx + 1, cy + 1, mouseX, mouseY, hover);
        }
        Nb.text(g, font, "→", rightX + 96 + 38, cTop + 13, th.faint());
        int resX = rightX + RIGHT_W - SLOT, resY = cTop + 9;
        slot(g, th, resX, resY);
        collect(g, font, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY,
                resX + 1, resY + 1, mouseX, mouseY, hover);

        // ---- 右块:3×9 储物 + 快捷栏(统一深色凹槽,快捷栏隔条小缝) ----
        int storeY = cTop + 40;
        if (snap == null || !snap.loaded() || snap.items().isEmpty()) {
            String hint = I18n.get(snap == null ? "numen.status.loading" : "numen.status.asleep");
            Nb.text(g, font, hint, rightX, storeY + 4, th.faint());
        } else {
            List<ItemStack> items = snap.items();
            for (int i = 9; i < 36; i++) {
                int col = (i - 9) % 9, row = (i - 9) / 9;
                int x = rightX + col * SLOT, y = storeY + row * SLOT;
                slot(g, th, x, y);
                collect(g, font, items.get(i), x + 1, y + 1, mouseX, mouseY, hover);
            }
            int hotbarY = storeY + 3 * SLOT + 4;
            for (int i = 0; i < 9; i++) {
                int x = rightX + i * SLOT;
                slot(g, th, x, hotbarY);
                collect(g, font, items.get(i), x + 1, hotbarY + 1, mouseX, mouseY, hover);
            }
        }

        // ---- 底部:Agent 状态带(两栏信息 + 右上角模式芯片) ----
        int aY = cTop + TOP_H + 6;
        RoundRect.card(g, startX, aY, startX + COMP_W, aY + AGENT_H, 4,
                th.surface(), th.surfaceBorder());
        var loop = AgentLoopRegistry.get(uuid).orElse(null);
        int c1 = startX + 8;
        int c2 = startX + COMP_W / 2 + 4;
        int lw = COMP_W / 2 - 16;
        int ly = aY + 6;
        // 人设行:8px 小脸 + 名字
        com.dwinovo.numen.client.skin.CompanionFace.draw(
                g, uuid, com.dwinovo.numen.client.agent.KnownSkins.of(uuid), c1, ly - 1, 8);
        String persona = loop != null && loop.personaName() != null && !loop.personaName().isBlank()
                ? loop.personaName() : "默认人设";
        Nb.text(g, font, clip(font, persona, lw - 11), c1 + 11, ly, th.text());
        // 模型行:条目 ID 解析回人读的名字(条目名 · 型号),别把主键糊给用户
        String model = "未绑定模型";
        if (loop != null && loop.providerEntryId() != null && !loop.providerEntryId().isBlank()) {
            var entry = com.dwinovo.numen.agent.llm.ProviderLibrary.instance()
                    .get(loop.providerEntryId());
            model = entry != null
                    ? entry.name() + (entry.model() == null || entry.model().isBlank()
                            ? "" : " · " + entry.model())
                    : "条目已删除";
        }
        Nb.text(g, font, clip(font, "模型 " + model, lw), c1, ly + 12, th.textDim());
        var voice = com.dwinovo.numen.client.voice.VoiceLibrary.instance().resolve(uuid);
        Nb.text(g, font, clip(font, "声线 " + (voice != null ? voice.name() : "无"), lw),
                c1, ly + 24, th.textDim());
        if (loop != null) {
            // 记忆行:水位条(绿→琥珀→红)+ 条数与累计消耗
            Nb.text(g, font, "记忆", c2, ly, th.textDim());
            int barX = c2 + 26, barW = 46, pct = Math.clamp(loop.contextPercent(), 0, 100);
            int barColor = pct < 60 ? th.ok() : pct < 85 ? th.run() : th.fail();
            // 水位是"只有一段"的堆叠条:分母是容量 100,不是各段之和
            com.dwinovo.numen.client.ui.StackedBar.draw(
                    new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                    barX, ly + 1, barW, 6, th.field(), 100,
                    java.util.List.of(new com.dwinovo.numen.client.ui.StackedBar.Segment(
                            pct, barColor)));
            Nb.text(g, font, clip(font, loop.display().size() + "条·"
                    + com.dwinovo.numen.client.ui.TokenFormat.tokens(loop.totalTokensUsed()), lw - 26 - barW - 8),
                    barX + barW + 4, ly, th.textDim());
            // 距离行:相对朝向的方位箭头——一眼知道她在哪边
            Minecraft mc = Minecraft.getInstance();
            String where;
            if (e != null && mc.player != null) {
                double dist = mc.player.distanceTo(e);
                where = "距离 " + (dist < 1 ? "就在身边" : Math.round(dist) + " 米 " + bearingArrow(mc, e));
            } else {
                where = "距离 不在附近";
            }
            Nb.text(g, font, clip(font, where, lw), c2, ly + 12, th.textDim());
            // 状态行:呼吸圆点 + 文案
            String state;
            int stateColor;
            boolean alive;
            if (loop.isExternallyDriven()) { state = "外接大脑驱动中"; stateColor = th.run(); alive = true; }
            else if (loop.isCompacting())  { state = "整理记忆中"; stateColor = th.run(); alive = true; }
            else if (loop.isBusy())        { state = "忙碌中"; stateColor = th.run(); alive = true; }
            else if (loop.hasQueuedPrompts()) {
                state = "积压 " + loop.queuedPrompts().size() + " 条"; stateColor = th.run(); alive = true;
            } else { state = "空闲"; stateColor = th.ok(); alive = false; }
            String dot = alive ? (System.currentTimeMillis() / 500 % 2 == 0 ? "●" : "○") : "●";
            String stateText = dot + " " + state;
            Nb.text(g, font, stateText, c2, ly + 24, stateColor);
            // 游戏模式只读展示(创建时选定;切换交互待定)
            var conn = Minecraft.getInstance().getConnection();
            var info = conn == null ? null : conn.getPlayerInfo(uuid);
            if (info != null) {
                String modeText = " · " + (info.getGameMode() == net.minecraft.world.level.GameType.CREATIVE
                        ? "创造" : "生存");
                Nb.text(g, font, modeText, c2 + font.width(stateText), ly + 24, th.textDim());
            }
        } else {
            Nb.text(g, font, "○ 尚未对话", c2, ly, th.faint());
        }

        tooltipLast(g, font, hover, mouseX, mouseY);
    }

    /** 统一凹槽:从当前主题的地色向边框色压暗两档(边更深、内浅一档)——
     *  深色但同一家谱,切主题跟着换装,不是生硬的半透黑。 */
    private static void slot(GuiGraphicsExtractor g, UiTheme th, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, UiTheme.mix(th.ground(), th.border(), 0.62f));
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, UiTheme.mix(th.ground(), th.border(), 0.34f));
    }

    /** A row of segmented icons for a 0..max stat (2 units per icon): vanilla HUD sprites. */
    private static void renderStatRow(GuiGraphicsExtractor g, int x, int y, float value, float max,
                                      Identifier full, Identifier half, Identifier empty) {
        int units = Math.max(1, (int) Math.ceil(max / 2f));
        for (int i = 0; i < units; i++) {
            int ix = x + i * ICON_STEP;
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, empty, ix, y, ICON, ICON);
            float v = value - i * 2f;
            if (v >= 2f)      g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, full, ix, y, ICON, ICON);
            else if (v >= 1f) g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, half, ix, y, ICON, ICON);
        }
    }

    /** 画物品并收集悬停(不在此画 tooltip——会被后画的槽位盖住)。 */
    private static void collect(GuiGraphicsExtractor g, Font font, ItemStack st, int x, int y,
                                int mouseX, int mouseY, ItemStack[] hover) {
        if (st == null || st.isEmpty()) return;
        g.item(st, x, y);
        g.itemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            hover[0] = st;
        }
    }

    /** 整页收尾:悬停物品的 tooltip 压最上层画。 */
    private static void tooltipLast(GuiGraphicsExtractor g, Font font, ItemStack[] hover,
                                    int mouseX, int mouseY) {
        if (!hover[0].isEmpty()) {
            g.setTooltipForNextFrame(font, hover[0], mouseX, mouseY);
        }
    }

    private static String clip(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String out = font.plainSubstrByWidth(s, maxW - font.width("…"));
        return out + "…";
    }

    /** 同伴相对主人朝向的八方位箭头(↑ = 正前方)。 */
    private static String bearingArrow(Minecraft mc, AbstractClientPlayer target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        float yawToTarget = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = net.minecraft.util.Mth.wrapDegrees(yawToTarget - mc.player.getYRot());
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return arrows[Math.floorMod(Math.round(rel / 45f), 8)];
    }
}
