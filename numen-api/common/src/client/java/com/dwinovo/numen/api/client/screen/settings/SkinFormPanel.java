package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.client.skin.SkinTextures;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.Dropdown;
import com.dwinovo.numen.api.client.ui.widget.InlineAlert;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.skin.SkinProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 皮肤的编辑表单——NumenUI 版的瓤:名称 + 手臂模型下拉 + 「选择文件…」(原生文件对话框/拖拽入窗共用 {@link #importFile}),
 * 选中的图在本机先预览。保存只是把名字、手臂模型和图交给服务端:签名在服务端排队({@code SkinSigning}),排到第几、
 * 被限流在等、签好没有都在列表页的行上。界面写明"图片会交给 MineSkin"——这是玩家要知道的外传。
 * 管理员另有一行 MineSkin 的 API key(服务器钥匙串里的,提高签名额度)。导入尺寸校验就地报错。
 */
public final class SkinFormPanel {

    /** 表单草稿;{@code editId} 空 = 新建。图字节留在草稿里,保存才发给服务端。 */
    public static final class Draft {
        public String editId;
        public String name = "";
        public String variant = SkinProfile.VARIANT_CLASSIC;
        public byte[] dropped;
        public int droppedW;
        public int droppedH;
        /** 管理员看到的服务器钥匙串里 MineSkin 的 key;非管理员恒为空,不显示这一行。 */
        public String mineSkinKey = "";
    }

    private static final List<String> VARIANTS =
            List.of(SkinProfile.VARIANT_CLASSIC, SkinProfile.VARIANT_SLIM);
    /** 选中的图预览的边长。 */
    private static final int PREVIEW = 32;

    private final UiRoot ui = new UiRoot();
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;
    private final Runnable onPickFile;
    private final java.util.function.BooleanSupplier admin;

    private Draft draft = new Draft();
    private TextField nameField;
    private Label statusLabel;
    private InlineAlert resultAlert;
    private int previewX, previewY;

    public SkinFormPanel(Consumer<Draft> onSave, Runnable onCancel, Runnable onPickFile,
                         java.util.function.BooleanSupplier admin) {
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.onPickFile = onPickFile;
        this.admin = admin;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
        // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 载入待编辑的草稿(新建=空草稿;编辑=从条目拷来)。宿主随后 build。 */
    public void open(Draft d) {
        this.draft = d;
    }

    public void build(int x, int y, int w, int h, UiRoot.Viewport window) {
        ui.clear();
        ui.setViewport(window);

        int ry = y;
        Label nameLabel = labelWidget(x, ry, ModLanguageData.Keys.SKIN_FORM_NAME);
        ry += NumenStyle.LABEL_PITCH;
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v).withLabel(nameLabel));
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
        statusLabel = ui.add(new Label("", Label.Role.SECONDARY));
        statusLabel.setBounds(x + pickW + 6, ry + 4, Math.max(0, w - pickW - 6 - PREVIEW - 6), 9);
        previewX = x + w - PREVIEW;
        previewY = ry - 8;
        refreshStatus();
        ry += 20;
        Label notice = ui.add(new Label(t(ModLanguageData.Keys.SKIN_NOTICE), Label.Role.SECONDARY));
        notice.setBounds(x, ry, w - PREVIEW - 6, 9);
        ry += NumenStyle.ROW_PITCH;

        if (admin.getAsBoolean()) {
            ry = label(x, ry, ModLanguageData.Keys.SKIN_FORM_MINESKIN_KEY);
            TextField key = ui.add(new TextField(draft.mineSkinKey, v -> draft.mineSkinKey = v).masked(true)
                    .placeholder(t(ModLanguageData.Keys.SKIN_MINESKIN_KEY_HINT)));
            key.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        }

        resultAlert = ui.add(new InlineAlert());
        resultAlert.setBounds(x, y + 2, w, 24);
        DialogButtons.cancelSave(ui, x, y, w, h, onCancel, this::save);
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
        // 选中的图先在本机看一眼:画她的脸
        if (draft.dropped != null && s instanceof com.dwinovo.numen.api.client.ui.mc.McDrawSurface mc) {
            var face = SkinTextures.faceOf("draft", draft.dropped);
            if (face != null) {
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(mc.graphics(), face, previewX, previewY,
                        PREVIEW);
            }
        }
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

    public boolean hasOverlay() {
        return ui.hasOverlay();
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    // ---- 内部 ----

    private void refreshStatus() {
        if (draft.dropped != null) {
            statusLabel.setText(t2(ModLanguageData.Keys.SKIN_LOADED, draft.droppedW + "x" + draft.droppedH));
        } else if (draft.editId != null) {
            statusLabel.setText(t(ModLanguageData.Keys.SKIN_KEEP_OLD));
        } else {
            statusLabel.setText("");
        }
    }

    private int label(int lx, int ly, String key) {
        labelWidget(lx, ly, key);
        return ly + NumenStyle.LABEL_PITCH;
    }

    /** 标签控件本体:会报错的字段用 withLabel 认领它(出错时自动让位)。 */
    private Label labelWidget(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.SECONDARY));
        l.setBounds(lx, ly, 200, 9);
        return l;
    }

    /** 保存 = 把名字、手臂模型和(新选的)图交给宿主发给服务端;新建一定要有图。 */
    private void save() {
        if (draft.name.trim().isEmpty()) {
            nameField.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));   // 校验错误内联在错误发生处
            return;
        }
        if (draft.editId == null && draft.dropped == null) {
            resultAlert.show(InlineAlert.Severity.ERROR, t(ModLanguageData.Keys.SKIN_WARN_IMAGE));
            return;
        }
        onSave.accept(draft);
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }

    private static String t2(String key, Object arg) {
        return Component.translatable(key, arg).getString();
    }
}
