package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.Slider;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 声线的编辑表单——NumenUI 版的瓤:后端下拉切换字段行(MiniMax 最多八行,
 * 躺进模型配置表单同款的滚动底盘)、音量滑条、试听按钮真合成真播放、
 * 结果走页面级 InlineAlert(试听中驻留/成功自动淡出/失败驻留),校验错误
 * 内联在字段上。编辑的是 {@link Draft} 草稿,保存才落库(与旧表单同语义)。
 */
public final class VoiceFormPanel {

    /** 表单草稿:与 VoiceLibrary.Entry 字段一一对应(id 由宿主管理);
     *  {@code volume} 用 UI 档位 1~10(5 档 = 原始响度 1.0)。 */
    public static final class Draft {
        public String backend = VoiceLibrary.BACKEND_OPENAI;
        public String name = "";
        public String url = "";
        public String apiKey = "";
        public String groupId = "";
        public String model = "";
        public String voice = "";
        public String refAudio = "";
        public String promptText = "";
        public String textLang = "";
        public int volume = 5;
    }

    /** 新草稿:官方端点预填,用户只补 key/音色。 */
    public static Draft freshDraft() {
        Draft d = new Draft();
        d.url = defaultUrl(d.backend);
        return d;
    }

    /** 草稿 → 库条目(档位换算增益;试听与保存共用同一转换,试出来的就是存进去的)。 */
    public static VoiceLibrary.Entry entryOf(Draft d, String id, String name) {
        float vol = Math.clamp(d.volume, 1, 10) / 5.0f;
        return new VoiceLibrary.Entry(id, name, d.backend,
                d.url.trim(), d.apiKey.trim(), d.groupId.trim(),
                d.model.trim(), d.voice.trim(),
                d.refAudio.trim(), d.promptText.trim(), d.textLang.trim(),
                VoiceLibrary.clampVolume(vol));
    }

    public static String defaultUrl(String backend) {
        return switch (backend) {
            case VoiceLibrary.BACKEND_SOVITS -> com.dwinovo.numen.client.voice.GptSovitsTts.DEFAULT_BASE;
            case VoiceLibrary.BACKEND_MINIMAX -> com.dwinovo.numen.client.voice.MiniMaxTts.DEFAULT_BASE;
            case VoiceLibrary.BACKEND_FISH -> com.dwinovo.numen.client.voice.FishAudioTts.DEFAULT_BASE;
            default -> com.dwinovo.numen.client.voice.OpenAiCompatibleTts.DEFAULT_BASE;
        };
    }

    private static final List<String> BACKENDS = List.of(
            VoiceLibrary.BACKEND_OPENAI, VoiceLibrary.BACKEND_SOVITS,
            VoiceLibrary.BACKEND_MINIMAX, VoiceLibrary.BACKEND_FISH);
    private static final String TEST_SENTENCE = "你好,我是你的同伴,这是我的声音。";

    /** 滚动根:表单行(进裁剪区,可上下滚);固定根:✕/结果胶囊/按钮行(不动)。 */
    private final UiRoot ui = new UiRoot();
    private final UiRoot fixedUi = new UiRoot();
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;

    private final java.util.Map<com.dwinovo.numen.client.ui.widget.Widget, Integer> baseYs =
            new java.util.HashMap<>();
    private int scrollY;
    private int contentH;
    private int viewH;
    private int formX, formY, formW, formH, viewportBottom;

    private Draft draft = new Draft();
    private TextField nameField;
    private Button testButton;
    private InlineAlert resultAlert;
    /** 在途试听的作废闸:表单关闭/又点一次都 ++,迟到的回调对不上号就丢弃。 */
    private int testGen;
    private com.dwinovo.numen.client.voice.VoicePreviewSound preview;

    public VoiceFormPanel(Consumer<Draft> onSave, Runnable onCancel) {
        this.onSave = onSave;
        this.onCancel = onCancel;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
    }

    /** 载入待编辑的草稿(新建=freshDraft;编辑=从条目拷来)。宿主随后 build。 */
    public void open(Draft d) {
        this.draft = d;
    }

    /** 表单关闭/切分区:作废在途试听回调。 */
    public void cancelPendingTest() {
        testGen++;
    }

    public void build(int x, int y, int w, int h, int viewportBottom) {
        this.formX = x;
        this.formY = y;
        this.formW = w;
        this.formH = h;
        this.viewportBottom = viewportBottom;
        ui.clear();
        fixedUi.clear();
        baseYs.clear();
        ui.setViewportHeight(viewportBottom);

        int ry = y;
        Label nameLabel = labelWidget(x, ry, ModLanguageData.Keys.VOICE_FORM_NAME);
        ry += NumenStyle.LABEL_PITCH;
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v).withLabel(nameLabel));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, ModLanguageData.Keys.PROVIDER_FORM_PROVIDER);
        Dropdown backendPick = ui.add(new Dropdown(List.of(
                t(ModLanguageData.Keys.VOICE_BACKEND_OPENAI),
                t(ModLanguageData.Keys.VOICE_BACKEND_SOVITS),
                t(ModLanguageData.Keys.VOICE_BACKEND_MINIMAX),
                t(ModLanguageData.Keys.VOICE_BACKEND_FISH)),
                Math.max(0, BACKENDS.indexOf(draft.backend)), this::onBackendPicked));
        backendPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, ModLanguageData.Keys.VOICE_FORM_URL);
        TextField urlField = ui.add(new TextField(draft.url, v -> draft.url = v)
                .placeholder(defaultUrl(draft.backend)));
        urlField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 后端专属行(行序与旧表单一致,占位符沿用旧示例)。
        switch (draft.backend) {
            case VoiceLibrary.BACKEND_SOVITS -> {
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_REF,
                        "D:/refs/voice.wav", false, draft.refAudio, v -> draft.refAudio = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_PROMPT,
                        "", false, draft.promptText, v -> draft.promptText = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_LANG,
                        "zh", false, draft.textLang, v -> draft.textLang = v);
            }
            case VoiceLibrary.BACKEND_MINIMAX -> {
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_KEY_MINIMAX,
                        "eyJ…", true, draft.apiKey, v -> draft.apiKey = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_GROUP,
                        "", false, draft.groupId, v -> draft.groupId = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_MINIMAX_MODEL,
                        "speech-02-turbo", false, draft.model, v -> draft.model = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_MINIMAX_VOICE,
                        "male-qn-qingse", false, draft.voice, v -> draft.voice = v);
            }
            case VoiceLibrary.BACKEND_FISH -> {
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_KEY_FISH,
                        "sk-…", true, draft.apiKey, v -> draft.apiKey = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_REFERENCE,
                        "fish.audio/m/… 或纯 ID", false, draft.voice, v -> draft.voice = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_FISH_MODEL,
                        "s1 / s2.1-pro-free", false, draft.model, v -> draft.model = v);
            }
            default -> {
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_KEY_OPENAI,
                        "sk-…", true, draft.apiKey, v -> draft.apiKey = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_MODEL,
                        "FunAudioLLM/CosyVoice2-0.5B", false, draft.model, v -> draft.model = v);
                ry = textRow(x, ry, w, ModLanguageData.Keys.VOICE_FORM_VOICE,
                        "FunAudioLLM/CosyVoice2-0.5B:alex", false, draft.voice, v -> draft.voice = v);
            }
        }

        ry = label(x, ry, ModLanguageData.Keys.VOICE_FORM_VOLUME);
        Slider volume = ui.add(new Slider(1, 10, 1, draft.volume,
                v -> draft.volume = (int) Math.round(v),
                v -> String.valueOf(Math.round(v))));
        volume.setBounds(x, ry, w - 24, NumenStyle.CONTROL_H);

        // ---- 滚动记账:内容高、视口高(按钮行之上),各行基线快照 ----
        contentH = (ry + NumenStyle.CONTROL_H) - y;
        viewH = h - 20;
        scrollY = Math.min(scrollY, maxScroll());
        for (com.dwinovo.numen.client.ui.widget.Widget rw : ui.widgetsView()) {
            baseYs.put(rw, rw.y());
        }
        reposition();

        // ---- 固定层:✕(卡片右上角落)/结果胶囊/按钮行——不随滚动 ----
        Button close = fixedUi.add(new Button("✕", Button.Style.GHOST, onCancel));
        close.setBounds(x + w - 8, y - 14, 14, 14);

        int by = y + h - 16;
        resultAlert = fixedUi.add(new InlineAlert());
        resultAlert.setBounds(x, y + 2, w, 24);
        testButton = fixedUi.add(new Button(t(ModLanguageData.Keys.VOICE_TEST),
                Button.Style.NORMAL, this::runVoiceTest));
        testButton.setBounds(x + w - 54 - 58, by, 54, 15);
        Button save = fixedUi.add(new Button(t("numen.gui.settings.save"),
                Button.Style.ACCENT, this::save));
        save.setBounds(x + w - 54, by, 54, 15);
    }

    private int maxScroll() {
        return Math.max(0, contentH - viewH);
    }

    /** 滚动=全部行控件按基线整体位移(布局账只算一次,滚动只挪 y)。 */
    private void reposition() {
        for (var e : baseYs.entrySet()) {
            var rw = e.getKey();
            rw.setBounds(rw.x(), e.getValue() - scrollY, rw.w(), rw.h());
        }
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        s.pushScissor(formX, formY, formW + 2, viewH);
        ui.renderContent(s, c, mouseX, mouseY, nowMs);
        s.popScissor();
        if (maxScroll() > 0) {
            int thumbH = Math.max(10, viewH * viewH / contentH);
            int thumbY = formY + (viewH - thumbH) * scrollY / maxScroll();
            s.fillRoundRect(formX + formW, thumbY, NumenStyle.SCROLLBAR_W, thumbH,
                    NumenStyle.RADIUS_SMALL, c.divider());
        }
        fixedUi.render(s, c, mouseX, mouseY, nowMs);
        ui.renderOverlayLayer(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (ui.hasOverlay()) return ui.mouseClicked(mx, my, button);   // 弹层优先(可越出视口)
        if (fixedUi.mouseClicked(mx, my, button)) return true;
        if (my >= formY && my < formY + viewH) {
            return ui.mouseClicked(mx, my, button);
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, double dx, double dy) {
        return ui.mouseDragged(mx, my, dx, dy);   // 音量滑条拖动
    }

    public boolean mouseReleased(double mx, double my, int button) {
        return ui.mouseReleased(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (ui.mouseScrolled(mx, my, delta)) return true;
        if (maxScroll() > 0 && my >= formY && my < formY + viewH) {
            scrollY = Math.max(0, Math.min(maxScroll(), scrollY - (int) (delta * 14)));
            reposition();
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return ui.keyPressed(keyCode, modifiers) || fixedUi.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch) || fixedUi.charTyped(ch);
    }

    public boolean hasOverlay() {
        return ui.hasOverlay() || fixedUi.hasOverlay();
    }

    // ---- 内部 ----

    private int label(int lx, int ly, String key) {
        labelWidget(lx, ly, key);
        return ly + NumenStyle.LABEL_PITCH;
    }

    /** 标签控件本体:会报错的字段用 withLabel 认领它(出错时自动让位)。 */
    private Label labelWidget(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 200, 9);
        return l;
    }

    /** 一行"标签+输入框",返回下一行的 y。 */
    private int textRow(int x, int ry, int w, String labelKey, String placeholder,
                        boolean masked, String initial, Consumer<String> onChange) {
        ry = label(x, ry, labelKey);
        TextField f = ui.add(new TextField(initial, onChange).masked(masked));
        if (!placeholder.isEmpty()) f.placeholder(placeholder);
        f.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        return ry + NumenStyle.ROW_PITCH;
    }

    /** 选型变了:行随之换(草稿字段全保);URL 只覆盖"空或还是旧默认"的值。 */
    private void onBackendPicked(int index) {
        String sel = BACKENDS.get(Math.clamp(index, 0, BACKENDS.size() - 1));
        if (sel.equals(draft.backend)) return;
        if (draft.url.isBlank() || draft.url.equals(defaultUrl(draft.backend))) {
            draft.url = defaultUrl(sel);
        }
        draft.backend = sel;
        scrollY = 0;
        build(formX, formY, formW, formH, viewportBottom);
    }

    private void save() {
        if (draft.name == null || draft.name.isBlank()) {
            nameField.setError(t("numen.gui.inline.required"));   // 校验错误内联在错误发生处
            return;
        }
        cancelPendingTest();
        onSave.accept(draft);
    }

    /**
     * 试听:用草稿当前值合成固定测试句,就地 2D 播放(不挂实体,
     * {@link com.dwinovo.numen.client.voice.VoicePreviewSound} 走与 3D 语音同一条
     * mixin 取数路径)。结果进页面级胶囊:成功自动淡出,失败驻留到下次操作。
     */
    private void runVoiceTest() {
        var probe = entryOf(draft, "__preview__",
                draft.name.isBlank() ? "preview" : draft.name.trim());
        resultAlert.show(InlineAlert.Severity.INFO, t(ModLanguageData.Keys.VOICE_TEST_RUNNING));
        final int gen = ++testGen;
        final float vol = probe.volume();
        // 同步防线:后端构建/合成同步抛(坏 URL 曾直接崩掉渲染线程)也只落到胶囊。
        java.util.concurrent.CompletableFuture<byte[]> synth;
        final com.dwinovo.numen.client.voice.TtsBackend backend;
        try {
            backend = probe.createBackend();
            synth = backend.synthesize(TEST_SENTENCE);
        } catch (Exception ex) {
            String why = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            com.dwinovo.numen.Constants.LOG.warn("[numen-voice] 试音失败(同步): {}", why);
            resultAlert.show(InlineAlert.Severity.ERROR,
                    t2(ModLanguageData.Keys.VOICE_TEST_FAIL, shorten(why)));
            return;
        }
        synth.whenComplete((wav, err) -> {
            com.dwinovo.numen.client.voice.PcmAudio decoded = null;
            Throwable failure = err;
            if (err == null) {
                try {
                    decoded = com.dwinovo.numen.client.voice.WavCodec.decode(wav).amplified(vol);
                } catch (Exception ex) {
                    failure = ex;
                }
            }
            final var audio = decoded;
            final Throwable fail = failure;
            Minecraft.getInstance().execute(() -> {
                if (gen != testGen) return;   // 表单已离开/又点了一次:作废
                if (fail != null) {
                    Throwable cur = fail;
                    while (cur.getCause() != null && cur != cur.getCause()) cur = cur.getCause();
                    String why = cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
                    // 完整原因进日志(胶囊里被截短,排障全靠这行)。
                    com.dwinovo.numen.Constants.LOG.warn("[numen-voice] 试音失败({}): {}",
                            backend.describe(), why);
                    resultAlert.show(InlineAlert.Severity.ERROR,
                            t2(ModLanguageData.Keys.VOICE_TEST_FAIL, shorten(why)));
                    return;
                }
                var sm = Minecraft.getInstance().getSoundManager();
                if (preview != null) sm.stop(preview);   // 重听:停掉上一句
                preview = com.dwinovo.numen.client.platform.ClientServices.VOICE.previewVoice(audio, 1.0f);
                sm.play(preview);
                resultAlert.show(InlineAlert.Severity.SUCCESS,
                        t(ModLanguageData.Keys.VOICE_TEST_OK), 2_500);
            });
        });
    }

    /** 胶囊是单行:错误原文截短进胶囊,完整版在日志。 */
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
