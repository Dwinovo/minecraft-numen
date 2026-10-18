package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.provider.ThinkingPlan;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.CauseWords;
import com.dwinovo.numen.api.client.agent.LatestReply;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.agent.RemoteLibrary;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TokenFormat;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.Disclosure;
import com.dwinovo.numen.api.client.ui.widget.Dropdown;
import com.dwinovo.numen.api.client.ui.widget.InlineAlert;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.PickField;
import com.dwinovo.numen.api.client.ui.widget.PickerBox;
import com.dwinovo.numen.api.client.ui.widget.Reveal;
import com.dwinovo.numen.api.client.ui.widget.ScrollBox;
import com.dwinovo.numen.api.client.ui.widget.TagRow;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 模型配置(档案)的编辑表单——NumenUI 版的瓤,替换旧表单缺的全套:
 * 检测按钮、思考开关、推理强度、toast、分类报错。档案思想不变:多份具名
 * 配置,创建同伴时自选。编辑的是 {@link Draft} 草稿,保存才落库(与旧表单
 * 同语义);检测直接测草稿当前值,存不存都能先试。
 *
 * <p>表单只组装:厂家、型号是长列表,点开是通用的 {@link PickerBox},列什么、怎么排由这里取了服务端的答案交给它;
 * 目录、草稿解析成的端点(能力:上下文、思考档位)、检测那次请求都在服务端算,这里一概不算也不连网——草稿变了就问一次
 * ({@link #ask}),答案回来({@link #pollAnswer})整张表单按草稿重排({@link #relayout}),输入框的值本来就在草稿里,不会丢。
 */
public final class ProfileFormPanel {

    /**
     * 表单草稿:与档案({@link ConnectionProfile})一一对应(id 由宿主管理)。界面不开放编辑的档案字段
     * (兼容项覆盖)原样带着走,保存不丢。
     */
    public static final class Draft {
        public String name = "";
        public String provider = "";
        public String model = "";
        public String apiKey = "";
        public String baseUrl = "";
        public String reasoningEffort = "";
        /** 上下文长度(tokens);空 = 按型号目录自动。 */
        public String ctx = "";
        public String proxy = "";
        /** 温度;空 = 不发。 */
        public String temperature = "";
        /** 输出上限(tokens);空 = 不发。 */
        public String maxTokens = "";
        private String protocol = "";
        private com.google.gson.JsonObject compat;

        public static Draft of(String name, ConnectionProfile p) {
            Draft d = new Draft();
            d.name = nz(name);
            d.provider = nz(p.provider());
            d.model = nz(p.model());
            d.apiKey = nz(p.apiKey());
            d.baseUrl = nz(p.baseUrl());
            d.reasoningEffort = nz(p.reasoningEffort());
            d.ctx = p.ctx() > 0 ? String.valueOf(p.ctx()) : "";
            d.proxy = nz(p.proxy());
            d.protocol = nz(p.protocol());
            d.temperature = p.temperature() == null ? "" : String.valueOf(p.temperature());
            d.maxTokens = p.maxTokens() > 0 ? String.valueOf(p.maxTokens()) : "";
            d.compat = p.compat();
            return d;
        }

        public ConnectionProfile toProfile() {
            return new ConnectionProfile(provider, protocol, model, apiKey, baseUrl, proxy, reasoningEffort,
                    parseCount(ctx), parseTemperature(temperature), parseCount(maxTokens), compat);
        }

        /** 个数输入:空/非数字/非正数一律 0(= 没填)。 */
        private static int parseCount(String raw) {
            try {
                return Math.max(0, Integer.parseInt(raw == null ? "" : raw.trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** 温度输入:空/不是数字 = null(不发)。 */
        private static Double parseTemperature(String raw) {
            try {
                return Double.valueOf(raw == null ? "" : raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** 滚动根:表单行(进裁剪区,可上下滚);固定根:结果胶囊/按钮行(不动)。 */
    private final UiRoot ui = new UiRoot();
    private final UiRoot fixedUi = new UiRoot();
    private final Consumer<Draft> onSave;
    private final Runnable onCancel;
    /** 换到另一个厂家时,表单上的 key 换成这个厂家已存的(key 按厂家存,不跟着档案走);没存过是空串。 */
    private final java.util.function.Function<String, String> keyOf;

    /** 表单行装不下卡片时整体上下位移(记账在 {@link ScrollBox})。 */
    private final ScrollBox scroll = new ScrollBox();

    private Draft draft = new Draft();

    /** 最近一次 build 的几何:草稿变了要按同样的几何重排。 */
    private int x, y, w, h;
    /** 设置窗口与屏幕相交的可见部分,由宿主给:选择框的遮罩、下拉的可用区域都按它算。 */
    private UiRoot.Viewport window = UiRoot.Viewport.UNBOUNDED;
    /** 高级一节开合的进度;跨重排保留,过渡不被打断。 */
    private final Reveal advanced = new Reveal();
    /** 高级一节收着时内容到哪(相对表单顶)、全开时到哪;每帧按 {@link #advanced} 在两者之间取。 */
    private int baseH, fullH;
    /** 厂家、型号共用的选择框;随每次重排换新(它的搜索框是屏幕上的真输入框,屏幕重建时一起没了)。 */
    private PickerBox picker = new PickerBox();

    private TextField nameField, keyField, baseUrlField;
    private Button checkButton;
    private InlineAlert resultAlert;
    private boolean checking;
    /** 检测的答复按请求号交回,只认最近一次发的那个(表单重开后,旧的答复也不认)。 */
    private final LatestReply checkReply = new LatestReply();
    private List<ThinkingLevel> thinkingLevels = List.of(ThinkingLevel.AUTO);

    /** 服务端对目录问题的最近一次答案(厂家、草稿解析出的端点)。 */
    private ServerLibraries.CatalogAnswer answer = ServerLibraries.CatalogAnswer.EMPTY;
    /** 目录问题的答复按请求号交回,只认最近一次问的那个,更早的答复让位给它。 */
    private final LatestReply askReply = new LatestReply();
    /** 最近一次问的答复到了,还没换上。 */
    private boolean answerArrived = true;
    /** 当前厂家的全部型号:问时要了才有;{@link #modelsFor} 是它们属于哪个厂家,{@link #modelsAsked} 是已经在要的那个厂家。 */
    private List<ServerLibraries.CatalogModel> models = List.of();
    private String modelsFor = "";
    private String modelsAsked = "";
    /** 上一次问的草稿里会改变答案的那几项:没变就不再问。 */
    private String askedKey = "";

    public ProfileFormPanel(Consumer<Draft> onSave, Runnable onCancel,
                            java.util.function.Function<String, String> keyOf) {
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.keyOf = keyOf;
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
        this.advanced.snap(false);
        this.askedKey = "";
        this.checking = false;
        checkReply.cancel();
    }

    /** 下拉与选择框不得越出的区域(通常是整个设置窗口)。在 build 之前给。 */
    public void setWindow(UiRoot.Viewport window) {
        this.window = window;
    }

    public void build(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        fixedUi.clear();
        ui.setViewport(window);
        picker = new PickerBox();

        pollAnswer();
        if (draft.provider == null || draft.provider.isBlank()) {
            List<ServerLibraries.Vendor> popular = answer.vendors().stream().filter(ServerLibraries.Vendor::popular).toList();
            adaptToVendor(popular.isEmpty() ? ConnectionProfile.CUSTOM : popular.get(0).id());
        }
        ask();
        ServerLibraries.Vendor vendor = vendor();
        ServerLibraries.CatalogModel model = model();

        int ry = y;
        Label nameLabel = labelWidget(x, ry, ModLanguageData.Keys.PROVIDER_FORM_NAME);
        ry += NumenStyle.LABEL_PITCH;
        nameField = ui.add(new TextField(draft.name, v -> draft.name = v).withLabel(nameLabel));
        nameField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        ry = label(x, ry, ModLanguageData.Keys.GUI_PROVIDERS_VENDOR);
        PickField vendorField = ui.add(new PickField(vendorName(vendor),
                vendor == null ? "" : vendor.host(), this::openVendorPicker));
        vendorField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        baseUrlField = null;
        if (vendor == null) {
            // 自定义厂家(或目录里已经没有的厂家):地址和协议全由档案给
            ry = label(x, ry, ModLanguageData.Keys.GUI_PROVIDERS_URL);
            baseUrlField = addBaseUrlField(x, ry);
            ry += NumenStyle.ROW_PITCH;

            ry = label(x, ry, ModLanguageData.Keys.PROVIDER_FORM_PROVIDER);
            List<String> protocols = List.of(t(ModLanguageData.Keys.GUI_PROVIDERS_PROTO_CHAT),
                    t(ModLanguageData.Keys.GUI_PROVIDERS_PROTO_RESPONSES),
                    t(ModLanguageData.Keys.GUI_PROVIDERS_PROTO_ANTHROPIC));
            Dropdown protocol = ui.add(new Dropdown(protocols, protocolIndex(), i -> {
                draft.protocol = Api.values()[i].id();
                ask();
            }));
            protocol.setBounds(x, ry, w, NumenStyle.CONTROL_H);
            ry += NumenStyle.ROW_PITCH;
        }

        ry = label(x, ry, ModLanguageData.Keys.GUI_PROVIDERS_MODEL);
        PickField modelField = ui.add(new PickField(model == null ? draft.model : model.name(), draft.model,
                this::openModelPicker).placeholder(t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_MODEL)));
        modelField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        List<TagRow.Tag> tags = modelTags(model);
        var font = Minecraft.getInstance().font;
        int tagsH = TagRow.height(font::width, font.lineHeight + 2, tags, w);
        if (tagsH > 0) {
            TagRow row = ui.add(new TagRow(tags));
            row.setBounds(x, ry + NumenStyle.CONTROL_H + 3, w, tagsH);
            ry += NumenStyle.CONTROL_H + 3 + tagsH + 5;
        } else {
            ry += NumenStyle.ROW_PITCH;
        }

        Label keyLabel = labelWidget(x, ry, ModLanguageData.Keys.GUI_SETTINGS_API_KEY);
        ry += NumenStyle.LABEL_PITCH;
        keyField = ui.add(new TextField(draft.apiKey, v -> draft.apiKey = v).masked(true)
                .withLabel(keyLabel));
        keyField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 单一自适应思考控件:列哪几档、每档右边的说明都来自解析出的端点的思考计划——请求体写的也是它,
        // 所以选得到的每一档都真会改变请求。计划里只剩自动(型号不支持、手填的型号、这个端点发不出)时整栏不出。
        ServerLibraries.Preview preview = answer.preview();
        thinkingLevels = preview == null ? List.of(ThinkingLevel.AUTO)
                : preview.steps().stream().map(ThinkingPlan.Step::level).toList();
        if (preview != null && !preview.autoOnly()) {
            ry = label(x, ry, ModLanguageData.Keys.GUI_PROVIDERS_THINKING);
            List<String> levelNames = new ArrayList<>();
            List<String> levelNotes = new ArrayList<>();
            for (ThinkingPlan.Step step : preview.steps()) {
                levelNames.add(levelLabel(step));
                levelNotes.add(levelNote(step.wire()));
            }
            Dropdown thinking = ui.add(new Dropdown(levelNames, thinkingLevels.indexOf(preview.effective()),
                    this::onThinkingPicked).details(levelNotes));
            thinking.setBounds(x, ry, Math.min(w, 260), NumenStyle.CONTROL_H);
            ry += NumenStyle.ROW_PITCH;
        }

        Disclosure adv = ui.add(new Disclosure(t("numen.brain.advanced"), advanced));
        adv.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        baseH = ry + NumenStyle.CONTROL_H - y;
        // 高级一节的行一直摆着,开合只是露出多高(见 applyReveal):行不重建,输入框里的字也不丢
        ry += NumenStyle.CONTROL_H + 4;
        if (vendor != null) {
            // 自定义厂家的地址已经在上面,这里不再放第二个入口
            ry = label(x, ry, ModLanguageData.Keys.GUI_PROVIDERS_BASE_OVERRIDE);
            baseUrlField = addBaseUrlField(x, ry);
            ry += NumenStyle.ROW_PITCH;
        }
        // 代理按档案走:国内外站点常需不同走线,留空跟随全局(/numen 里设)。
        ry = label(x, ry, ModLanguageData.Keys.GUI_PROVIDERS_PROXY);
        TextField proxyField = ui.add(new TextField(draft.proxy, v -> draft.proxy = v)
                .placeholder(t(ModLanguageData.Keys.GUI_PROVIDERS_PROXY_HINT)));
        proxyField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 上下文长度:压缩闸门与面板水位都按这个窗口算——填错太小会提前压缩,填错太大会顶爆上游。
        ry = label(x, ry, ModLanguageData.Keys.GUI_PROVIDERS_CTX);
        TextField ctxField = ui.add(new TextField(draft.ctx, v -> draft.ctx = v).numeric()
                .placeholder(t(ModLanguageData.Keys.GUI_PROVIDERS_CTX_HINT)));
        ctxField.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        // 温度与输出上限并排:都是“不填用厂家默认”的生成参数
        int half = (w - 8) / 2;
        labelWidgetAt(x, ry, half, ModLanguageData.Keys.GUI_PROVIDERS_TEMP);
        labelWidgetAt(x + half + 8, ry, half, ModLanguageData.Keys.GUI_PROVIDERS_MAX_OUT);
        ry += NumenStyle.LABEL_PITCH;
        TextField temp = ui.add(new TextField(draft.temperature, v -> draft.temperature = v)
                .placeholder(t(ModLanguageData.Keys.GUI_PROVIDERS_DEFAULT_HINT)));
        temp.setBounds(x, ry, half, NumenStyle.CONTROL_H);
        TextField maxOut = ui.add(new TextField(draft.maxTokens, v -> draft.maxTokens = v).numeric()
                .placeholder(t(ModLanguageData.Keys.GUI_PROVIDERS_DEFAULT_HINT)));
        maxOut.setBounds(x + half + 8, ry, half, NumenStyle.CONTROL_H);
        fullH = ry + NumenStyle.CONTROL_H - y;

        // ---- 滚动记账:视口到收尾行上方为止,内容高随高级一节的开合在 baseH 与 fullH 之间走 ----
        scroll.measure(ui, x, y, w,
                NumenStyle.footerTop(y, h) - NumenStyle.HEADER_GAP - y,
                revealedH());

        // ---- 固定层:结果胶囊/按钮行——不随滚动 ----
        // 页面级 Alert:贴在收尾行上方悬浮,和检测、保存同在表单底部——操作结果的家(字段错误才内联)。
        resultAlert = fixedUi.add(new InlineAlert());
        resultAlert.setBounds(x, NumenStyle.footerTop(y, h) - 30, w, 24);
        checkButton = DialogButtons.left(fixedUi, x, y, h, this::runConnectivityCheck,
                t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK), t(ModLanguageData.Keys.GUI_PROVIDERS_CHECKING));
        DialogButtons.cancelSave(fixedUi, x, y, w, h, onCancel, this::save);
    }

    /** 草稿变了:按同样的几何整张重排(控件的值都从草稿来)。 */
    private void relayout() {
        build(x, y, w, h);
    }

    /** 服务端答了最近一次目录问题:换上它,型号随之更新。答案和原来不同才返回 {@code true}(要重排)。 */
    private boolean pollAnswer() {
        if (!answerArrived) {
            return false;
        }
        answerArrived = false;
        ServerLibraries.CatalogAnswer before = answer;
        answer = ServerLibraries.catalog();
        if (answer.models() != null) {
            models = answer.models();
            modelsFor = answer.modelsFor();
        }
        return !answer.equals(before);
    }

    /**
     * 问服务端这份草稿解析成什么(和当前厂家有哪些型号)。只有会改变答案的几项变了才问:厂家、型号、协议、兼容项。
     * key 与地址覆盖不带——答案与它们无关;上下文长度和思考档位是玩家在这一页上输入的,不用答案回头改写它们。
     */
    private void ask() {
        String provider = nz(draft.provider);
        ConnectionProfile profile = draft.toProfile().withApiKey("").withBaseUrl("");
        String key = profile.provider() + "|" + profile.protocol() + "|" + profile.model() + "|" + profile.compat();
        boolean wantModels = !provider.equals(modelsFor) && !provider.equals(modelsAsked);
        if (key.equals(askedKey) && !wantModels) {
            return;
        }
        askedKey = key;
        if (wantModels) {
            modelsAsked = provider;
        }
        JsonObject body = new JsonObject();
        body.add("draft", ProviderLibrary.profileToJson(profile));
        body.addProperty("models", wantModels);
        RemoteCompanions.library().request(Libraries.CATALOG, Libraries.LIST, body, askReply.take(reply -> {
            if (reply.ok()) {
                answerArrived = true;
            }
        }));
    }

    // ---- 宿主转发面 ----

    /** 此刻露出的内容高。 */
    private int revealedH() {
        return baseH + Math.round((fullH - baseH) * advanced.value());
    }

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (pollAnswer()) relayout();
        if (advanced.step(nowMs)) scroll.resize(revealedH());
        // 表单行进裁剪区(滚出视口的部分不画);下拉弹层、选择框与固定层在裁剪区外。
        scroll.beginClip(s);
        ui.renderContent(s, c, mouseX, mouseY, nowMs);
        scroll.endClip(s);
        scroll.renderThumb(s, c);
        fixedUi.render(s, c, mouseX, mouseY, nowMs);
        ui.renderOverlayLayer(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (ui.hasOverlay()) return ui.mouseClicked(mx, my, button);   // 弹层优先(可越出视口)
        if (fixedUi.mouseClicked(mx, my, button)) return true;
        // 视口外的行控件虽被裁掉,坐标上仍在——点击按可视区域裁决。
        return scroll.inside(my) && ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (ui.mouseScrolled(mx, my, delta)) return true;   // 下拉弹层滚动优先
        return scroll.scrolled(my, delta);
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

    // ---- 版式件 ----

    private int label(int lx, int ly, String key) {
        labelWidget(lx, ly, key);
        return ly + NumenStyle.LABEL_PITCH;
    }

    /** 标签控件本体:会报错的字段用 withLabel 认领它(出错时自动让位)。 */
    private Label labelWidget(int lx, int ly, String key) {
        return labelWidgetAt(lx, ly, 200, key);
    }

    private Label labelWidgetAt(int lx, int ly, int lw, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.SECONDARY));
        l.setBounds(lx, ly, lw, 9);
        return l;
    }

    /** 地址输入:占位是目录给的缺省地址(不含档案里的覆盖)。 */
    private TextField addBaseUrlField(int fx, int fy) {
        TextField f = ui.add(new TextField(draft.baseUrl, v -> draft.baseUrl = v));
        f.placeholder(answer.preview() == null ? "" : answer.preview().baseUrl());
        f.setBounds(fx, fy, w, NumenStyle.CONTROL_H);
        return f;
    }

    // ---- 目录与草稿 ----

    private ServerLibraries.Vendor vendor(String id) {
        for (ServerLibraries.Vendor v : answer.vendors()) {
            if (v.id().equals(id)) return v;
        }
        return null;
    }

    /** 草稿里的厂家;自定义厂家和目录里没有的厂家给 null。 */
    private ServerLibraries.Vendor vendor() {
        return vendor(nz(draft.provider));
    }

    /** 草稿里的型号;目录里没有的(手填的、已弃用的)给 null。 */
    private ServerLibraries.CatalogModel model() {
        if (!nz(draft.provider).equals(modelsFor)) return null;
        for (ServerLibraries.CatalogModel m : models) {
            if (m.id().equals(nz(draft.model))) return m;
        }
        return null;
    }

    private String vendorName(ServerLibraries.Vendor vendor) {
        if (vendor != null) return vendor.name();
        return ConnectionProfile.CUSTOM.equals(draft.provider) ? t(ModLanguageData.Keys.GUI_PROVIDERS_CUSTOM)
                : nz(draft.provider);
    }

    private int protocolIndex() {
        Api api = Api.byId(draft.protocol);
        return api == null ? 0 : api.ordinal();
    }

    /** 厂家变了:型号换成该厂家第一个能调工具的,思考回自动,地址与厂家专属的覆盖项清空回目录缺省。 */
    private void adaptToVendor(String vendorId) {
        draft.provider = vendorId;
        draft.apiKey = keyOf.apply(vendorId);
        draft.protocol = ConnectionProfile.CUSTOM.equals(vendorId) ? Api.CHAT.id() : "";
        draft.compat = null;
        ServerLibraries.Vendor vendor = vendor(vendorId);
        draft.model = vendor == null ? "" : vendor.defaultModel();
        draft.reasoningEffort = "";
        draft.baseUrl = "";
    }

    /** 型号下面的能力小标签,数字和能力取自服务端的解析结果与目录;目录里没有的型号只标“手填的型号”。 */
    private List<TagRow.Tag> modelTags(ServerLibraries.CatalogModel m) {
        List<TagRow.Tag> tags = new ArrayList<>();
        if (m == null) {
            if (!nz(draft.model).isBlank()) tags.add(new TagRow.Tag(t(ModLanguageData.Keys.GUI_PROVIDERS_TAG_MANUAL), false));
            return tags;
        }
        int ctx = answer.preview() == null ? 0 : answer.preview().contextWindow();
        if (ctx > 0) {
            tags.add(new TagRow.Tag(t(ModLanguageData.Keys.GUI_PROVIDERS_TAG_CONTEXT, TokenFormat.capacity(ctx)), false));
        }
        if (m.maxOutput() > 0) {
            tags.add(new TagRow.Tag(t(ModLanguageData.Keys.GUI_PROVIDERS_TAG_OUTPUT, TokenFormat.capacity(m.maxOutput())), false));
        }
        if (m.reasons()) tags.add(new TagRow.Tag(t(ModLanguageData.Keys.GUI_PROVIDERS_TAG_THINKING), false));
        if (!m.inputs().isEmpty()) {
            tags.add(new TagRow.Tag(t(ModLanguageData.Keys.GUI_PROVIDERS_TAG_MULTIMODAL,
                    m.inputs().stream().map(ProfileFormPanel::modality).collect(Collectors.joining(" · "))), false));
        }
        if (!m.toolCall()) tags.add(new TagRow.Tag(t(ModLanguageData.Keys.GUI_PROVIDERS_TAG_NO_TOOLS), true));
        return tags;
    }

    private static String modality(String id) {
        return switch (id) {
            case "text" -> t(ModLanguageData.Keys.GUI_PROVIDERS_MODALITY_TEXT);
            case "image" -> t(ModLanguageData.Keys.GUI_PROVIDERS_MODALITY_IMAGE);
            case "pdf" -> t(ModLanguageData.Keys.GUI_PROVIDERS_MODALITY_PDF);
            case "audio" -> t(ModLanguageData.Keys.GUI_PROVIDERS_MODALITY_AUDIO);
            case "video" -> t(ModLanguageData.Keys.GUI_PROVIDERS_MODALITY_VIDEO);
            default -> id;
        };
    }

    // ---- 选择框 ----

    private void openVendorPicker() {
        List<ServerLibraries.Vendor> vendors = answer.vendors();
        PickerBox.Labels labels = new PickerBox.Labels(t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_VENDOR),
                t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_VENDOR_HINT, vendors.size()),
                t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_SEARCH), pickerKeys(),
                q -> t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_NO_MATCH, q));
        picker.open(ui, window.left(), window.top(), window.right() - window.left(),
                window.bottom() - window.top(), labels, null, q -> {
            String lq = q.toLowerCase(Locale.ROOT);
            List<PickerBox.Item> popular = vendors.stream().filter(ServerLibraries.Vendor::popular)
                    .filter(p -> matches(lq, p.name(), p.id(), p.host())).map(this::vendorRow).toList();
            List<PickerBox.Item> rest = vendors.stream().filter(p -> !p.popular())
                    .filter(p -> matches(lq, p.name(), p.id(), p.host()))
                    .sorted(Comparator.comparing(ServerLibraries.Vendor::name, String.CASE_INSENSITIVE_ORDER))
                    .map(this::vendorRow).toList();
            PickerBox.Item custom = new PickerBox.Item(ConnectionProfile.CUSTOM,
                    t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_CUSTOM),
                    t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_CUSTOM_DETAIL), List.of(), vendor() == null);
            List<PickerBox.Item> customRow = matches(lq, custom.title(), custom.id(), custom.detail())
                    ? List.of(custom) : List.of();
            if (!q.isEmpty()) {   // 搜索时不分节
                List<PickerBox.Item> all = new ArrayList<>(popular);
                all.addAll(rest);
                all.addAll(customRow);
                return List.of(new PickerBox.Section("", all));
            }
            return List.of(new PickerBox.Section(t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_POPULAR), popular),
                    new PickerBox.Section(t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_ALL), rest),
                    new PickerBox.Section("", customRow));
        }, null, item -> {
            if (item.id().equals(draft.provider)) return;
            adaptToVendor(item.id());
            relayout();
        });
    }

    private PickerBox.Item vendorRow(ServerLibraries.Vendor p) {
        return new PickerBox.Item(p.id(), p.name(), p.host(), List.of(), p.id().equals(draft.provider));
    }

    private void openModelPicker() {
        ServerLibraries.Vendor vendor = vendor();
        List<ServerLibraries.CatalogModel> offered = vendor == null || !vendor.id().equals(modelsFor) ? List.of() : models;
        PickerBox.Labels labels = new PickerBox.Labels(t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_MODEL),
                vendor == null ? t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_MODEL_HAND)
                        : t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_MODEL_HINT, vendor.name(), offered.size()),
                t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_SEARCH), pickerKeys(),
                // 没搜时列表就是空的(自定义厂家没有目录):提示该手填,而不是“没有找到「」”
                q -> q.isEmpty() ? t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_MODEL_HAND)
                        : t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_NO_MATCH, q));
        PickerBox.Table table = new PickerBox.Table(t(ModLanguageData.Keys.GUI_PROVIDERS_COL_MODEL), List.of(
                new PickerBox.Column(t(ModLanguageData.Keys.GUI_PROVIDERS_COL_CONTEXT), 5, true),
                new PickerBox.Column(t(ModLanguageData.Keys.GUI_PROVIDERS_COL_OUTPUT), 4, true),
                new PickerBox.Column(t(ModLanguageData.Keys.GUI_PROVIDERS_COL_THINKING), 3, false),
                new PickerBox.Column(t(ModLanguageData.Keys.GUI_PROVIDERS_COL_INPUT), 11, false)), 380);
        PickerBox.FreeEntry free = new PickerBox.FreeEntry(t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_FREE),
                q -> t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_FREE_TITLE, q),
                t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_FREE_DETAIL));
        // 发布日期新的在前;没写日期的排在后面(同日期保持目录里的顺序)
        Comparator<ServerLibraries.CatalogModel> newestFirst =
                Comparator.comparing(ServerLibraries.CatalogModel::releaseDate).reversed();
        picker.open(ui, window.left(), window.top(), window.right() - window.left(),
                window.bottom() - window.top(), labels, table, q -> {
            String lq = q.toLowerCase(Locale.ROOT);
            List<ServerLibraries.CatalogModel> hit = offered.stream().filter(m -> matches(lq, m.name(), m.id()))
                    .sorted(newestFirst).toList();
            List<PickerBox.Item> withTools = hit.stream().filter(ServerLibraries.CatalogModel::toolCall)
                    .map(this::modelRow).toList();
            List<PickerBox.Item> without = hit.stream().filter(m -> !m.toolCall()).map(this::modelRow).toList();
            boolean sectioned = q.isEmpty();   // 搜索时不分节
            return List.of(
                    new PickerBox.Section(sectioned ? t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_TOOLS) : "", withTools),
                    new PickerBox.Section(sectioned ? t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_NO_TOOLS) : "", without));
        }, free, item -> {
            if (item.id().equals(draft.model)) return;
            draft.model = item.id();
            draft.reasoningEffort = "";
            relayout();
        });
    }

    private PickerBox.Item modelRow(ServerLibraries.CatalogModel m) {
        String none = "—";
        String inputs = (m.inputs().isEmpty() ? "" : " ")
                + m.inputs().stream().map(ProfileFormPanel::modality).collect(Collectors.joining(" "));
        return new PickerBox.Item(m.id(), m.name(), m.id(), List.of(
                m.contextWindow() > 0 ? TokenFormat.capacity(m.contextWindow()) : none,
                m.maxOutput() > 0 ? TokenFormat.capacity(m.maxOutput()) : none,
                m.reasons() ? "✓" : none,
                modality("text") + inputs), m.id().equals(draft.model));
    }

    private List<String> pickerKeys() {
        return List.of(t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_KEY_MOVE),
                t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_KEY_ENTER), t(ModLanguageData.Keys.GUI_PROVIDERS_PICK_KEY_ESC));
    }

    /** 搜索词(已小写)是否出现在任一字段里;没搜就都匹配。 */
    private static boolean matches(String lowerQuery, String... fields) {
        if (lowerQuery.isEmpty()) return true;
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
        }
        return false;
    }

    // ---- 思考 ----

    private static String levelLabel(ThinkingPlan.Step step) {
        if (step.wire().kind() == ThinkingPlan.Wire.Kind.ON) return t(ModLanguageData.Keys.GUI_PROVIDERS_THINKING_ON);
        return t(switch (step.level()) {
            case AUTO -> ModLanguageData.Keys.GUI_PROVIDERS_THINKING_AUTO;
            case OFF -> ModLanguageData.Keys.GUI_PROVIDERS_THINKING_OFF;
            case LOW -> ModLanguageData.Keys.GUI_PROVIDERS_EFFORT_LOW;
            case MEDIUM -> ModLanguageData.Keys.GUI_PROVIDERS_EFFORT_MEDIUM;
            case HIGH -> ModLanguageData.Keys.GUI_PROVIDERS_EFFORT_HIGH;
            case MAX -> ModLanguageData.Keys.GUI_PROVIDERS_EFFORT_MAX;
        });
    }

    /** 一档右边的灰色说明,取自思考计划给出的“实际会发什么”。 */
    private static String levelNote(ThinkingPlan.Wire wire) {
        return switch (wire.kind()) {
            case AUTO -> t(ModLanguageData.Keys.GUI_PROVIDERS_NOTE_AUTO);
            case OFF -> t(ModLanguageData.Keys.GUI_PROVIDERS_NOTE_OFF);
            case ON -> t(ModLanguageData.Keys.GUI_PROVIDERS_NOTE_ON);
            case EFFORT -> t(ModLanguageData.Keys.GUI_PROVIDERS_NOTE_EFFORT, wire.effort());
            case BUDGET -> t(ModLanguageData.Keys.GUI_PROVIDERS_NOTE_BUDGET, wire.tokens());
        };
    }

    private void onThinkingPicked(int index) {
        if (index >= 0 && index < thinkingLevels.size()) {
            draft.reasoningEffort = thinkingLevels.get(index).id();
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void save() {
        if (draft.name == null || draft.name.isBlank()) {
            nameField.setError(t(ModLanguageData.Keys.GUI_INLINE_REQUIRED));   // 校验错误内联在错误发生处
            return;
        }
        onSave.accept(draft);
    }

    /** 检测草稿当前值——存不存都能先试连。试连是服务端发的请求,这里只发草稿、等结局({@link #settleCheck})。 */
    private void runConnectivityCheck() {
        if (checking) return;
        checking = true;
        checkButton.setEnabled(false);
        checkButton.setLabel(t(ModLanguageData.Keys.GUI_PROVIDERS_CHECKING));
        resultAlert.show(InlineAlert.Severity.INFO, t(ModLanguageData.Keys.GUI_PROVIDERS_CHECKING));
        JsonObject body = new JsonObject();
        body.add("draft", ProviderLibrary.profileToJson(draft.toProfile()));
        body.addProperty("key", draft.apiKey);
        RemoteCompanions.library().request(Libraries.PROVIDER_CHECK, Libraries.RUN, body, checkReply.take(this::settleCheck));
    }

    /** 服务端交回了这一次检测的结局:缺 key、缺地址标在字段上,连得通连不通进结果胶囊;没办成的(请求有毛病)把原因挂在胶囊上。 */
    private void settleCheck(RemoteLibrary.Reply reply) {
        checking = false;
        checkButton.setEnabled(true);
        checkButton.setLabel(t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK));
        if (!reply.ok()) {
            resultAlert.show(InlineAlert.Severity.ERROR, reply.problem());
            return;
        }
        ServerLibraries.Check check = ServerLibraries.check();
        switch (check.state()) {
            case "need_key" -> {
                resultAlert.clear();
                keyField.setError(t(ModLanguageData.Keys.GUI_INLINE_NEED_KEY));
            }
            case "need_url" -> {
                resultAlert.clear();
                advanced.setOpen(true);   // 目录厂家没有固定地址:地址覆盖在高级里,展开它再标错
                baseUrlField.setError(t(ModLanguageData.Keys.GUI_INLINE_NEED_URL));
            }
            // 成功=知道了就行,2.5s 自动淡出;失败驻留到被下次操作替换。
            case "ok" -> resultAlert.show(InlineAlert.Severity.SUCCESS, t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_OK), 2_500);
            default -> resultAlert.show(InlineAlert.Severity.ERROR, CauseWords.text(check.cause()));
        }
    }

    private static String t(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
