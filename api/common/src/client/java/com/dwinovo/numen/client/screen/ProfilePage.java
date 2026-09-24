package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.mc.Sprites;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.persona.PersonaLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 同伴资料页,照 Telegram 资料页的想法排:先是"这个人",再是"你们之间的东西",配置收在后面。整页可滚。
 * <ol>
 *   <li>顶部居中:大头像、名字、此刻在干什么(没在干活才说在线/忙碌),体征,一排操作块(发消息、编辑);</li>
 *   <li>资料:简介(她人设的开头一段)、在你哪边多远;</li>
 *   <li>她记得的事:最新几条札记(Telegram 的共享媒体那一节,我们这里最有意思的是她记住了什么);</li>
 *   <li>共同群聊:她在哪几个群里,点了进去;</li>
 *   <li>背包:收成一行"背包 · N 件",点开往下展开格子;</li>
 *   <li>设置:人设、模型、声线、游戏模式、上下文,一行一项、左名右值,点了开编辑卡;</li>
 *   <li>页底红字:遣散。</li>
 * </ol>
 * 节与节之间隔一道深色宽缝(Telegram 的分节),节头是强调色小字。
 */
final class ProfilePage {

    /** 点中了什么:发消息、编辑、遣散、进某个群。 */
    sealed interface Hit {
        record Message() implements Hit {}
        record Edit() implements Hit {}
        record Dismiss() implements Hit {}
        record Open(Conversation conversation) implements Hit {}
    }

    /** 大头像的边长:脸是 8×8 像素,取整数倍放大才不糊。 */
    private static final int AVATAR = 48;
    private static final int ICON = 9;
    private static final int SLOT = 18;
    private static final int TILE_H = 30;
    /** 两行的资料(值 + 它是什么)与一行的设置项。 */
    private static final int ROW_H = 21, LINE_ROW_H = 15;
    private static final int GAP_H = 7;
    private static final int PAD = 8;
    private static final int TEXT_DX = Sprites.SIZE + 10;
    /** 她记得的事摆几条;多的说"还有 N 条"。 */
    private static final int NOTES_SHOWN = 3;
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
    /** 背包那一节开着没有,和它展开到多高(像素,按趋近走)。 */
    private boolean itemsOpen;
    private float itemsShown;
    /** 这一帧可点的几块在哪。 */
    private record Rect(Hit hit, int x, int y, int w, int h) {}
    private final List<Rect> rects = new ArrayList<>();
    private int viewX, viewY, viewW;
    /** 背包那一行这一帧的顶边;点它是页内开合。 */
    private int itemsRowY = Integer.MIN_VALUE;
    /** 札记按"哪一本、写过几次"缓存:索引要读盘,不能每帧读。 */
    private UUID notesOf;
    private int notesRevision = -1;
    private List<NoteBook.Note> notes = List.of();

    ProfilePage(Font font) {
        this.font = font;
    }

    /** 换了人:回到顶上,背包收起。 */
    void reset() {
        scroll = 0;
        scrollTarget = 0;
        itemsOpen = false;
        itemsShown = 0;
    }

    boolean scroll(double sy) {
        scrollTarget = Math.clamp((float) (scrollTarget - sy * 20), 0f, Math.max(0, contentH - viewH));
        return true;
    }

    /** 点击:背包那一行在页内开合(返回 null,但这一下算吃掉了,见 {@link #consumes});别的交给宿主。 */
    Hit click(double mx, double my) {
        if (!inView(mx, my)) return null;
        if (overItemsRow(my)) {
            itemsOpen = !itemsOpen;
            return null;
        }
        for (Rect r : rects) {
            if (mx >= r.x() && mx < r.x() + r.w() && my >= r.y() && my < r.y() + r.h()) return r.hit();
        }
        return null;
    }

    /** 这一下落在页里能点的地方(宿主据此判断点击有没有被吃掉)。 */
    boolean consumes(double mx, double my) {
        if (!inView(mx, my)) return false;
        if (overItemsRow(my)) return true;
        for (Rect r : rects) {
            if (mx >= r.x() && mx < r.x() + r.w() && my >= r.y() && my < r.y() + r.h()) return true;
        }
        return false;
    }

    private boolean inView(double mx, double my) {
        return mx >= viewX && mx < viewX + viewW && my >= viewY && my < viewY + viewH;
    }

    private boolean overItemsRow(double my) {
        return my >= itemsRowY && my < itemsRowY + LINE_ROW_H + 4;
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
        String tip = null;
        int cx = x + w / 2;
        int iw = w - PAD * 2;

        g.enableScissor(x, y, x + w, y + h);
        int cy = y + 10 - Math.round(scroll);

        // ---- 顶部:大头像、名字、此刻在干什么、体征、操作块 ----
        CompanionFace.draw(g, who, KnownSkins.of(who), cx - AVATAR / 2, cy, AVATAR);
        cy += AVATAR + 7;
        Component name = Component.literal(NumenRoster.instance().name(who)).withStyle(ChatFormatting.BOLD);
        Nb.text(g, font, name, cx - font.width(name) / 2, cy, t.text());
        cy += 12;
        // 手上有活就说她在干什么(强调色,这是她此刻最要紧的一句),没有才是在线/忙碌
        String doing = loop != null ? loop.status().activity() : null;
        String st = Nb.clip(font, doing != null ? I18n.get("numen.profile.doing", doing) : status.apply(who), iw);
        Nb.text(g, font, st, cx - font.width(st) / 2, cy, doing != null ? t.accent() : t.textDim());
        cy += 13;
        if (e != null) {
            int food = snap != null && snap.loaded() ? snap.foodLevel() : 0;
            int vx = cx - (10 * ICON * 2 + 8) / 2;
            statRow(g, vx, cy, e.getHealth(), e.getMaxHealth(), HEART_FULL, HEART_HALF, HEART_BG);
            statRow(g, vx + 10 * ICON + 8, cy, food, 20, FOOD_FULL, FOOD_HALF, FOOD_BG);
            cy += ICON + 8;
        }
        int tileW = (iw - 6) / 2;
        tile(g, t, new Hit.Message(), Sprites.MESSAGE, I18n.get("numen.profile.message"), x + PAD, cy, tileW,
                mouseX, mouseY);
        tile(g, t, new Hit.Edit(), Sprites.EDIT, I18n.get(ModLanguageData.Keys.EDIT_TITLE), x + PAD + tileW + 6, cy,
                tileW, mouseX, mouseY);
        cy += TILE_H + 8;

        // ---- 资料:简介、在哪 ----
        cy = gap(g, t, x, w, cy);
        List<FormattedCharSequence> bio = bio(loop, iw - TEXT_DX);
        if (!bio.isEmpty()) {
            Sprites.draw(g, Sprites.PERSONA, x + PAD, cy + 2, Sprites.SIZE, t.textDim());
            int by = cy + 1;
            for (FormattedCharSequence line : bio) {
                Nb.text(g, font, line, x + PAD + TEXT_DX, by);
                by += 10;
            }
            Nb.text(g, font, I18n.get("numen.profile.bio"), x + PAD + TEXT_DX, by, t.faint());
            cy = by + 12;
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
        infoRow(g, t, Sprites.MAP_PIN, where, I18n.get("numen.profile.distance"), x + PAD, cy, iw);
        cy += ROW_H + 4;

        // ---- 她记得的事 ----
        cy = gap(g, t, x, w, cy);
        List<NoteBook.Note> all = notes(who);
        cy = sectionHead(g, t, I18n.get("numen.profile.memories"), all.isEmpty() ? "" : String.valueOf(all.size()),
                x + PAD, cy, iw);
        if (all.isEmpty()) {
            Nb.text(g, font, I18n.get("numen.profile.memories_none"), x + PAD, cy, t.faint());
            cy += 14;
        } else {
            for (int i = 0; i < Math.min(NOTES_SHOWN, all.size()); i++) {
                NoteBook.Note n = all.get(i);
                infoRow(g, t, Sprites.BOOK, n.description(),
                        I18n.get("numen.profile.note_meta", n.day(), n.type()), x + PAD, cy, iw);
                cy += ROW_H;
            }
            if (all.size() > NOTES_SHOWN) {
                Nb.text(g, font, I18n.get("numen.profile.memories_more", all.size() - NOTES_SHOWN),
                        x + PAD + TEXT_DX, cy, t.faint());
                cy += 12;
            }
            cy += 2;
        }

        // ---- 共同群聊:她在的那几个群,点了进去 ----
        List<Conversation> groups = new ArrayList<>();
        for (Conversation c : Conversations.instance().all()) {
            if (Conversations.instance().soloOf(c) == null && c.has(who)) groups.add(c);
        }
        if (!groups.isEmpty()) {
            cy = gap(g, t, x, w, cy);
            cy = sectionHead(g, t, I18n.get("numen.profile.groups"), String.valueOf(groups.size()), x + PAD, cy, iw);
            for (Conversation c : groups) {
                boolean hot = mouseX >= x && mouseX < x + w && mouseY >= cy - 2 && mouseY < cy + 18;
                if (hot) g.fill(x, cy - 2, x + w, cy + 18, t.over());
                com.dwinovo.numen.client.skin.ConversationFaces.draw(g, c, x + PAD - 3, cy - 1, 18);
                Nb.text(g, font, Nb.clip(font, c.displayName(NumenRoster.instance()::name), iw - TEXT_DX),
                        x + PAD + TEXT_DX, cy + 4, t.text());
                rects.add(new Rect(new Hit.Open(c), x, cy - 2, w, 20));
                cy += 20;
            }
            cy += 2;
        }

        // ---- 背包:一行,点开往下展开 ----
        cy = gap(g, t, x, w, cy);
        int count = 0;
        if (snap != null && snap.loaded()) {
            for (ItemStack it : snap.items()) if (!it.isEmpty()) count++;
        }
        itemsRowY = cy - 2;
        boolean hotItems = mouseX >= x && mouseX < x + w && overItemsRow(mouseY);
        if (hotItems) g.fill(x, cy - 2, x + w, cy + LINE_ROW_H + 2, t.over());
        Sprites.draw(g, Sprites.BACKPACK, x + PAD, cy + (LINE_ROW_H - Sprites.SIZE) / 2, Sprites.SIZE, t.textDim());
        Nb.text(g, font, I18n.get("numen.profile.backpack", count), x + PAD + TEXT_DX, cy + 4, t.text());
        String arrow = itemsOpen ? "▾" : "▸";
        Nb.text(g, font, arrow, x + w - PAD - font.width(arrow), cy + 4, t.textDim());
        cy += LINE_ROW_H + 4;
        int gridH = 2 * SLOT + 6 + 3 * SLOT + 4 + SLOT + 8;
        itemsShown = Anim.approach(itemsShown, itemsOpen ? gridH : 0f, 16f, dt);
        if (itemsShown > 0.5f) {
            int shown = Math.round(itemsShown);
            g.enableScissor(x, Math.max(y, cy), x + w, Math.min(y + h, cy + shown));
            drawItems(g, t, snap, e, cx, cy, mouseX, mouseY, hover);
            g.disableScissor();
            cy += shown;
        }

        // ---- 设置:一行一项,左名右值,点了开编辑卡 ----
        cy = gap(g, t, x, w, cy);
        cy = sectionHead(g, t, I18n.get("numen.profile.settings"), "", x + PAD, cy, iw);
        String persona = loop != null && loop.personaName() != null && !loop.personaName().isBlank()
                ? loop.personaName() : I18n.get("numen.profile.persona_default");
        cy = settingRow(g, t, I18n.get("numen.profile.persona"), persona, x, cy, w, mouseX, mouseY);
        String model = I18n.get("numen.profile.model_none");
        if (loop != null && loop.providerEntryId() != null && !loop.providerEntryId().isBlank()) {
            var entry = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().get(loop.providerEntryId());
            model = entry == null ? I18n.get("numen.profile.model_deleted")
                    : entry.model() == null || entry.model().isBlank() ? entry.name() : entry.model();
        }
        cy = settingRow(g, t, I18n.get("numen.profile.model"), model, x, cy, w, mouseX, mouseY);
        var voice = com.dwinovo.numen.client.voice.VoiceLibrary.instance().resolve(who);
        cy = settingRow(g, t, I18n.get("numen.profile.voice"),
                voice != null ? voice.name() : I18n.get("numen.profile.voice_none"), x, cy, w, mouseX, mouseY);
        var conn = mc.getConnection();
        var info = conn == null ? null : conn.getPlayerInfo(who);
        if (info != null) {
            cy = settingRow(g, t, I18n.get("numen.profile.mode"), I18n.get(
                    info.getGameMode() == net.minecraft.world.level.GameType.CREATIVE
                            ? "numen.profile.creative" : "numen.profile.survival"), x, cy, w, mouseX, mouseY);
        }
        if (loop != null) {
            int rowTop = cy;
            cy = settingRow(g, t, I18n.get("numen.profile.context"),
                    I18n.get("numen.profile.context_value", Math.clamp(loop.contextPercent(), 0, 100),
                            loop.display().size()), x, cy, w, mouseX, mouseY);
            if (mouseX >= x && mouseX < x + w && mouseY >= rowTop && mouseY < cy) tip = usageDetail(loop);
        }
        cy += 4;

        // ---- 页底:遣散(红字,点了还要过确认卡) ----
        cy = gap(g, t, x, w, cy);
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= cy - 2 && mouseY < cy + LINE_ROW_H + 2;
        if (hot) g.fill(x, cy - 2, x + w, cy + LINE_ROW_H + 2, t.over());
        Sprites.draw(g, Sprites.DELETE, x + PAD, cy + (LINE_ROW_H - Sprites.SIZE) / 2, Sprites.SIZE, t.fail());
        Nb.text(g, font, I18n.get(ModLanguageData.Keys.EDIT_DISMISS), x + PAD + TEXT_DX, cy + 4, t.fail());
        rects.add(new Rect(new Hit.Dismiss(), x, cy - 2, w, LINE_ROW_H + 4));
        cy += LINE_ROW_H + 12;

        contentH = cy + Math.round(scroll) - y;
        // 滚动条:只有滑块,内容超出一屏才画
        if (contentH > viewH) {
            int th = Math.max(12, viewH * viewH / contentH);
            int ty = y + Math.round((viewH - th) * (scroll / Math.max(1, contentH - viewH)));
            g.fill(x + w - 3, ty, x + w - 1, ty + th, (t.textDim() & 0xFFFFFF) | 0x60000000);
        }
        g.disableScissor();
        if (!hover[0].isEmpty()) g.renderTooltip(font, hover[0], mouseX, mouseY);
        else if (tip != null) g.renderTooltip(font, Component.literal(tip), mouseX, mouseY);
    }

    /** 背包格子:左盔甲 2×2 + 副手,右合成 2×2 → 结果;下面 3×9 背包与快捷栏。 */
    private void drawItems(GuiGraphics g, UiTheme t, ClientNumenState.Snapshot snap, AbstractClientPlayer e,
                           int cx, int cy, int mouseX, int mouseY, ItemStack[] hover) {
        int gx = cx - 9 * SLOT / 2;
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
        int sy0 = cy + 2 * SLOT + 6;
        if (snap == null || !snap.loaded() || snap.items().isEmpty()) {
            String hint = I18n.get(snap == null ? "numen.status.loading" : "numen.status.asleep");
            Nb.text(g, font, hint, gx, sy0 + 4, t.faint());
            return;
        }
        List<ItemStack> items = snap.items();
        for (int i = 9; i < 36; i++) {
            int col = (i - 9) % 9, row = (i - 9) / 9;
            slot(g, t, gx + col * SLOT, sy0 + row * SLOT);
            collect(g, items.get(i), gx + col * SLOT + 1, sy0 + row * SLOT + 1, mouseX, mouseY, hover);
        }
        int hotY = sy0 + 3 * SLOT + 4;
        for (int i = 0; i < 9; i++) {
            slot(g, t, gx + i * SLOT, hotY);
            collect(g, items.get(i), gx + i * SLOT + 1, hotY + 1, mouseX, mouseY, hover);
        }
    }

    /** 她的札记,新的在前;同一本写过几次没变就不重读。 */
    private List<NoteBook.Note> notes(UUID who) {
        NoteBook book = NoteBook.of(who);
        if (!who.equals(notesOf) || book.revision() != notesRevision) {
            notesOf = who;
            notesRevision = book.revision();
            notes = book.index();
        }
        return notes;
    }

    /** 简介:她人设的开头一段(跳过标题行),最多两行,再长截短。没有人设正文是空。 */
    private List<FormattedCharSequence> bio(EntityAgentLoop loop, int width) {
        if (loop == null || loop.personaId() == null) return List.of();
        var persona = PersonaLibrary.instance().get(loop.personaId());
        if (persona == null || persona.text() == null) return List.of();
        StringBuilder para = new StringBuilder();
        for (String line : persona.text().split("\n")) {
            String s = line.strip();
            if (s.startsWith("#")) continue;
            if (s.isEmpty()) {
                if (para.length() > 0) break;
                continue;
            }
            if (para.length() > 0) para.append(' ');
            para.append(s);
        }
        if (para.length() == 0) return List.of();
        int ink = UiTheme.current().text();
        List<FormattedCharSequence> lines = font.split(Nb.colored(para.toString(), ink), width);
        if (lines.size() <= 2) return lines;
        // 超出两行:第二行截短补省略号
        StringBuilder second = new StringBuilder();
        lines.get(1).accept((idx, style, cp) -> {
            second.appendCodePoint(cp);
            return true;
        });
        return List.of(lines.get(0),
                Nb.colored(Nb.clip(font, second + "…", width), ink).getVisualOrderText());
    }

    /** 节头:强调色小字,右端可带一个淡色的数。返回下面内容的顶边。 */
    private int sectionHead(GuiGraphics g, UiTheme t, String title, String count, int x, int y, int w) {
        Nb.text(g, font, title, x, y, t.accent());
        if (!count.isEmpty()) Nb.text(g, font, count, x + w - font.width(count), y, t.faint());
        return y + 14;
    }

    /** 一行设置:左边名字,右边值(淡字);整行点了开编辑卡。返回下一行的顶边。 */
    private int settingRow(GuiGraphics g, UiTheme t, String label, String value, int x, int y, int w,
                           int mouseX, int mouseY) {
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + LINE_ROW_H;
        if (hot) g.fill(x, y, x + w, y + LINE_ROW_H, t.over());
        int ty = y + (LINE_ROW_H - 8) / 2;
        Nb.text(g, font, label, x + PAD, ty, t.text());
        String v = Nb.clip(font, value, w - PAD * 2 - font.width(label) - 12);
        Nb.text(g, font, v, x + w - PAD - font.width(v), ty, t.textDim());
        rects.add(new Rect(new Hit.Edit(), x, y, w, LINE_ROW_H));
        return y + LINE_ROW_H;
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
    private void infoRow(GuiGraphics g, UiTheme t, ResourceLocation icon, String value, String caption,
                         int x, int y, int w) {
        Sprites.draw(g, icon, x, y + (ROW_H - Sprites.SIZE) / 2 - 1, Sprites.SIZE, t.textDim());
        int tx = x + TEXT_DX, room = w - TEXT_DX;
        Nb.text(g, font, Nb.clip(font, value, room), tx, y + 1, t.text());
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
