package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.skin.FolderOpener;
import com.dwinovo.numen.api.client.skin.SkinChoices;
import com.dwinovo.numen.api.client.skin.SkinTextures;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;
import com.dwinovo.numen.api.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.DialogBox;
import com.dwinovo.numen.api.client.ui.widget.Dropdown;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.skin.MineSkinClient;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinRef;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 选皮肤卡:皮肤就是文件夹里的 png,文件名就是皮肤名。按文件名列出,带小脸,分"我的"(这台机器的皮肤文件夹)与"服务器提供"
 * (服务器的;房主两个文件夹是同一个,只有"我的"一节)两节。列表上方有"打开文件夹"(系统文件管理器;手机上打不开,就显示路径请玩家
 * 用手机自带的文件管理器放图)和"刷新",文件夹空着给一句说明。选一张,手臂粗细按图自动判断({@code SkinVariants}),玩家可以改;
 * "穿上"之后她的皮肤由服务端用时才签({@code Wardrobe}):这里只显示"正在准备皮肤…"和失败的原因,签名这回事玩家看不见。
 * 管理员另有一行 MineSkin 的 API key(服务器钥匙串里的,提高签名额度)。
 *
 * <p>脸是 MC 独有的东西,在这一层直接画;几何与状态色走 NumenStyle。
 */
public final class SkinPickerPanel extends ModalCard {

    /** 屏幕侧的面:对着哪只、关卡。 */
    public interface Host {
        UUID uuid();

        String name();

        void onClose();
    }

    /** 列表里的一行:节标题、说明、"默认皮肤"、一张图。 */
    private sealed interface Row {
        record Heading(String text) implements Row {}

        record Note(String text) implements Row {}

        record Default() implements Row {}

        record Item(SkinChoices.Choice choice) implements Row {}
    }

    private static final int ROW_H = 20;
    private static final int HEADING_H = 13;
    private static final int NOTE_H = 14;
    private static final int FACE = 16;
    private static final int LIST_H = 120;
    private static final int LINE_H = 11;
    private static final List<String> ARMS = List.of(SkinProfile.VARIANT_CLASSIC, SkinProfile.VARIANT_SLIM);

    private final Host host;
    private List<Row> rows = List.of();
    /** 选中的那一行在 {@link #rows} 里的位置;没选是 -1。 */
    private int selected = -1;
    private int scrollPx;
    private String arm = SkinProfile.VARIANT_CLASSIC;
    private String mineSkinKey = "";
    /** 刚发出去的"穿上",等她身上的皮肤变成这一张;{@code null} 是没在等。 */
    private SkinRef expected;
    private boolean takingOff;
    private String problem;

    private Button wear;
    private Dropdown armPick;
    private int listX, listY, listW;
    private int statusY;

    public SkinPickerPanel(Host host) {
        this.host = host;
    }

    @Override
    void reset() {
        selected = -1;
        scrollPx = 0;
        expected = null;
        takingOff = false;
        problem = null;
        mineSkinKey = storedKey();
        ask();
        refresh();
        // 上一回没成的留在服务端的队里,这一回不再带着:看过了就撤掉
        for (ServerLibraries.SkinJob job : ServerLibraries.skinJobs()) {
            if (job.failed()) {
                cancel(job.job());
            }
        }
    }

    /** 向服务端要它的皮肤文件夹此刻的样子,和我在队里的几行。 */
    private static void ask() {
        RemoteCompanions.library().ask(Libraries.SKIN_FILES);
        RemoteCompanions.library().ask(Libraries.SKIN);
    }

    /** 重列两个文件夹。 */
    private void refresh() {
        List<Row> next = new ArrayList<>();
        next.add(new Row.Default());
        next.add(new Row.Heading(t(ModLanguageData.Keys.LIBRARY_MINE)));
        List<SkinChoices.Choice> mine = SkinChoices.mine();
        if (mine.isEmpty()) {
            next.add(new Row.Note(t(ModLanguageData.Keys.SKIN_FOLDER_EMPTY)));
        }
        mine.forEach(c -> next.add(new Row.Item(c)));
        List<SkinChoices.Choice> server = SkinChoices.server();
        if (!server.isEmpty()) {
            next.add(new Row.Heading(t(ModLanguageData.Keys.LIBRARY_SERVER)));
            server.forEach(c -> next.add(new Row.Item(c)));
        }
        SkinRef worn = worn();
        int keep = selected >= 0 && selected < rows.size() ? selected : -1;
        rows = next;
        selected = -1;
        for (int i = 0; i < rows.size() && keep < 0; i++) {
            if (rows.get(i) instanceof Row.Item item && worn != null && item.choice().usable()
                    && item.choice().hash().equals(worn.hash())) {
                keep = i;
            }
        }
        select(keep);
    }

    private void select(int index) {
        selected = index >= 0 && index < rows.size() && selectable(rows.get(index)) ? index : -1;
        if (selected >= 0 && rows.get(selected) instanceof Row.Item item) {
            arm = item.choice().variant();
            SkinRef worn = worn();
            if (worn != null && worn.hash().equals(item.choice().hash())) {
                arm = worn.variant();   // 穿着的这张,粗细以她穿着的为准
            }
        }
        if (armPick != null) {
            armPick.select(Math.max(0, ARMS.indexOf(arm)));
        }
        problem = null;
    }

    private static boolean selectable(Row row) {
        return row instanceof Row.Default || row instanceof Row.Item item && item.choice().usable();
    }

    /** 她现在穿着的(缓存里的名字);没穿是 {@code null}。 */
    private SkinRef worn() {
        return SkinRef.parse(RemoteCompanions.getOrCreate(host.uuid()).skinId());
    }

    private boolean admin() {
        return RemoteCompanions.admin();
    }

    /** 服务器钥匙串里 MineSkin 的 key;房主的钥匙就是服务器那一本,在他自己的那一份里。 */
    private static String storedKey() {
        return RemoteCompanions.host() ? ServerLibraries.mineKey(MineSkinClient.KEY_SERVICE)
                : ServerLibraries.serverKey(MineSkinClient.KEY_SERVICE);
    }

    // ---- 版式 ----

    private boolean pathShown() {
        return !FolderOpener.available();
    }

    private int headH() {
        return 16 + 2 + LINE_H + (pathShown() ? LINE_H : 0);
    }

    @Override
    int height() {
        return heightFor(headH() + LIST_H + 4 + ROW_H + (admin() ? FIELD_H + 2 : 0) + LINE_H + 2);
    }

    @Override
    protected void layout(int top) {
        title(I18n.get(ModLanguageData.Keys.SKIN_PICKER_TITLE, host.name()));
        int px = x + DialogBox.PAD_X;
        int pw = w - DialogBox.PAD_X * 2;
        int ry = top;
        var font = net.minecraft.client.Minecraft.getInstance().font;
        String openLabel = t(ModLanguageData.Keys.SKIN_OPEN_FOLDER);
        String refreshLabel = t(ModLanguageData.Keys.SKIN_REFRESH);
        if (FolderOpener.available()) {
            Button open = ui.add(new Button(openLabel, Button.Style.LINK, () -> FolderOpener.open(SkinFolder.here().dir())));
            open.setBounds(px - 4, ry, font.width(openLabel) + 8, 16);
        }
        Button again = ui.add(new Button(refreshLabel, Button.Style.LINK, this::reload));
        int aw = font.width(refreshLabel) + 8;
        again.setBounds(px + pw - aw + 4, ry, aw, 16);
        ry += 18;
        Label where = ui.add(new Label(I18n.get(ModLanguageData.Keys.SKIN_FOLDER_AT, shortPath()), Label.Role.SECONDARY));
        where.setBounds(px, ry, pw, 9);
        ry += LINE_H;
        if (pathShown()) {
            Label hint = ui.add(new Label(t(ModLanguageData.Keys.SKIN_FOLDER_PHONE), Label.Role.SECONDARY));
            hint.setBounds(px, ry, pw, 9);
            ry += LINE_H;
        }
        listX = px;
        listY = ry;
        listW = pw;
        ry += LIST_H + 4;
        armPick = select(ry, t(ModLanguageData.Keys.SKIN_ARM), List.of(t(ModLanguageData.Keys.SKIN_VARIANT_CLASSIC),
                t(ModLanguageData.Keys.SKIN_VARIANT_SLIM)), Math.max(0, ARMS.indexOf(arm)),
                i -> arm = ARMS.get(Math.clamp(i, 0, ARMS.size() - 1)));
        ry += ROW_H;
        if (admin()) {
            field(px, ry, pw, t(ModLanguageData.Keys.SKIN_MINESKIN_KEY), new TextField(mineSkinKey, v -> mineSkinKey = v)
                    .masked(true).placeholder(t(ModLanguageData.Keys.SKIN_MINESKIN_KEY_HINT)));
            ry += FIELD_H + 2;
        }
        statusY = ry;
        wear = buttons(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onClose,
                t(ModLanguageData.Keys.SKIN_WEAR), this::wear);
    }

    /** 路径只写到游戏目录往下,不把玩家的用户名与盘符摆在界面上。 */
    private static String shortPath() {
        return "config/numen/skins";
    }

    private void reload() {
        ask();
        refresh();
        scrollPx = 0;
    }

    // ---- 办事 ----

    private void wear() {
        if (selected < 0 || waiting()) {
            return;
        }
        if (admin()) {
            ServerLibraries.saveKey(MineSkinClient.KEY_SERVICE, mineSkinKey, !RemoteCompanions.host());
        }
        JsonObject body = new JsonObject();
        body.addProperty("companion", host.uuid().toString());
        if (rows.get(selected) instanceof Row.Default) {
            takingOff = true;
            RemoteCompanions.library().request(Libraries.SKIN, Libraries.DELETE, body, this::answered);
            return;
        }
        SkinChoices.Choice choice = ((Row.Item) rows.get(selected)).choice();
        body.addProperty("name", choice.name());
        body.addProperty("variant", arm);
        if (choice.where() == SkinChoices.Where.MINE) {
            body.addProperty("png", java.util.Base64.getEncoder().encodeToString(choice.png()));
        } else {
            body.addProperty("file", choice.name());
        }
        expected = choice.ref(arm);
        problem = null;
        RemoteCompanions.library().request(Libraries.SKIN, Libraries.PUT, body, this::answered);
    }

    /** 服务端答了这一次请求:没办成就把原因摆出来。 */
    private void answered(com.dwinovo.numen.api.client.agent.RemoteLibrary.Reply reply) {
        if (!reply.ok()) {
            expected = null;
            takingOff = false;
            problem = reply.problem();
        }
    }

    private static void cancel(long job) {
        JsonObject body = new JsonObject();
        body.addProperty("job", job);
        RemoteCompanions.library().request(Libraries.SKIN, Libraries.CANCEL, body);
    }

    /** 等着她穿上(或脱掉)。 */
    private boolean waiting() {
        return expected != null || takingOff;
    }

    /** 这一刻该画在状态行上的话和颜色;没什么可说是 {@code null}。 */
    private String status(NumenTheme.Colors c, int[] color) {
        for (ServerLibraries.SkinJob job : ServerLibraries.skinJobs()) {
            if (job.failed()) {
                if (waiting()) {
                    expected = null;
                    takingOff = false;
                }
                color[0] = c.danger();
                return I18n.get(ModLanguageData.Keys.SKIN_FAILED, job.failure().message().getString());
            }
        }
        if (problem != null) {
            color[0] = c.danger();
            return I18n.get(ModLanguageData.Keys.SKIN_FAILED, problem);
        }
        if (waiting()) {
            color[0] = c.textSecondary();
            for (ServerLibraries.SkinJob job : ServerLibraries.skinJobs()) {
                if ("WAITING".equals(job.state())) {
                    return t(ModLanguageData.Keys.SKIN_JOB_WAITING);
                }
                if ("QUEUED".equals(job.state()) && job.position() > 1) {
                    return I18n.get(ModLanguageData.Keys.SKIN_JOB_QUEUED, job.position());
                }
            }
            return t(ModLanguageData.Keys.SKIN_PREPARING);
        }
        return null;
    }

    // ---- 画与点 ----

    private int rowH(Row row) {
        return row instanceof Row.Heading ? HEADING_H : row instanceof Row.Note ? NOTE_H : ROW_H;
    }

    private int contentH() {
        return rows.stream().mapToInt(this::rowH).sum();
    }

    /** 鼠标下是第几行;不在列表上是 -1。 */
    private int rowAt(double mx, double my) {
        if (mx < listX || mx >= listX + listW || my < listY || my >= listY + LIST_H) {
            return -1;
        }
        int at = listY - scrollPx;
        for (int i = 0; i < rows.size(); i++) {
            int next = at + rowH(rows.get(i));
            if (my >= at && my < next) {
                return i;
            }
            at = next;
        }
        return -1;
    }

    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
        // 她已经穿上了我们等的那一张(或已脱掉):收卡
        SkinRef worn = worn();
        if (expected != null && expected.equals(worn) || takingOff && worn == null) {
            host.onClose();
            return;
        }
        wear.setEnabled(selected >= 0 && !waiting());
        armPick.setEnabled(selected >= 0 && rows.get(selected) instanceof Row.Item && !waiting());
        int max = Math.max(0, contentH() - LIST_H);
        scrollPx = Math.clamp(scrollPx, 0, max);
        s.pushScissor(listX, listY, listW, LIST_H);
        int hot = rowAt(mouseX, mouseY);
        int at = listY - scrollPx;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int h = rowH(row);
            if (at + h > listY && at < listY + LIST_H) {
                paintRow(s, c, row, i, at, h, i == hot, worn);
            }
            at += h;
        }
        s.popScissor();
        if (max > 0) {   // 一条细滚动条
            int barH = Math.max(12, LIST_H * LIST_H / contentH());
            int barY = listY + (LIST_H - barH) * scrollPx / max;
            s.fillRect(listX + listW - 2, barY, 2, barH, c.textMuted());
        }
        int[] color = {c.textSecondary()};
        String line = status(c, color);
        if (line != null) {
            s.drawText(TextClip.fit(s, line, w - DialogBox.PAD_X * 2), x + DialogBox.PAD_X, statusY, color[0], false);
        }
    }

    private void paintRow(McDrawSurface s, NumenTheme.Colors c, Row row, int index, int at, int h, boolean hot, SkinRef worn) {
        int tx = listX + 4;
        switch (row) {
            case Row.Heading heading -> s.drawText(heading.text(), tx, at + 3, c.accent(), false);
            case Row.Note note -> s.drawText(TextClip.fit(s, note.text(), listW - 8), tx, at + 3, c.textSecondary(), false);
            case Row.Default ignored -> {
                paintBase(s, c, index, at, h, hot);
                PlayerFaceRenderer.draw(s.graphics(), DefaultPlayerSkin.get(host.uuid()), tx, at + (h - FACE) / 2, FACE);
                s.drawText(t(ModLanguageData.Keys.SUMMON_SKIN_DEFAULT), tx + FACE + 6, at + 6,
                        c.textPrimary(), false);
                if (worn == null) {
                    s.drawText("✓", listX + listW - 14, at + 6, c.accent(), false);
                }
            }
            case Row.Item item -> {
                SkinChoices.Choice choice = item.choice();
                paintBase(s, c, index, at, h, hot && choice.usable());
                ResourceLocation face = choice.usable() ? SkinTextures.faceOf(choice.where() + ":" + choice.name(), choice.png())
                        : null;
                if (face != null) {
                    PlayerFaceRenderer.draw(s.graphics(), face, tx, at + (h - FACE) / 2, FACE);
                }
                int right = listX + listW - 6;
                String tag = choice.usable() ? t(SkinProfile.VARIANT_SLIM.equals(choice.variant())
                        ? ModLanguageData.Keys.SKIN_VARIANT_SLIM : ModLanguageData.Keys.SKIN_VARIANT_CLASSIC)
                        : choice.problem().message().getString();
                int tagW = Math.min(s.textWidth(tag), listW / 2);
                boolean current = worn != null && choice.usable() && worn.hash().equals(choice.hash());
                int checkW = current ? 12 : 0;
                s.drawText(TextClip.fit(s, tag, tagW), right - tagW - checkW, at + 6,
                        choice.usable() ? c.textMuted() : c.danger(), false);
                s.drawText(TextClip.fit(s, choice.name(), right - tagW - checkW - (tx + FACE + 6) - 6),
                        tx + FACE + 6, at + 6, choice.usable() ? c.textPrimary() : c.textMuted(), false);
                if (current) {
                    s.drawText("✓", right - 8, at + 6, c.accent(), false);
                }
            }
        }
    }

    private void paintBase(McDrawSurface s, NumenTheme.Colors c, int index, int at, int h, boolean hot) {
        if (index == selected) {
            s.fillRect(listX, at, listW, h, c.selected());
        } else if (hot) {
            s.fillRect(listX, at, listW, h, c.hover());
        }
    }

    @Override
    boolean mouseClicked(double mx, double my, int button) {
        int i = rowAt(mx, my);
        if (button == 0 && i >= 0 && !waiting()) {
            select(i);
            return true;
        }
        return ui.mouseClicked(mx, my, button);
    }

    @Override
    boolean mouseScrolled(double mx, double my, double delta) {
        if (rowAt(mx, my) >= 0) {
            scrollPx -= (int) Math.signum(delta) * ROW_H;
            return true;
        }
        return ui.mouseScrolled(mx, my, delta);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
