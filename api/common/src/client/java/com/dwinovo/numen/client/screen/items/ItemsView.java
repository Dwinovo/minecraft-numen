package com.dwinovo.numen.client.screen.items;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.mc.Sprites;
import net.minecraft.client.Minecraft;
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
 * Items 页:同伴的"人物卡",布局贴着原版物品栏的肌肉记忆走——左边
 * 盔甲柱 + 立绘,右边体征、合成、3×9 储物与快捷栏;底下是资料行(Telegram 资料页那种:
 * 左一枚图标,第一行是值,下面一行小字说它是什么,没有外框)。心/鸡腿用原版 HUD 贴图;
 * 槽位是统一的深色凹槽(任何主题下都读得出"这是格子")。
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
    /** 资料行:一行两排字(值 + 它是什么),两栏各三行。 */
    private static final int ROW_H = 21;
    private static final int INFO_GAP = 8;
    private static final int INFO_H = 3 * ROW_H;
    private static final int COMP_H = TOP_H + INFO_GAP + INFO_H;

    // 原版 HUD 贴图:心与鸡腿
    private static final ResourceLocation HEART_BG = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation HEART_FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_HALF = ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final ResourceLocation FOOD_BG = ResourceLocation.withDefaultNamespace("hud/food_empty");
    private static final ResourceLocation FOOD_FULL = ResourceLocation.withDefaultNamespace("hud/food_full");
    private static final ResourceLocation FOOD_HALF = ResourceLocation.withDefaultNamespace("hud/food_half");

    private ItemsView() {}

    public static void render(GuiGraphics g, Font font, UUID uuid,
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

        com.dwinovo.numen.client.ui.NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font), startX + 22, cTop, LEFT_W - 22, TOP_H,
                th.surface(), th.surfaceBorder());
        if (e != null) {
            net.minecraft.client.gui.screens.inventory.InventoryScreen
                    .renderEntityInInventoryFollowsMouse(g, startX + 24, cTop + 2,
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

        // ---- 底部:资料行,两栏各三行。左栏是她是谁(人设、模型、声线),右栏是她此刻怎样(上下文、距离、状态) ----
        int aY = cTop + TOP_H + INFO_GAP;
        var loop = AgentLoopRegistry.get(uuid).orElse(null);
        int c1 = startX, c2 = startX + COMP_W / 2;
        int colW = COMP_W / 2 - 4;
        String persona = loop != null && loop.personaName() != null && !loop.personaName().isBlank()
                ? loop.personaName() : I18n.get("numen.profile.persona_default");
        infoRow(g, font, th, Sprites.PERSONA, persona, th.text(), I18n.get("numen.profile.persona"), c1, aY, colW);
        // 模型:值是型号(要紧的那个),小字是"模型 · 条目名"——条目 ID 不糊给用户
        String model = I18n.get("numen.profile.model_none");
        String modelCaption = I18n.get("numen.profile.model");
        if (loop != null && loop.providerEntryId() != null && !loop.providerEntryId().isBlank()) {
            var entry = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().get(loop.providerEntryId());
            if (entry == null) {
                model = I18n.get("numen.profile.model_deleted");
            } else {
                model = entry.model() == null || entry.model().isBlank() ? entry.name() : entry.model();
                modelCaption = I18n.get("numen.profile.model_of", entry.name());
            }
        }
        infoRow(g, font, th, Sprites.CPU, model, th.text(), modelCaption, c1, aY + ROW_H, colW);
        var voice = com.dwinovo.numen.client.voice.VoiceLibrary.instance().resolve(uuid);
        infoRow(g, font, th, Sprites.VOLUME, voice != null ? voice.name() : I18n.get("numen.profile.voice_none"),
                th.text(), I18n.get("numen.profile.voice"), c1, aY + 2 * ROW_H, colW);
        String usageTip = null;
        if (loop != null) {
            // 上下文:值是一条水位 + 百分比,占用越高越往警示色走;悬停出用量明细
            int pct = Math.clamp(loop.contextPercent(), 0, 100);
            int barColor = pct < 60 ? th.ok() : pct < 85 ? th.run() : th.fail();
            infoRow(g, font, th, Sprites.DATABASE, "", th.text(),
                    I18n.get("numen.profile.context", loop.display().size(),
                            com.dwinovo.numen.client.ui.TokenFormat.tokens(loop.totalTokensUsed())),
                    c2, aY, colW);
            int bx = c2 + TEXT_DX, barW = 40;
            com.dwinovo.numen.client.ui.StackedBar.draw(
                    new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                    bx, aY + 2, barW, 5, th.field(), 100,   // 只有一段的堆叠条:分母是容量 100
                    java.util.List.of(new com.dwinovo.numen.client.ui.StackedBar.Segment(pct, barColor)));
            Nb.text(g, font, pct + "%", bx + barW + 4, aY + 1, th.text());
            if (mouseX >= c2 && mouseX < c2 + colW && mouseY >= aY && mouseY < aY + ROW_H) {
                usageTip = usageDetail(loop);
            }
            // 距离:相对朝向的方位箭头——一眼知道她在哪边
            Minecraft mc = Minecraft.getInstance();
            String where;
            if (e != null && mc.player != null) {
                double dist = mc.player.distanceTo(e);
                where = dist < 1 ? I18n.get("numen.profile.here")
                        : I18n.get("numen.profile.meters", Math.round(dist), bearingArrow(mc, e));
            } else {
                where = I18n.get("numen.profile.away");
            }
            infoRow(g, font, th, Sprites.MAP_PIN, where, th.text(), I18n.get("numen.profile.distance"),
                    c2, aY + ROW_H, colW);
            // 状态:呼吸圆点 + 文案,后面跟游戏模式(创建时选定,只读)
            String state;
            int stateColor;
            boolean alive;
            var status = loop.status();
            if (com.dwinovo.numen.mcp.server.McpMode.instance().driving()) {
                state = I18n.get("numen.profile.state_external"); stateColor = th.run(); alive = true;
            } else if (status.phase() == com.dwinovo.numen.agent.loop.Phase.COMPACT) {
                state = I18n.get("numen.profile.state_compact"); stateColor = th.run(); alive = true;
            } else if (status.busy()) {
                state = I18n.get("numen.profile.state_busy"); stateColor = th.run(); alive = true;
            } else if (!status.queuedPreview().isEmpty()) {
                state = I18n.get("numen.profile.state_queued", status.queuedPreview().size());
                stateColor = th.run(); alive = true;
            } else {
                state = I18n.get("numen.profile.state_idle"); stateColor = th.ok(); alive = false;
            }
            String dot = alive ? (System.currentTimeMillis() / 500 % 2 == 0 ? "●" : "○") : "●";
            var conn = mc.getConnection();
            var info = conn == null ? null : conn.getPlayerInfo(uuid);
            String modeText = info == null ? "" : " · " + I18n.get(
                    info.getGameMode() == net.minecraft.world.level.GameType.CREATIVE
                            ? "numen.profile.creative" : "numen.profile.survival");
            String stateText = dot + " " + state;
            infoRow(g, font, th, Sprites.HEART, stateText, stateColor, I18n.get("numen.profile.state"),
                    c2, aY + 2 * ROW_H, colW);
            int after = c2 + TEXT_DX + font.width(stateText);
            Nb.text(g, font, Nb.clip(font, modeText, c2 + colW - after), after, aY + 2 * ROW_H + 1, th.textDim());
        } else {
            infoRow(g, font, th, Sprites.HEART, "○ " + I18n.get("numen.profile.not_started"), th.faint(),
                    I18n.get("numen.profile.state"), c2, aY, colW);
        }

        tooltipLast(g, font, hover, mouseX, mouseY);
        if (usageTip != null) g.renderTooltip(font, net.minecraft.network.chat.Component.literal(usageTip), mouseX, mouseY);
    }

    /** 资料行里字的左缘(图标右边)。 */
    private static final int TEXT_DX = Sprites.SIZE + 7;

    /** 一行资料:左一枚图标,第一行是值,下面一行小字说它是什么;放不下就截短。 */
    private static void infoRow(GuiGraphics g, Font font, UiTheme th, ResourceLocation icon, String value,
                                int valueColor, String caption, int x, int y, int w) {
        Sprites.draw(g, icon, x, y + (ROW_H - Sprites.SIZE) / 2 - 1, Sprites.SIZE, th.textDim());
        int tx = x + TEXT_DX, room = w - TEXT_DX;
        if (!value.isEmpty()) Nb.text(g, font, Nb.clip(font, value, room), tx, y + 1, valueColor);
        Nb.text(g, font, Nb.clip(font, caption, room), tx, y + 11, th.faint());
    }

    /**
     * 用量明细:{@code ↑输入 ↓输出 R缓存读 W缓存写 CH命中率 占用/窗口}。每段有值才出现——服务商不报缓存
     * 的话那三段自然消失。它是给想知道的人看的,住在上下文那一行的悬停提示里,不常驻。
     * 命中率只看<b>最近一轮</b>:累计命中率会被历史稀释,看不出"刚才那轮把缓存打穿了"。
     */
    private static String usageDetail(com.dwinovo.numen.client.agent.EntityAgentLoop lp) {
        var sum = lp.usageTotals();
        List<String> parts = new java.util.ArrayList<>();
        if (sum.input() > 0) parts.add("↑" + com.dwinovo.numen.client.ui.TokenFormat.tokens(sum.input()));
        if (sum.output() > 0) parts.add("↓" + com.dwinovo.numen.client.ui.TokenFormat.tokens(sum.output()));
        if (sum.cacheRead() > 0) parts.add("R" + com.dwinovo.numen.client.ui.TokenFormat.tokens(sum.cacheRead()));
        if (sum.cacheWrite() > 0) parts.add("W" + com.dwinovo.numen.client.ui.TokenFormat.tokens(sum.cacheWrite()));
        double hit = lp.lastUsage().cacheHitRate();
        if (sum.reportsCache() && hit >= 0) {
            parts.add("CH" + com.dwinovo.numen.client.ui.TokenFormat.percent1(hit) + "%");
        }
        parts.add(lp.contextPercent() + "%/" + com.dwinovo.numen.client.ui.TokenFormat.tokens(lp.modelWindow()));
        return String.join(" ", parts);
    }

    /** 统一凹槽:从当前主题的地色向边框色压暗两档(边更深、内浅一档)——
     *  深色但同一家谱,切主题跟着换装,不是生硬的半透黑。 */
    private static void slot(GuiGraphics g, UiTheme th, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, UiTheme.mix(th.band(), th.border(), 0.62f));
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, UiTheme.mix(th.band(), th.border(), 0.34f));
    }

    /** A row of segmented icons for a 0..max stat (2 units per icon): vanilla HUD sprites. */
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
