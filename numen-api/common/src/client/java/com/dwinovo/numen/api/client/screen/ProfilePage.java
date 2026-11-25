package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.mind.LibraryDesk;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.brain.CompanionState;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.brain.OwnerSchedules;
import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.client.agent.ClientCompanions;
import com.dwinovo.numen.api.client.agent.ActivityWords;
import com.dwinovo.numen.api.client.agent.ClientNumenLookup;
import com.dwinovo.numen.api.client.agent.Conversations;
import com.dwinovo.numen.api.client.agent.CompanionView;
import com.dwinovo.numen.api.client.agent.KnownSkins;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.client.data.ClientNumenState;
import com.dwinovo.numen.api.client.skin.CompanionFace;
import com.dwinovo.numen.api.client.ui.Anim;
import com.dwinovo.numen.api.client.ui.mc.Sprites;
import com.dwinovo.numen.api.client.ui.widget.DialogBox;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.entity.Keeper;
import com.dwinovo.numen.api.client.agent.ClientLibraries;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 同伴资料页,照 Telegram 资料页的想法排:先是"这个人",再是"你们之间的东西",配置收在后面。整页可滚。
 * <ol>
 *   <li>顶部居中:大头像、名字、此刻在干什么(没在干活才说在线/忙碌),分享来的她再一行"来自 某某",体征,一排操作块(发消息、编辑);</li>
 *   <li>资料:简介(她人设的开头一段)、在你哪边多远;</li>
 *   <li>记忆:最新几条札记(Telegram 的共享媒体那一节,我们这里最有意思的是她记住了什么);</li>
 *   <li>定时:主人给她定的话(每隔多久对她说一句),点开编辑,最后一行新增;</li>
 *   <li>共同群聊:她在哪几个群里,点了进去;</li>
 *   <li>主人:她的主人表(创建者在前),一行一位;创建者在这里加人(最后一行)、移人(行尾的图标),别的主人只看;</li>
 *   <li>背包:收成一行"背包 · N 件",点开往下展开格子;</li>
 *   <li>设置:人设、模型、声线、游戏模式、上下文,一行一项、左名右值,点了开编辑卡;另一位主人绑的那几项标着"某某的";</li>
 *   <li>页底红字:创建者是删除,被分享的人是退出。</li>
 * </ol>
 * 只有创建者办得成的事(删除、清空记忆、加人移人)别的主人这里不摆入口;能不能办仍由服务端判。
 * 节与节之间隔一道深色宽缝(Telegram 的分节),节头是强调色小字。
 */
final class ProfilePage {

    /** 点中了什么:发消息、编辑、遣散、进某个群。 */
    sealed interface Hit {
        record Message() implements Hit {}
        record Edit() implements Hit {}
        record Dismiss() implements Hit {}
        record Export() implements Hit {}
        record Wipe() implements Hit {}
        /** 创建者把她分享给一位玩家(开分享卡)。 */
        record Share() implements Hit {}
        /** 创建者不再把她分享给这一位。 */
        record Unshare(Keeper keeper) implements Hit {}
        /** 被分享的人退出。 */
        record Leave() implements Hit {}
        /** 点开一条札记编辑。 */
        record Note(String name) implements Hit {}
        /** 新增一条札记。 */
        record NewNote() implements Hit {}
        /** 点开一条定时编辑。 */
        record Schedule(String id) implements Hit {}
        /** 新增一条定时。 */
        record NewSchedule() implements Hit {}
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
    /** 主人表一行的高与行里那张脸的边长(8 的整数倍)。 */
    private static final int KEEPER_ROW_H = 20, KEEPER_AV = 16;
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
    /** 背包那一节开着没有,和它展开到多高(像素,按趋近走)。 */
    private boolean itemsOpen;
    private float itemsShown;
    /** 这一帧可点的几块在哪。 */
    private record Rect(Hit hit, int x, int y, int w, int h) {}
    private final List<Rect> rects = new ArrayList<>();
    private int viewX, viewY, viewW;
    /** 背包那一行这一帧的顶边;点它是页内开合。 */
    private int itemsRowY = Integer.MIN_VALUE;
    /** 主人表那一节画着的行:表上的,加上刚被移出、还在收起的;与各行此刻露出多高(像素,按趋近走)。 */
    private final List<Keeper> keeperRows = new ArrayList<>();
    private final Map<UUID, Float> keeperShown = new HashMap<>();
    /** 这一页对着这只画过主人表没有:头一帧表上的行直接摆到位,之后新加的才从零长出来。 */
    private boolean keepersSettled;
    /** 这一帧指针下那处要说的一句(主人表行尾的图标、上下文那一行);没有是 null。 */
    private String tip;
    /** 上次向服务端要她的札记与定时的时刻:面板开着时隔几秒要一次最新的。 */
    private long askedMs;
    private static final long REFRESH_MS = 5_000L;

    ProfilePage(Font font) {
        this.font = font;
    }

    /** 换了人:回到顶上,背包收起。 */
    void reset() {
        scroll = 0;
        scrollTarget = 0;
        itemsOpen = false;
        itemsShown = 0;
        memoryPending = false;
        keeperRows.clear();
        keeperShown.clear();
        keepersSettled = false;
    }

    /** 记忆那一节(札记)顶边在整页内容里的位置(像素,从页顶起);画过一帧才有。 */
    private int memoryTop = -1;
    /** 有人要看记忆那一节:这页画出它的位置后滚过去(按趋近走,不硬切)。 */
    private boolean memoryPending;

    /** 滚到记忆那一节({@code /memory} 把主人带到这里)。 */
    void showMemory() {
        memoryPending = true;
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
        CompanionView loop = ClientCompanions.get(who).orElse(null);
        ItemStack[] hover = {ItemStack.EMPTY};
        tip = null;
        // 她的主人表:创建者是不是我,决定只有创建者办得成的那几个入口摆不摆
        NumenRoster.Entry entry = NumenRoster.instance().entry(who);
        UUID me = ClientCompanions.viewer();
        Keeper creator = entry == null ? null : entry.creator();
        boolean mine = entry != null && entry.createdBy(me);
        boolean shared = entry != null && entry.sharedWith(me);
        int cx = x + w / 2;
        int iw = w - PAD * 2;

        g.enableScissor(x, y, x + w, y + h);
        int cy = y + 10 - Math.round(scroll);

        // ---- 顶部:大头像、名字、此刻在干什么、体征、操作块 ----
        CompanionFace.draw(g, who, KnownSkins.of(who), cx - AVATAR / 2, cy, AVATAR);
        cy += AVATAR + 7;
        // 她刚从名册上没了(不再分享给我、被删了)的那几帧,这一页还在滑出去:名字空着,别的照画
        Component name = Component.literal(entry == null ? "" : entry.name()).withStyle(ChatFormatting.BOLD);
        Nb.text(g, font, name, cx - font.width(name) / 2, cy, t.text());
        cy += 12;
        // 手上有活就说她在干什么(强调色,这是她此刻最要紧的一句),没有才是在线/忙碌
        var activity = loop != null ? loop.status().activity() : null;
        String doing = activity == null ? null : ActivityWords.text(activity);
        String st = Nb.clip(font, doing != null ? I18n.get("numen.profile.doing", doing) : status.apply(who), iw);
        Nb.text(g, font, st, cx - font.width(st) / 2, cy, doing != null ? t.accent() : t.textDim());
        cy += 13;
        if (shared) {
            // 分享来的她:是谁创建的
            String from = Nb.clip(font, I18n.get(ModLanguageData.Keys.SHARE_FROM, creator.shown()), iw - Sprites.SIZE - 4);
            int fx = cx - (Sprites.SIZE + 4 + font.width(from)) / 2;
            Sprites.draw(g, Sprites.USERS, fx, cy - 2, Sprites.SIZE, t.accent());
            Nb.text(g, font, from, fx + Sprites.SIZE + 4, cy, t.accent());
            cy += 15;
        }
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
            Nb.text(g, font, I18n.get("numen.profile.bio"), x + PAD + TEXT_DX, by, t.textDim());
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

        // ---- 记忆:全部札记,点开编辑;最后一行新增 ----
        cy = gap(g, t, x, w, cy);
        memoryTop = cy + Math.round(scroll) - y;
        if (memoryPending && contentH > 0) {
            scrollTarget = Math.clamp((float) (memoryTop - 4), 0f, Math.max(0, contentH - viewH));
            memoryPending = false;
        }
        refreshTables();
        List<ClientLibraries.Note> all = ClientLibraries.notes(who);
        cy = sectionHead(g, t, I18n.get("numen.profile.memories"), all.isEmpty() ? "" : String.valueOf(all.size()),
                x + PAD, cy, iw);
        if (all.isEmpty()) {
            Nb.text(g, font, I18n.get("numen.profile.memories_none"), x + PAD, cy, t.textDim());
            cy += 14;
        } else {
            for (ClientLibraries.Note n : all) {
                boolean hot = mouseX >= x && mouseX < x + w && mouseY >= cy - 2 && mouseY < cy + ROW_H - 2;
                if (hot) g.fill(x, cy - 2, x + w, cy + ROW_H - 2, t.over());
                infoRow(g, t, Sprites.BOOK, n.description(),
                        I18n.get("numen.profile.note_meta", n.day(), n.type()), x + PAD, cy, iw);
                rects.add(new Rect(new Hit.Note(n.name()), x, cy - 2, w, ROW_H));
                cy += ROW_H;
            }
            cy += 2;
        }
        cy = actionRow(g, t, new Hit.NewNote(), Sprites.EDIT, I18n.get(ModLanguageData.Keys.NOTE_ADD), t.accent(),
                x, cy, w, mouseX, mouseY);

        // ---- 定时:到点她听到主人的这句话;点开编辑,最后一行新增 ----
        cy = gap(g, t, x, w, cy);
        List<ClientLibraries.Schedule> timed = ClientLibraries.schedules(who);
        cy = sectionHead(g, t, I18n.get(ModLanguageData.Keys.SCHEDULE_SECTION),
                timed.isEmpty() ? "" : String.valueOf(timed.size()), x + PAD, cy, iw);
        if (timed.isEmpty()) {
            for (var line : font.split(Component.translatable(ModLanguageData.Keys.SCHEDULE_NONE), iw)) {
                g.drawString(font, line, x + PAD, cy, t.textDim(), false);
                cy += font.lineHeight + 1;
            }
            cy += 3;
        } else {
            for (ClientLibraries.Schedule s : timed) {
                boolean hot = mouseX >= x && mouseX < x + w && mouseY >= cy - 2 && mouseY < cy + ROW_H - 2;
                if (hot) g.fill(x, cy - 2, x + w, cy + ROW_H - 2, t.over());
                infoRow(g, t, Sprites.MESSAGE, s.text(), every(s.every()), x + PAD, cy, iw);
                rects.add(new Rect(new Hit.Schedule(s.id()), x, cy - 2, w, ROW_H));
                cy += ROW_H;
            }
            cy += 2;
        }
        cy = actionRow(g, t, new Hit.NewSchedule(), Sprites.EDIT, I18n.get(ModLanguageData.Keys.SCHEDULE_ADD),
                t.accent(), x, cy, w, mouseX, mouseY);

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
                com.dwinovo.numen.api.client.skin.ConversationFaces.draw(g, c, x + PAD - 3, cy - 1, 18);
                Nb.text(g, font, Nb.clip(font, c.displayName(NumenRoster.instance()::name), iw - TEXT_DX),
                        x + PAD + TEXT_DX, cy + 4, t.text());
                rects.add(new Rect(new Hit.Open(c), x, cy - 2, w, 20));
                cy += 20;
            }
            cy += 2;
        }

        // ---- 主人:她的主人表;创建者在这里加人、移人 ----
        if (entry != null && creator != null) {
            cy = gap(g, t, x, w, cy);
            cy = keepers(g, t, entry, me, mine, x, cy, w, mouseX, mouseY, dt);
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
        // 三项绑定各写它是哪一条;另一位主人绑的标"某某的"——那一条在他的库里,我这边看不到的只写得出名字
        CompanionState.Binding personaBound = loop == null ? null : loop.binding(Slot.PERSONA);
        String persona = personaBound == null ? I18n.get("numen.profile.persona_default")
                : personaBound.bound().by().equals(me) ? personaBound.id()
                : boundBy(personaBound, personaBound.bound().id().name(), me);
        cy = settingRow(g, t, I18n.get("numen.profile.persona"), persona, x, cy, w, mouseX, mouseY);
        CompanionState.Binding modelBound = loop == null ? null : loop.binding(Slot.PROVIDER);
        String model = I18n.get("numen.profile.model_none");
        if (modelBound != null) {
            var seen = ClientLibraries.provider(modelBound.bound(), me);
            model = boundBy(modelBound, seen != null ? seen.model() == null || seen.model().isBlank() ? seen.name() : seen.model()
                    : modelBound.bound().by().equals(me) ? I18n.get("numen.profile.model_deleted")
                    : modelBound.bound().id().name(), me);
        }
        cy = settingRow(g, t, I18n.get("numen.profile.model"), model, x, cy, w, mouseX, mouseY);
        CompanionState.Binding voiceBound = loop == null ? null : loop.binding(Slot.VOICE);
        String voice = I18n.get("numen.profile.voice_none");
        if (voiceBound != null) {
            var seen = ClientLibraries.voice(voiceBound.bound(), me);
            if (seen != null) {
                voice = boundBy(voiceBound, seen.name(), me);
            } else if (!voiceBound.bound().by().equals(me)) {
                // 他"我的"里的声线:这台电脑看不到,不念;写明白,免得当成坏了
                voice = I18n.get(ModLanguageData.Keys.PROFILE_VOICE_UNHEARD,
                        boundBy(voiceBound, voiceBound.bound().id().name(), me));
            }
        }
        cy = settingRow(g, t, I18n.get("numen.profile.voice"), voice, x, cy, w, mouseX, mouseY);
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
                            loop.messageCount()), x, cy, w, mouseX, mouseY);
            if (mouseX >= x && mouseX < x + w && mouseY >= rowTop && mouseY < cy) tip = usageDetail(loop);
        }
        cy += 4;

        // ---- 记忆:存在这个服务器上(服主看得到),主人可以整份导出、可以清空(点了还要过确认卡) ----
        cy = gap(g, t, x, w, cy);
        cy = sectionHead(g, t, I18n.get(ModLanguageData.Keys.PROFILE_MEMORY), "", x + PAD, cy, iw);
        for (var line : font.split(Component.translatable(ModLanguageData.Keys.PROFILE_MEMORY_HERE), iw)) {
            g.drawString(font, line, x + PAD, cy, t.textDim(), false);
            cy += font.lineHeight + 1;
        }
        cy += 3;
        cy = actionRow(g, t, new Hit.Export(), Sprites.COPY, I18n.get(ModLanguageData.Keys.PROFILE_EXPORT), t.accent(),
                x, cy, w, mouseX, mouseY);
        if (mine) {
            cy = actionRow(g, t, new Hit.Wipe(), Sprites.DELETE, I18n.get(ModLanguageData.Keys.PROFILE_WIPE), t.fail(),
                    x, cy, w, mouseX, mouseY);
        }

        // ---- 页底(红字,点了还要过确认卡):创建者是删除她,被分享的人是退出 ----
        if (mine || shared) {
            cy = gap(g, t, x, w, cy);
            cy = mine ? actionRow(g, t, new Hit.Dismiss(), Sprites.DELETE, I18n.get(ModLanguageData.Keys.EDIT_DELETE),
                    t.fail(), x, cy, w, mouseX, mouseY)
                    : actionRow(g, t, new Hit.Leave(), Sprites.USER_X, I18n.get(ModLanguageData.Keys.SHARE_LEAVE),
                    t.fail(), x, cy, w, mouseX, mouseY);
        }
        cy += 8;

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
    private void drawItems(GuiGraphics g, UiTheme t, com.dwinovo.numen.api.agent.request.BodySnapshot snap, AbstractClientPlayer e,
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
        Nb.text(g, font, "→", crx + 2 * SLOT + 3, midY + 5, t.textDim());
        slot(g, t, resX, midY);
        collect(g, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY, resX + 1, midY + 1, mouseX, mouseY, hover);
        int sy0 = cy + 2 * SLOT + 6;
        if (snap == null || !snap.loaded() || snap.items().isEmpty()) {
            String hint = I18n.get(snap == null ? "numen.status.loading" : "numen.status.asleep");
            Nb.text(g, font, hint, gx, sy0 + 4, t.textDim());
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

    /** 间隔用玩家看得懂的单位写:整日的按游戏日,否则按游戏小时(1000 刻)。 */
    private static String every(long ticks) {
        return ticks % OwnerSchedules.DAY_TICKS == 0
                ? I18n.get(ModLanguageData.Keys.SCHEDULE_EVERY_DAYS, ticks / OwnerSchedules.DAY_TICKS)
                : I18n.get(ModLanguageData.Keys.SCHEDULE_EVERY_HOURS, Math.max(1, Math.round(ticks / 1000f)));
    }

    /** 札记与定时的表:服务端那一份才算数,面板开着时隔几秒问一次。 */
    private void refreshTables() {
        long now = System.currentTimeMillis();
        if (now - askedMs > REFRESH_MS) {
            askedMs = now;
            ClientCompanions.library().ask(LibraryDesk.NOTES);
            ClientCompanions.library().ask(LibraryDesk.SCHEDULES);
        }
    }

    /** 简介:她人设的开头一段(跳过标题行),最多两行,再长截短。没有人设正文是空。 */
    private List<FormattedCharSequence> bio(CompanionView loop, int width) {
        CompanionState.Binding bound = loop == null ? null : loop.binding(Slot.PERSONA);
        if (bound == null) return List.of();
        var persona = ClientLibraries.persona(bound.bound(), ClientCompanions.viewer());
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

    /** 一项绑定在设置行上怎么写:条目的名字;另一位主人绑的再标上"某某的"。 */
    private static String boundBy(CompanionState.Binding binding, String name, UUID me) {
        return binding.bound().by().equals(me) ? name
                : I18n.get(ModLanguageData.Keys.SETUP_BOUND_BY, name, binding.by().shown());
    }

    /**
     * 主人表那一节:一行一位(脸、名字,创建者与"你"各标一枚),创建者看别人的行尾有一枚移出的图标(点了过确认卡),最后一行加人。
     * 表变了行不硬切:新加的从零长出来、淡入,被移出的收回去、淡出。返回下一节的顶边。
     */
    private int keepers(GuiGraphics g, UiTheme t, NumenRoster.Entry entry, UUID me, boolean mine,
                        int x, int cy, int w, int mouseX, int mouseY, float dt) {
        List<Keeper> table = entry.keepers();
        cy = sectionHead(g, t, I18n.get(ModLanguageData.Keys.SHARE_OWNERS),
                table.size() > 1 ? String.valueOf(table.size()) : "", x + PAD, cy, w - PAD * 2);
        // 表上新来的接在后面(服务端按加进来的先后给);已经画着的换成表上最新的那份(名字可能刚查到)
        for (Keeper keeper : table) {
            int at = rowOf(keeper.uuid());
            if (at >= 0) {
                keeperRows.set(at, keeper);
            } else {
                keeperRows.add(keeper);
                keeperShown.put(keeper.uuid(), keepersSettled ? 0f : (float) KEEPER_ROW_H);
            }
        }
        keepersSettled = true;
        for (Iterator<Keeper> rows = keeperRows.iterator(); rows.hasNext(); ) {
            Keeper keeper = rows.next();
            boolean on = table.contains(keeper);
            float shown = Anim.approach(keeperShown.get(keeper.uuid()), on ? KEEPER_ROW_H : 0f, 16f, dt);
            if (!on && shown <= 0f) {
                rows.remove();
                keeperShown.remove(keeper.uuid());
                continue;
            }
            keeperShown.put(keeper.uuid(), shown);
            int rowH = Math.round(shown);
            if (rowH > 0) {
                g.enableScissor(x, cy, x + w, cy + rowH);
                // 字体把几乎全透明的颜色当不透明画,所以淡到底也留一点
                keeperRow(g, t, keeper, entry.createdBy(keeper.uuid()), keeper.uuid().equals(me),
                        mine && on && shown >= KEEPER_ROW_H, x, cy, w, Math.max(0.05f, shown / KEEPER_ROW_H),
                        mouseX, mouseY);
                g.disableScissor();
                cy += rowH;
            }
        }
        cy += 2;
        if (mine) {
            cy = actionRow(g, t, new Hit.Share(), Sprites.USER_PLUS, I18n.get(ModLanguageData.Keys.SHARE_ADD), t.accent(),
                    x, cy, w, mouseX, mouseY);
        }
        return cy;
    }

    private int rowOf(UUID keeper) {
        for (int i = 0; i < keeperRows.size(); i++) {
            if (keeperRows.get(i).uuid().equals(keeper)) return i;
        }
        return -1;
    }

    /**
     * 主人表的一行,整行按 {@code alpha} 淡。{@code removable} = 这一行尾上摆移出的图标:看的人是创建者、这一行不是他自己、行已经长满。
     */
    private void keeperRow(GuiGraphics g, UiTheme t, Keeper keeper, boolean creator, boolean self, boolean removable,
                           int x, int y, int w, float alpha, int mouseX, int mouseY) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.setColor(1f, 1f, 1f, alpha);
        PlayerFaceRenderer.draw(g, KnownSkins.of(keeper.uuid()), x + PAD - 2, y + (KEEPER_ROW_H - KEEPER_AV) / 2, KEEPER_AV);
        g.setColor(1f, 1f, 1f, 1f);
        int ty = y + (KEEPER_ROW_H - 8) / 2;
        int tx = x + PAD + TEXT_DX;
        int right = x + w - PAD - (removable && !creator ? Sprites.SIZE + 6 : 0);
        String role = creator ? I18n.get(ModLanguageData.Keys.SHARE_CREATOR) : "";
        String you = self ? I18n.get(ModLanguageData.Keys.SHARE_YOU) : "";
        int tags = (role.isEmpty() ? 0 : font.width(role) + 6) + (you.isEmpty() ? 0 : font.width(you) + 6);
        String name = Nb.clip(font, keeper.shown(), right - tx - tags);
        Nb.text(g, font, name, tx, ty, DialogBox.fade(t.text(), alpha));
        tx += font.width(name) + 6;
        if (!role.isEmpty()) {
            Nb.text(g, font, role, tx, ty, DialogBox.fade(t.accent(), alpha));
            tx += font.width(role) + 6;
        }
        if (!you.isEmpty()) {
            Nb.text(g, font, you, tx, ty, DialogBox.fade(t.textDim(), alpha));
        }
        if (removable && !creator) {
            int bx = x + w - PAD - Sprites.SIZE, by = y + (KEEPER_ROW_H - Sprites.SIZE) / 2;
            boolean hot = mouseX >= bx - 3 && mouseX < bx + Sprites.SIZE + 3 && mouseY >= by - 3 && mouseY < by + Sprites.SIZE + 3;
            Sprites.draw(g, Sprites.USER_X, bx, by, Sprites.SIZE, hot ? t.fail() : t.textDim());
            rects.add(new Rect(new Hit.Unshare(keeper), bx - 3, by - 3, Sprites.SIZE + 6, Sprites.SIZE + 6));
            if (hot) tip = I18n.get(ModLanguageData.Keys.UNSHARE_TIP, keeper.shown());
        }
    }

    /** 节头:强调色小字,右端可带一个淡色的数。返回下面内容的顶边。 */
    private int sectionHead(GuiGraphics g, UiTheme t, String title, String count, int x, int y, int w) {
        Nb.text(g, font, title, x, y, t.accent());
        if (!count.isEmpty()) Nb.text(g, font, count, x + w - font.width(count), y, t.textDim());
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

    /** 页里一行可点的操作:图标加字,悬停浅底;返回下一行的顶边。 */
    private int actionRow(GuiGraphics g, UiTheme t, Hit hit, ResourceLocation icon, String label, int color,
                          int x, int y, int w, int mouseX, int mouseY) {
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y - 2 && mouseY < y + LINE_ROW_H + 2;
        if (hot) g.fill(x, y - 2, x + w, y + LINE_ROW_H + 2, t.over());
        Sprites.draw(g, icon, x + PAD, y + (LINE_ROW_H - Sprites.SIZE) / 2, Sprites.SIZE, color);
        Nb.text(g, font, label, x + PAD + TEXT_DX, y + 4, color);
        rects.add(new Rect(hit, x, y - 2, w, LINE_ROW_H + 4));
        return y + LINE_ROW_H + 4;
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
        Nb.text(g, font, Nb.clip(font, caption, room), tx, y + 11, t.textDim());
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
     * 用量明细:{@code ↑输入 ↓输出 缓存读 缓存写 命中率 占用/窗口}(缓存三段的前缀是语言键,英文是 R / W / CH)。每段有值才出现——服务商不报缓存
     * 的话那三段自然消失。它是给想知道的人看的,住在上下文那一行的悬停提示里,不常驻。
     * 命中率只看<b>最近一轮</b>:累计命中率会被历史稀释,看不出"刚才那轮把缓存打穿了"。
     */
    private static String usageDetail(CompanionView lp) {
        var sum = lp.usageTotals();
        List<String> parts = new ArrayList<>();
        if (sum.input() > 0) parts.add("↑" + com.dwinovo.numen.api.client.ui.TokenFormat.tokens(sum.input()));
        if (sum.output() > 0) parts.add("↓" + com.dwinovo.numen.api.client.ui.TokenFormat.tokens(sum.output()));
        if (sum.cacheRead() > 0) parts.add(I18n.get("numen.profile.usage_read") + com.dwinovo.numen.api.client.ui.TokenFormat.tokens(sum.cacheRead()));
        if (sum.cacheWrite() > 0) parts.add(I18n.get("numen.profile.usage_write") + com.dwinovo.numen.api.client.ui.TokenFormat.tokens(sum.cacheWrite()));
        double hit = lp.lastUsage().cacheHitRate();
        if (sum.reportsCache() && hit >= 0) {
            parts.add(I18n.get("numen.profile.usage_hit") + com.dwinovo.numen.api.client.ui.TokenFormat.percent1(hit) + "%");
        }
        parts.add(lp.contextPercent() + "%/" + com.dwinovo.numen.api.client.ui.TokenFormat.tokens(lp.modelWindow()));
        return String.join(" ", parts);
    }
}
