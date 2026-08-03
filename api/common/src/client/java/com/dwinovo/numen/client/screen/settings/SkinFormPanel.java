package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.skin.SkinLibrary;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 皮肤的编辑表单——NumenUI 版的瓤:名称 + 手臂模型下拉 + 「选择文件…」
 * (原生文件对话框/拖拽入窗共用 {@link #importFile});保存 = 需要时先
 * MineSkin 代签再落库(新图/换手臂模型/未签名都要重签,仅改名直接落库)。
 * 签名排队期间按钮自锁,结果走页面级 InlineAlert;导入尺寸校验就地报错。
 */
public final class SkinFormPanel {

    /** 表单草稿;{@code editId} 空 = 新建。图字节留在草稿里,保存才签名落盘。 */
    public static final class Draft {
        public String editId;
        public String name = "";
        public String variant = SkinLibrary.VARIANT_CLASSIC;
        public byte[] dropped;
        public int droppedW;
        public int droppedH;
    }

    private static final List<String> VARIANTS =
            List.of(SkinLibrary.VARIANT_CLASSIC, SkinLibrary.VARIANT_SLIM);

    private final UiRoot ui = new UiRoot();
    /** 保存完成回调:参数=经 MineSkin 签名的名称(仅改名未重签时为 null)。 */
    private final Consumer<String> onSaved;
    private final Runnable onCancel;
    private final Runnable onPickFile;

    private Draft draft = new Draft();
    private TextField nameField;
    private Label statusLabel;
    private Button saveButton;
    private InlineAlert resultAlert;
    /** 签名在 MineSkin 排队,期间禁止重复点击;gen 作废在途回调。 */
    private boolean signing;
    private int gen;

    public SkinFormPanel(Consumer<String> onSaved, Runnable onCancel, Runnable onPickFile) {
        this.onSaved = onSaved;
        this.onCancel = onCancel;
        this.onPickFile = onPickFile;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 载入待编辑的草稿(新建=空草稿;编辑=从条目拷来)。宿主随后 build。 */
    public void open(Draft d) {
        this.draft = d;
    }

    /** 表单关闭/切分区:作废在途签名回调。 */
    public void cancelPending() {
        gen++;
        signing = false;
    }

    public void build(int x, int y, int w, int h) {
        ui.clear();

        int ry = y;
        ry = label(x, ry, ModLanguageData.Keys.SKIN_FORM_NAME);
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, ModLanguageData.Keys.SKIN_FORM_VARIANT);
        Dropdown variantPick = ui.add(new Dropdown(List.of(
                t(ModLanguageData.Keys.SKIN_VARIANT_CLASSIC),
                t(ModLanguageData.Keys.SKIN_VARIANT_SLIM)),
                Math.max(0, VARIANTS.indexOf(draft.variant)),
                i -> draft.variant = VARIANTS.get(Math.clamp(i, 0, VARIANTS.size() - 1))));
        variantPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 4;

        // 导入区:唯一入口「选择文件…」——原生文件对话框(FCL 会把它翻译成
        // 安卓的文件选择器);拖拽入窗仍被宿主 onFilesDrop 静默接住,不再宣传。
        String pickLabel = t(ModLanguageData.Keys.SKIN_PICK_FILE);
        // 按钮宽随文案实测(写死会被长文案穿底)。
        int pickW = Minecraft.getInstance().font.width(pickLabel) + 14;
        Button pick = ui.add(new Button(pickLabel, Button.Style.NORMAL, onPickFile));
        pick.setBounds(x, ry, pickW, 16);
        statusLabel = ui.add(new Label("", Label.Role.MUTED));
        statusLabel.setBounds(x + pickW + 6, ry + 4, Math.max(0, w - pickW - 6), 9);
        refreshStatus();

        Button close = ui.add(new Button("✕", Button.Style.GHOST, onCancel));
        close.setBounds(x + w - 8, y - 14, 14, 14);
        resultAlert = ui.add(new InlineAlert());
        resultAlert.setBounds(x, y + 2, w, 24);
        saveButton = ui.add(new Button(t("numen.gui.settings.save"),
                Button.Style.ACCENT, this::save));
        saveButton.setBounds(x + w - 54, y + h - 16, 54, 15);
        saveButton.setEnabled(!signing);
    }

    /** 皮肤导入的统一入口:拖拽、原生文件对话框两条路共用。64×64 或旧版 64×32。 */
    public void importFile(java.nio.file.Path p) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            try (var img = com.mojang.blaze3d.platform.NativeImage.read(
                    new java.io.ByteArrayInputStream(bytes))) {
                int iw = img.getWidth(), ih = img.getHeight();
                if (iw != 64 || (ih != 64 && ih != 32)) {
                    resultAlert.show(InlineAlert.Severity.ERROR,
                            t2(ModLanguageData.Keys.SKIN_WARN_SIZE, iw + "x" + ih));
                    return;
                }
                draft.dropped = bytes;
                draft.droppedW = iw;
                draft.droppedH = ih;
                refreshStatus();
                resultAlert.show(InlineAlert.Severity.SUCCESS,
                        t2(ModLanguageData.Keys.SKIN_LOADED, iw + "x" + ih), 2_500);
            }
        } catch (java.io.IOException | RuntimeException ex) {
            resultAlert.show(InlineAlert.Severity.ERROR,
                    t2(ModLanguageData.Keys.SKIN_WARN_READ, ex.getMessage() == null
                            ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    // ---- 内部 ----

    private void refreshStatus() {
        if (draft.dropped != null) {
            statusLabel.setText(t2(ModLanguageData.Keys.SKIN_LOADED,
                    draft.droppedW + "x" + draft.droppedH));
        } else if (draft.editId != null) {
            statusLabel.setText(t(ModLanguageData.Keys.SKIN_KEEP_OLD));
        } else {
            statusLabel.setText("");
        }
    }

    private int label(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 140, 9);
        return ly + NumenStyle.LABEL_PITCH;
    }

    /**
     * 保存 = 签名 + 落库。需要重签的情形:新图、或手臂模型变了(variant 编码在
     * 签名数据里)、或还没签过;仅改名直接落库。
     */
    private void save() {
        if (signing) return;
        String name = draft.name.trim();
        if (name.isEmpty()) {
            nameField.setError(t("numen.gui.inline.required"));   // 校验错误内联在错误发生处
            return;
        }
        var lib = SkinLibrary.instance();
        var old = draft.editId != null ? lib.get(draft.editId) : null;
        byte[] png = draft.dropped;
        boolean needSign = png != null || old == null || !old.variant().equals(draft.variant)
                || !old.signed();
        if (needSign && png == null) {
            if (old != null) {   // 不换图重签(改手臂模型):从盘上读原图
                try {
                    png = java.nio.file.Files.readAllBytes(lib.pngPath(old.id()));
                } catch (java.io.IOException ex) {
                    png = null;
                }
            }
            if (png == null) {
                resultAlert.show(InlineAlert.Severity.ERROR,
                        t(ModLanguageData.Keys.SKIN_WARN_IMAGE));
                return;
            }
        }
        String id = old != null ? old.id() : lib.freshId();
        if (!needSign) {
            lib.put(new SkinLibrary.Entry(id, name, draft.variant, old.value(), old.signature()), null);
            onSaved.accept(null);   // 仅改名:没签名,不报"签名成功"
            return;
        }
        signing = true;
        saveButton.setEnabled(false);
        resultAlert.show(InlineAlert.Severity.INFO, t(ModLanguageData.Keys.SKIN_SIGNING));
        final int myGen = ++gen;
        final byte[] fPng = png;
        final String fVariant = draft.variant;
        com.dwinovo.numen.client.skin.MineSkinClient.generate(fPng, fVariant, name)
                .whenComplete((signed, err) -> Minecraft.getInstance().execute(() -> {
                    if (myGen != gen) return;   // 表单已离开/重开:作废
                    signing = false;
                    saveButton.setEnabled(true);
                    if (err != null || signed == null) {
                        Throwable cur = err;
                        while (cur != null && cur.getCause() != null && cur != cur.getCause()) {
                            cur = cur.getCause();
                        }
                        String why = cur == null ? "?" : (cur.getMessage() == null
                                ? cur.getClass().getSimpleName() : cur.getMessage());
                        // 完整原因进日志(胶囊里被截短,排障全靠这行)。
                        com.dwinovo.numen.Constants.LOG.warn("[numen-skin] MineSkin 签名失败: {}", why);
                        resultAlert.show(InlineAlert.Severity.ERROR,
                                t2(ModLanguageData.Keys.SKIN_SIGN_FAIL, shorten(why)));
                        return;
                    }
                    com.dwinovo.numen.Constants.LOG.info("[numen-skin] MineSkin 签名成功: {}", name);
                    SkinLibrary.instance().put(
                            new SkinLibrary.Entry(id, name, fVariant, signed.value(), signed.signature()),
                            fPng);
                    onSaved.accept(name);
                }));
    }

    private static String shorten(String why) {
        return why.length() > 64 ? why.substring(0, 64) + "…" : why;
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }

    private static String t2(String key, Object arg) {
        return Component.translatable(key, arg).getString();
    }
}
