package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.ClientCompanions;
import com.dwinovo.numen.api.client.agent.ClientLibraries;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.ScrollBox;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.permission.Preset;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 权限分区——主人管自己名下每只同伴的权限设置:八种动作各一个档位(分段钮,点哪个设哪个),和每种动作"不用问"的名单。
 * 读的是服务端的同一份({@link ClientLibraries#permission},命令 {@code /numen permission show} 读的也是它),写的都是请求,
 * 办不办由服务端判。
 *
 * <p>上面一块:每只同伴一节——一行名字加三个预设钮(只看、全放行、恢复出厂),下面八行各是一个动作的名字和它的档位分段钮;
 * 下面是名单列表({@link LibraryListPanel}):按动作分节,每项一行,下面一行小字是哪只同伴的,右键能删。
 */
public final class PermissionPanel {

    private static final List<Tier> TIERS = Arrays.asList(Tier.values());
    private static final List<Preset> PRESETS = Arrays.asList(Preset.values());
    /** 档位块最多露几行,再多滚动:同伴很多时不把名单挤没。 */
    private static final int MAX_TIER_ROWS = 10;
    /** 档位块与名单之间的空。 */
    private static final int GAP = 4;

    /** 上块里的控件:预设钮、档位分段钮、同伴名与动作名。 */
    private final UiRoot top = new UiRoot();
    private final ScrollBox tierScroll = new ScrollBox();
    private final LibraryListPanel<ClientLibraries.PermissionListed> listed;

    private int x, y, w, h;
    private int dimX, dimY, dimW, dimH;
    private int tiersTop, tiersViewH, listTop;
    /** 搭档位块时的同伴名册:变了(她被召唤、遣散)才重搭,档位本身变了只是分段钮滑一下。 */
    private List<UUID> builtFor = List.of();

    /** 服务端那张表读成的样子,表的版本不变就一直用它。 */
    private ClientLibraries.Permission snapshot = ClientLibraries.Permission.EMPTY;
    private long snapshotVersion = -1;

    public PermissionPanel() {
        Minecraft mc = Minecraft.getInstance();
        top.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        listed = new LibraryListPanel<ClientLibraries.PermissionListed>(
                () -> permission().listed(),
                PermissionPanel::row,
                r -> I18n.get(ModLanguageData.Keys.PERMISSION_DELETE_CONFIRM, r.entry()),
                r -> ClientLibraries.deleteListed(r.companion(), r.verb(), r.entry()),
                null)
                .withSections(ClientLibraries.PermissionListed::verb, PermissionPanel::sections)
                .following(Libraries.PERMISSION);
    }

    /** 右键一行弹删除菜单:交给宿主统一的右键通道。 */
    LibraryListPanel<?> list() {
        return listed;
    }

    private ClientLibraries.Permission permission() {
        long version = ClientCompanions.library().version(Libraries.PERMISSION);
        if (version != snapshotVersion) {
            snapshotVersion = version;
            snapshot = ClientLibraries.permission();
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
        ClientLibraries.Permission now = permission();
        builtFor = now.companions();

        var font = Minecraft.getInstance().font;
        List<String> tierNames = TIERS.stream().map(t -> I18n.get(ModLanguageData.Keys.PERMISSION_TIER_PREFIX + t.word())).toList();
        int segW = Segmented.widthFor(tierNames, font::width);
        tiersTop = y;
        int ry = tiersTop;
        for (UUID companion : builtFor) {
            String name = now.tiers().stream().filter(t -> t.companion().equals(companion)).findFirst()
                    .map(ClientLibraries.PermissionTier::name).orElse("?");
            ry = presetRow(companion, name, ry);
            for (Verb verb : Verb.values()) {
                SettingsRows.label(top, I18n.get(ModLanguageData.Keys.PERMISSION_VERB_PREFIX + verb.word()), x + 6, ry,
                        w - segW - 14);
                Segmented seg = top.add(new Segmented(tierNames, () -> tierIndex(companion, verb),
                        pick -> ClientLibraries.setTier(companion, verb.word(), TIERS.get(pick).word())));
                seg.setBounds(x + w - segW - 2, SettingsRows.controlY(ry, NumenStyle.CONTROL_H), segW, NumenStyle.CONTROL_H);
                ry += SettingsRows.ROW_H;
            }
        }
        int rows = (ry - tiersTop) / SettingsRows.ROW_H;
        if (rows == 0) {
            Label none = top.add(new Label(I18n.get(ModLanguageData.Keys.PERMISSION_NO_COMPANIONS), Label.Role.SECONDARY));
            none.setBounds(x, SettingsRows.controlY(ry, 9), w, 9);
            rows = 1;
        }
        tiersViewH = Math.min(rows, MAX_TIER_ROWS) * SettingsRows.ROW_H;
        tierScroll.measure(top, x - NumenStyle.PAD, tiersTop, w + NumenStyle.PAD * 2, tiersViewH,
                rows * SettingsRows.ROW_H);

        listTop = tiersTop + tiersViewH + GAP;
        listed.build(x, listTop, w, y + h - listTop, dimX, dimY, dimW, dimH);
    }

    /** 一只同伴那一节的抬头:名字,和靠右排开的三个预设钮。返回下一行的顶。 */
    private int presetRow(UUID companion, String name, int ry) {
        var font = Minecraft.getInstance().font;
        int right = x + w - 2;
        for (int i = PRESETS.size() - 1; i >= 0; i--) {
            Preset preset = PRESETS.get(i);
            String label = I18n.get(ModLanguageData.Keys.PERMISSION_PRESET_PREFIX + preset.word());
            int bw = font.width(label) + 16;
            Button button = top.add(new Button(label, Button.Style.GHOST,
                    () -> ClientLibraries.applyPreset(companion, preset.word())));
            button.setBounds(right - bw, SettingsRows.controlY(ry, NumenStyle.CONTROL_H), bw, NumenStyle.CONTROL_H);
            right -= bw + 4;
        }
        SettingsRows.label(top, name, x, ry, Math.max(20, right - x));
        return ry + SettingsRows.ROW_H;
    }

    /** 这只同伴服务端此刻存的这种动作的档位是三格里的第几格。 */
    private int tierIndex(UUID companion, Verb verb) {
        for (ClientLibraries.PermissionTier t : permission().tiers()) {
            if (t.companion().equals(companion) && t.verb().equals(verb.word())) {
                Tier tier = Tier.byWord(t.tier());
                return tier == null ? 0 : TIERS.indexOf(tier);
            }
        }
        return 0;
    }

    // ---- 名单的行与分组 ----

    /** 八节按动作的次序排;有条目才显示。 */
    private static List<LibraryListPanel.Section> sections() {
        List<LibraryListPanel.Section> out = new ArrayList<>();
        for (Verb verb : Verb.values()) {
            out.add(new LibraryListPanel.Section(verb.word(),
                    I18n.get(ModLanguageData.Keys.PERMISSION_VERB_PREFIX + verb.word()), null, null, null, false));
        }
        return out;
    }

    /** 一行:名单里的名字,下面一行小字是哪只同伴的。能删(右键)。 */
    private static LibraryListPanel.Row row(ClientLibraries.PermissionListed r) {
        return new LibraryListPanel.Row(r.entry(), r.name(), false, null, false, false);
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (!builtFor.equals(permission().companions())) {
            build(x, y, w, h, dimX, dimY, dimW, dimH);   // 名册变了,档位块重搭
        }
        boolean modal = top.hasOverlay();
        tierScroll.beginClip(s);
        top.renderContent(s, c, modal ? -10000 : mouseX, modal ? -10000 : mouseY, nowMs);
        tierScroll.endClip(s);
        tierScroll.renderThumb(s, c);
        s.fillRect(x, listTop - GAP / 2, w, 1, c.divider());

        listed.render(s, c, modal ? -10000 : mouseX, modal ? -10000 : mouseY, nowMs);
        top.renderOverlayLayer(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (top.hasOverlay()) {
            return top.mouseClicked(mx, my, button);
        }
        if (listed.mouseClicked(mx, my, button)) {
            return true;
        }
        return button == 0 && tierScroll.inside(my) && top.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (top.hasOverlay()) {
            return true;
        }
        if (my >= tiersTop && my < tiersTop + tiersViewH) {
            return tierScroll.scrolled(my, delta);
        }
        return listed.mouseScrolled(mx, my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return top.keyPressed(keyCode, modifiers) || listed.keyPressed(keyCode, modifiers);
    }
}
