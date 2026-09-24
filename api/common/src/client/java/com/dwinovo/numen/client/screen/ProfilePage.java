package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.mc.Sprites;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 同伴资料页,照 Telegram 资料页的顺序排,整页可滚:
 * <ol>
 *   <li>顶部居中:她的大头像(Telegram 资料页那样)、名字、状态、心与鸡腿,下面一排操作块(发消息、编辑);</li>
 *   <li>资料行:左一枚图标,值在上、它是什么在下(人设、模型、声线、上下文、距离、游戏模式);</li>
 *   <li>物品:装备与副手、合成格、背包与快捷栏(Telegram 的共享媒体那一节);</li>
 *   <li>页底一行红字:遣散。</li>
 * </ol>
 * 节与节之间隔一道深色宽缝(Telegram 的分节)。悬停物品的提示最后画,不被后画的格子盖住。
 */
final class ProfilePage {

    /** 点中了什么。 */
    enum Hit { MESSAGE, EDIT, DISMISS }

    /** 大头像的边长:脸是 8×8 像素,取整数倍放大才不糊。 */
    private static final int AVATAR = 48;
    private static final int ICON = 9;
    private static final int SLOT = 18;
    private static final int TILE_H = 30;
    private static final int ROW_H = 21;
    private static final int GAP_H = 7;
    private static final int PAD = 8;
    private static final int TEXT_DX = Sprites.SIZE + 10;
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final ResourceLocation HEART_BG = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation HEART_FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_HALF = ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final ResourceLocation FOOD_BG = ResourceLocation.withDefaultNamespace("hud/food_empty");
    private static final ResourceLocation FOOD_FULL = ResourceLocation.withDefaultNamespace("hud/food_full");
    private static final ResourceLocation FOOD_HALF = ResourceLocation.withDefaultNamespace("hud/food_half");

    private final Font font;
    /** 滚到哪了(像素,按趋近走)、要滚到哪、整页多高、看得见多高。 */
    private float scroll, scrollTarget;
    private int contentH, viewH;
    private long lastFrameMs;
    /** 这一帧可点的几块在哪。 */
    private record Rect(Hit hit, int x, int y, int w, int h) {}
    private final List<Rect> rects = new ArrayList<>();
    private int viewX, viewY, viewW;

    ProfilePage(Font font) {
        this.font = font;
    }

    /** 换了人:回到顶上。 */
    void reset() {
        scroll = 0;
        scrollTarget = 0;
    }

    boolean scroll(double sy) {
        scrollTarget = Math.clamp((float) (scrollTarget - sy * 20), 0f, Math.max(0, contentH - viewH));
        return true;
    }

    Hit click(double mx, double my) {
        if (mx < viewX || mx >= viewX + viewW || my < viewY || my >= viewY + viewH) return null;
        for (Rect r : rects) {
            if (mx >= r.x() && mx < r.x() + r.w() && my >= r.y() && my < r.y() + r.h()) return r.hit();
        }
        return null;
    }

    /** {@code live} = 整页在场、没有模态压着:这时才亮悬停。{@code status} 是抬头第二行那句(在线、正在输入…)。 */
    void render(GuiGraphics g, UUID who, int x, int y, int w, int h, int mouseX, int mouseY,
                boolean live, Function<UUID, String> status) {
        UiTheme t = UiTheme.current();
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        viewX = x;
        viewY = y;
        viewW = w;
        viewH = h;
        scrollTarget = Math.clamp(scrollTarget, 0f, Math.max(0, contentH - viewH));
        scroll = Anim.approach(scroll, scrollTarget, 16f, dt);
        rects.clear();
        if (!live) {
            mouseX = -10000;
            mouseY = -10000;
        }
        var snap = ClientNumenState.get(who).orElse(null);
        AbstractClientPlayer e = ClientNumenLookup.resolve(who);
        EntityAgentLoop loop = AgentLoopRegistry.get(who).orElse(null);
        ItemStack[] hover = {ItemStack.EMPTY};
        String usageTip = null;
        int cx = x + w / 2;
        int iw = w - PAD * 2;   // 里面东西的宽

        g.enableScissor(x, y, x + w, y + h);
        int cy = y + 8 - Math.round(scroll);

        // ---- 顶部:大头像,名字、状态、体征、操作块 ----
        CompanionFace.draw(g, who, KnownSkins.of(who), cx - AVATAR / 2, cy, AVATAR);
        cy += AVATAR + 7;
        Component name = Component.literal(NumenRoster.instance().name(who)).withStyle(ChatFormatting.BOLD);
        Nb.text(g, font, name, cx - font.width(name) / 2, cy, t.text());
        cy += 12;
        String st = status.apply(who);
        Nb.text(g, font, st, cx - font.width(st) / 2, cy, t.textDim());
        cy += 13;
        if (e != null) {
            int food = snap != null && snap.loaded() ? snap.foodLevel() : 0;
            int vx = cx - (10 * ICON * 2 + 8) / 2;
            statRow(g, vx, cy, e.getHealth(), e.getMaxHealth(), HEART_FULL, HEART_HALF, HEART_BG);
            statRow(g, vx + 10 * ICON + 8, cy, food, 20, FOOD_FULL, FOOD_HALF, FOOD_BG);
            cy += ICON + 8;
        }
        int tileW = (iw - 6) / 2;
        tile(g, t, Hit.MESSAGE, Sprites.MESSAGE, I18n.get("numen.profile.message"), x + PAD, cy, tileW, mouseX, mouseY);
        tile(g, t, Hit.EDIT, Sprites.EDIT, I18n.get(ModLanguageData.Keys.EDIT_TITLE), x + PAD + tileW + 6, cy, tileW,
                mouseX, mouseY);
        cy += TILE_H + 8;

        // ---- 资料行 ----
        cy = gap(g, t, x, w, cy);
        String persona = loop != null && loop.personaName() != null && !loop.personaName().isBlank()
                ? loop.personaName() : I18n.get("numen.profile.persona_default");
        infoRow(g, t, Sprites.PERSONA, persona, t.text(), I18n.get("numen.profile.persona"), x + PAD, cy, iw);
        cy += ROW_H;
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
        infoRow(g, t, Sprites.CPU, model, t.text(), modelCaption, x + PAD, cy, iw);
        cy += ROW_H;
        var voice = com.dwinovo.numen.client.voice.VoiceLibrary.instance().resolve(who);
        infoRow(g, t, Sprites.VOLUME, voice != null ? voice.name() : I18n.get("numen.profile.voice_none"), t.text(),
                I18n.get("numen.profile.voice"), x + PAD, cy, iw);
        cy += ROW_H;
        if (loop != null) {
            // 上下文:值是一条水位 + 百分比,占用越高越往警示色走;悬停出用量明细
            int pct = Math.clamp(loop.contextPercent(), 0, 100);
            int barColor = pct < 60 ? t.ok() : pct < 85 ? t.run() : t.fail();
            infoRow(g, t, Sprites.DATABASE, "", t.text(),
                    I18n.get("numen.profile.context", loop.display().size(),
                            com.dwinovo.numen.client.ui.TokenFormat.tokens(loop.totalTokensUsed())),
                    x + PAD, cy, iw);
            int bx = x + PAD + TEXT_DX, barW = 60;
            com.dwinovo.numen.client.ui.StackedBar.draw(
                    new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                    bx, cy + 2, barW, 5, t.field(), 100,   // 只有一段的堆叠条:分母是容量 100
                    List.of(new com.dwinovo.numen.client.ui.StackedBar.Segment(pct, barColor)));
            Nb.text(g, font, pct + "%", bx + barW + 5, cy + 1, t.text());
            if (mouseX >= x && mouseX < x + w && mouseY >= cy && mouseY < cy + ROW_H) usageTip = usageDetail(loop);
            cy += ROW_H;
        }
        Minecraft mc = Minecraft.getInstance();
        String where;
        if (e != null && mc.player != null) {
            double dist = mc.player.distanceTo(e);
            where = dist < 1 ? I18n.get("numen.profile.here")
                    : I18n.get("numen.profile.meters", Math.round(dist), bearingArrow(mc, e));
        } else {
            where = I18n.get("numen.profile.away");
        }
        infoRow(g, t, Sprites.MAP_PIN, where, t.text(), I18n.get("numen.profile.distance"), x + PAD, cy, iw);
        cy += ROW_H;
        var conn = mc.getConnection();
        var info = conn == null ? null : conn.getPlayerInfo(who);
        if (info != null) {
            String mode = I18n.get(info.getGameMode() == net.minecraft.world.level.GameType.CREATIVE
                    ? "numen.profile.creative" : "numen.profile.survival");
            infoRow(g, t, Sprites.GAMEPAD, mode, t.text(), I18n.get("numen.profile.mode"), x + PAD, cy, iw);
            cy += ROW_H;
        }
        cy += 4;

        // ---- 物品:装备与副手、合成格,下面背包与快捷栏 ----
        cy = gap(g, t, x, w, cy);
        Nb.text(g, font, I18n.get("numen.profile.items"), x + PAD, cy, t.accent());
        cy += 13;
        int gx = cx - 9 * SLOT / 2;
        // 左:盔甲 2×2(头胸 / 腿脚)+ 副手;右:合成 2×2 → 结果。两边都占两行高
        for (int i = 0; i < ARMOR.length; i++) {
            int sx = gx + (i % 2) * SLOT, sy = cy + (i / 2) * SLOT;
            slot(g, t, sx, sy);
            if (e != null) collect(g, e.getItemBySlot(ARMOR[i]), sx + 1, sy + 1, mouseX, mouseY, hover);
        }
        int offX = gx + 2 * SLOT + 4, midY = cy + SLOT / 2;
        slot(g, t, offX, midY);
        if (e != null) collect(g, e.getItemBySlot(EquipmentSlot.OFFHAND), offX + 1, midY + 1, mouseX, mouseY, hover);
        List<ItemStack> craft = snap != null ? snap.craft() : List.of();
        int resX = gx + 9 * SLOT - SLOT;
        int crx = resX - 14 - 2 * SLOT;
        for (int i = 0; i < 4; i++) {
            int sx = crx + (i % 2) * SLOT, sy = cy + (i / 2) * SLOT;
            slot(g, t, sx, sy);
            collect(g, i < craft.size() ? craft.get(i) : ItemStack.EMPTY, sx + 1, sy + 1, mouseX, mouseY, hover);
        }
        Nb.text(g, font, "→", crx + 2 * SLOT + 3, midY + 5, t.faint());
        slot(g, t, resX, midY);
        collect(g, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY, resX + 1, midY + 1, mouseX, mouseY, hover);
        cy += 2 * SLOT + 6;
        if (snap == null || !snap.loaded() || snap.items().isEmpty()) {
            String hint = I18n.get(snap == null ? "numen.status.loading" : "numen.status.asleep");
            Nb.text(g, font, hint, gx, cy + 4, t.faint());
            cy += 16;
        } else {
            List<ItemStack> items = snap.items();
            for (int i = 9; i < 36; i++) {
                int col = (i - 9) % 9, row = (i - 9) / 9;
                slot(g, t, gx + col * SLOT, cy + row * SLOT);
                collect(g, items.get(i), gx + col * SLOT + 1, cy + row * SLOT + 1, mouseX, mouseY, hover);
            }
            int hotY = cy + 3 * SLOT + 4;
            for (int i = 0; i < 9; i++) {
                slot(g, t, gx + i * SLOT, hotY);
                collect(g, items.get(i), gx + i * SLOT + 1, hotY + 1, mouseX, mouseY, hover);
            }
            cy = hotY + SLOT + 8;
        }

        // ---- 页底:遣散(红字,点了还要过确认卡) ----
        cy = gap(g, t, x, w, cy);
        int dy = cy;
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= dy && mouseY < dy + ROW_H;
        if (hot) g.fill(x, dy, x + w, dy + ROW_H, t.over());
        Sprites.draw(g, Sprites.DELETE, x + PAD, dy + (ROW_H - Sprites.SIZE) / 2, Sprites.SIZE, t.fail());
        Nb.text(g, font, I18n.get(ModLanguageData.Keys.EDIT_DISMISS), x + PAD + TEXT_DX, dy + (ROW_H - 8) / 2, t.fail());
        rects.add(new Rect(Hit.DISMISS, x, dy, w, ROW_H));
        cy += ROW_H + 8;

        contentH = cy + Math.round(scroll) - y;
        // 滚动条:只有滑块,内容超出一屏才画
        if (contentH > viewH) {
            int th = Math.max(12, viewH * viewH / contentH);
            int ty = y + Math.round((viewH - th) * (scroll / Math.max(1, contentH - viewH)));
            g.fill(x + w - 3, ty, x + w - 1, ty + th, (t.textDim() & 0xFFFFFF) | 0x60000000);
        }
        g.disableScissor();
        if (!hover[0].isEmpty()) g.renderTooltip(font, hover[0], mouseX, mouseY);
        else if (usageTip != null) g.renderTooltip(font, Component.literal(usageTip), mouseX, mouseY);
    }

    /** 操作块(Telegram 名字下面那排):上图标、下字,浅底,悬停深一档。 */
    private void tile(GuiGraphics g, UiTheme t, Hit hit, ResourceLocation icon, String label,
                      int x, int y, int w, int mouseX, int mouseY) {
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TILE_H;
        g.fill(x, y, x + w, y + TILE_H, hot ? UiTheme.mix(t.over(), t.text(), 0.06f) : t.over());
        Sprites.draw(g, icon, x + (w - Sprites.SIZE) / 2, y + 4, Sprites.SIZE, t.accent());
        Nb.text(g, font, label, x + (w - font.width(label)) / 2, y + 19, t.accent());
        rects.add(new Rect(hit, x, y, w, TILE_H));
    }

    /** 节与节之间的深色宽缝;返回下一节的顶边。 */
    private int gap(GuiGraphics g, UiTheme t, int x, int w, int y) {
        g.fill(x, y, x + w, y + GAP_H, UiTheme.mix(t.band(), t.border(), 0.35f));
        return y + GAP_H + 6;
    }

    /** 一行资料:左一枚图标,值在上、它是什么在下;放不下就截短。 */
    private void infoRow(GuiGraphics g, UiTheme t, ResourceLocation icon, String value, int valueColor,
                         String caption, int x, int y, int w) {
        Sprites.draw(g, icon, x, y + (ROW_H - Sprites.SIZE) / 2 - 1, Sprites.SIZE, t.textDim());
        int tx = x + TEXT_DX, room = w - TEXT_DX;
        if (!value.isEmpty()) Nb.text(g, font, Nb.clip(font, value, room), tx, y + 1, valueColor);
        Nb.text(g, font, Nb.clip(font, caption, room), tx, y + 11, t.faint());
    }

    /** 统一凹槽:从窗口底色向边框色压暗两档(边更深、内浅一档),跟着主题换。 */
    private static void slot(GuiGraphics g, UiTheme th, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, UiTheme.mix(th.band(), th.border(), 0.62f));
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, UiTheme.mix(th.band(), th.border(), 0.34f));
    }

    /** 一排 0..max 的体征图标(一格两点),原版 HUD 贴图。 */
    private static void statRow(GuiGraphics g, int x, int y, float value, float max,
                                ResourceLocation full, ResourceLocation half, ResourceLocation empty) {
        int units = Math.max(1, (int) Math.ceil(max / 2f));
        for (int i = 0; i < units; i++) {
            int ix = x + i * ICON;
            g.blitSprite(empty, ix, y, ICON, ICON);
            float v = value - i * 2f;
            if (v >= 2f) g.blitSprite(full, ix, y, ICON, ICON);
            else if (v >= 1f) g.blitSprite(half, ix, y, ICON, ICON);
        }
    }

    /** 画物品并记下悬停的那件(提示整页画完再画,免得被后画的格子盖住)。 */
    private void collect(GuiGraphics g, ItemStack st, int x, int y, int mouseX, int mouseY, ItemStack[] hover) {
        if (st == null || st.isEmpty()) return;
        g.renderItem(st, x, y);
        g.renderItemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) hover[0] = st;
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

    /**
     * 用量明细:{@code ↑输入 ↓输出 R缓存读 W缓存写 CH命中率 占用/窗口}。每段有值才出现——服务商不报缓存
     * 的话那三段自然消失。它是给想知道的人看的,住在上下文那一行的悬停提示里,不常驻。
     * 命中率只看<b>最近一轮</b>:累计命中率会被历史稀释,看不出"刚才那轮把缓存打穿了"。
     */
    private static String usageDetail(EntityAgentLoop lp) {
        var sum = lp.usageTotals();
        List<String> parts = new ArrayList<>();
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
}
