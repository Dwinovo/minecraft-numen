package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;
import com.dwinovo.numen.api.client.ui.widget.ConfirmDialog;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.ScrollBox;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.permission.RuleBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 权限分区——主人管自己名下的规则与模式,照 Claude Code 的 {@code /permissions} 对话框:规则按"拒绝、询问、允许"分组列出,
 * 每行标来源,能加能删。读的是服务端的同一份({@link ServerLibraries#permission},命令 {@code /numen permission rules list}
 * 读的也是它),写的都是请求,办不办由服务端判。
 *
 * <p>上面一块:服主的硬规则开没开(开关是服主的服务器设置,这里只报告),和主人名下每只同伴的模式(分段钮,点哪个设哪个);
 * 下面是规则列表({@link LibraryListPanel}):硬规则(服主管)、主人层的拒绝/询问/允许(能删,右键一行)、出厂层,
 * 后两者只读。每行规则原文,下面一行小字是来源(主人写的、她加的及她说的理由)和命中账(几次、最近多久以前)。
 * 标题行"＋"加一行(表单卡,{@link RuleFormPanel}),"清空"清掉主人层所有的行(过确认卡)。
 */
public final class PermissionPanel {

    /** 同伴模式的三格,次序同分段钮;说法是服务端存的名字。 */
    private static final List<String> MODES = List.of("ask", "bypass", "observe");
    /** 模式行最多露几行,再多滚动:同伴很多时不把规则列表挤没。 */
    private static final int MAX_MODE_ROWS = 4;
    /** 状态行、模式行与规则列表之间的空。 */
    private static final int GAP = 4;

    /** 上块里的控件:模式分段钮、模式行的名字,以及清空的确认卡。 */
    private final UiRoot top = new UiRoot();
    private final ScrollBox modeScroll = new ScrollBox();
    private final ConfirmDialog clearConfirm = new ConfirmDialog();
    private final LibraryListPanel<ServerLibraries.PermissionRule> rules;

    private int x, y, w, h, dimX, dimY, dimW, dimH;
    /** 状态行、模式块、列表各自的顶边(build 时定)。 */
    private int modesTop, modesViewH, listTop;
    /** 搭模式块时的同伴名册:变了(她被召唤、遣散)才重搭,模式本身变了只是分段钮滑一下。 */
    private List<UUID> builtFor = List.of();

    /** 服务端那张表读成的样子,表的版本不变就一直用它。 */
    private ServerLibraries.Permission snapshot = ServerLibraries.Permission.EMPTY;
    private long snapshotVersion = -1;

    /** @param onAdd 标题行"＋":宿主打开加规则的表单卡 */
    public PermissionPanel(Runnable onAdd) {
        Minecraft mc = Minecraft.getInstance();
        top.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        rules = new LibraryListPanel<ServerLibraries.PermissionRule>(
                ModLanguageData.Keys.PERMISSION_ADD,
                ModLanguageData.Keys.PERMISSION_EMPTY,
                () -> permission().rules(),
                PermissionPanel::row,
                r -> I18n.get(ModLanguageData.Keys.PERMISSION_DELETE_CONFIRM, r.rule()),
                r -> ServerLibraries.deleteRule(r.table(), r.rule()),
                onAdd,
                null)
                .withGroups(PermissionPanel::group)
                .withTitleAction(I18n.get(ModLanguageData.Keys.PERMISSION_CLEAR), this::askClear)
                .following(Libraries.PERMISSION);
    }

    /** 右键一行弹删除菜单:交给宿主统一的右键通道。 */
    LibraryListPanel<?> list() {
        return rules;
    }

    private ServerLibraries.Permission permission() {
        long version = RemoteCompanions.library().version(Libraries.PERMISSION);
        if (version != snapshotVersion) {
            snapshotVersion = version;
            snapshot = ServerLibraries.permission();
        }
        return snapshot;
    }

    public void build(int x, int y, int w, int h, int dimX, int dimY, int dimW, int dimH) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
        top.clear();
        ServerLibraries.Permission now = permission();
        builtFor = now.modes().stream().map(ServerLibraries.CompanionMode::companion).toList();

        var font = Minecraft.getInstance().font;
        modesTop = y + SettingsRows.ROW_H;
        int rows = now.modes().size();
        modesViewH = Math.max(1, Math.min(rows, MAX_MODE_ROWS)) * SettingsRows.ROW_H;
        List<String> modeNames = List.of(I18n.get(ModLanguageData.Keys.PERMISSION_MODE_PREFIX + "ask"),
                I18n.get(ModLanguageData.Keys.PERMISSION_MODE_PREFIX + "bypass"),
                I18n.get(ModLanguageData.Keys.PERMISSION_MODE_PREFIX + "observe"));
        int segW = Segmented.widthFor(modeNames, font::width);
        int ry = modesTop;
        for (ServerLibraries.CompanionMode mode : now.modes()) {
            SettingsRows.label(top, mode.name(), x, ry, w - segW - 8);
            Segmented seg = top.add(new Segmented(modeNames, () -> modeIndex(mode.companion()),
                    pick -> ServerLibraries.setMode(mode.companion(), MODES.get(pick))));
            seg.setBounds(x + w - segW - 2, SettingsRows.controlY(ry, NumenStyle.CONTROL_H), segW, NumenStyle.CONTROL_H);
            ry += SettingsRows.ROW_H;
        }
        if (rows == 0) {
            Label none = top.add(new Label(I18n.get(ModLanguageData.Keys.PERMISSION_NO_COMPANIONS), Label.Role.SECONDARY));
            none.setBounds(x, SettingsRows.controlY(ry, 9), w, 9);
        }
        modeScroll.measure(top, x - NumenStyle.PAD, modesTop, w + NumenStyle.PAD * 2, modesViewH,
                Math.max(1, rows) * SettingsRows.ROW_H);

        listTop = modesTop + modesViewH + GAP;
        rules.build(x, listTop, w, y + h - listTop, dimX, dimY, dimW, dimH);
    }

    /** 这只同伴服务端此刻存的模式是三格里的第几格。 */
    private int modeIndex(UUID companion) {
        for (ServerLibraries.CompanionMode mode : permission().modes()) {
            if (mode.companion().equals(companion)) {
                return Math.max(0, MODES.indexOf(mode.mode()));
            }
        }
        return 0;
    }

    private void askClear() {
        clearConfirm.open(top, dimX, dimY, dimW, dimH, I18n.get(ModLanguageData.Keys.PERMISSION_CLEAR_CONFIRM),
                I18n.get(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), I18n.get(ModLanguageData.Keys.PERMISSION_CLEAR),
                ServerLibraries::clearRules);
    }

    // ---- 列表的行与分组 ----

    private static String group(ServerLibraries.PermissionRule r) {
        return I18n.get(ModLanguageData.Keys.PERMISSION_GROUP_PREFIX + r.layer() + "." + r.table());
    }

    /** 一行:规则原文,下面一行小字是来源和命中账。主人层的能删(不是只读行);硬规则与出厂层只读。 */
    private static LibraryListPanel.Row row(ServerLibraries.PermissionRule r) {
        List<String> notes = new ArrayList<>();
        if (r.editable()) {
            notes.add(r.byHer()
                    ? r.note().isEmpty() ? I18n.get(ModLanguageData.Keys.COMMAND_RULES_HER)
                            : I18n.get(ModLanguageData.Keys.COMMAND_RULES_HER_WHY, r.note())
                    : I18n.get(ModLanguageData.Keys.PERMISSION_SOURCE_OWNER));
        }
        notes.add(r.count() == 0 ? I18n.get(ModLanguageData.Keys.COMMAND_RULES_NEVER)
                : I18n.get(ModLanguageData.Keys.COMMAND_RULES_HITS, r.count(),
                        I18n.get(ModLanguageData.Keys.COMMAND_RULES_AGO,
                                RuleBook.ago(r.lastMillis(), System.currentTimeMillis()))));
        return new LibraryListPanel.Row(r.rule(), String.join(" · ", notes), false, null, !r.editable(), false);
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ServerLibraries.Permission now = permission();
        if (!builtFor.equals(now.modes().stream().map(ServerLibraries.CompanionMode::companion).toList())) {
            build(x, y, w, h, dimX, dimY, dimW, dimH);   // 名册变了,模式块重搭
        }
        // 硬规则开没开:服主的服务器设置,这里只报告
        s.drawText(TextClip.fit(s, I18n.get(now.hardRules() ? ModLanguageData.Keys.PERMISSION_HARD_ON
                        : ModLanguageData.Keys.PERMISSION_HARD_OFF), w), x, SettingsRows.controlY(y, s.lineHeight()),
                now.hardRules() ? c.textMuted() : c.warning(), false);

        boolean modal = top.hasOverlay();
        modeScroll.beginClip(s);
        top.renderContent(s, c, modal ? -10000 : mouseX, modal ? -10000 : mouseY, nowMs);
        modeScroll.endClip(s);
        modeScroll.renderThumb(s, c);
        s.fillRect(x, listTop - GAP / 2, w, 1, c.divider());

        rules.render(s, c, modal ? -10000 : mouseX, modal ? -10000 : mouseY, nowMs);
        top.renderOverlayLayer(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (top.hasOverlay()) {
            return top.mouseClicked(mx, my, button);
        }
        if (rules.mouseClicked(mx, my, button)) {
            return true;
        }
        return button == 0 && modeScroll.inside(my) && top.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (top.hasOverlay()) {
            return true;
        }
        if (my >= modesTop && my < modesTop + modesViewH) {
            return modeScroll.scrolled(my, delta);
        }
        return rules.mouseScrolled(mx, my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return top.keyPressed(keyCode, modifiers) || rules.keyPressed(keyCode, modifiers);
    }
}
