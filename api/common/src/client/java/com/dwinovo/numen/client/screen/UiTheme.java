package com.dwinovo.numen.client.screen;

import java.util.List;

/**
 * Numen 面板的配色:和 Telegram Desktop 一样只有日间、夜间两套,☰ 菜单里的夜间模式在两套之间切。
 * 颜色取自 Telegram 自带的主题(lib_ui 的 colors.palette、Day 蓝色版、night 主题),形状仍是 MC 的方角。
 *
 * <p>每个槽对应 Telegram 里的一个颜色:聊天区的背景({@code ground},Telegram 的聊天壁纸)和窗口底色
 * ({@code band}:左栏、抬头、资料页、设置页、菜单)是两回事;她的与主人的气泡、气泡里的时间、
 * 左栏选中行、强调色填充与强调色文字各有各的槽,不从别的颜色推。
 *
 * <p>All colours are ARGB.
 *
 * @param ground  聊天区底色(Telegram 聊天壁纸的主色)
 * @param dot     聊天区点纹
 * @param band    窗口底色(windowBg):左栏、抬头、资料页、设置页、菜单
 * @param onBand  窗口底色上的字
 * @param border  面板外框与分隔线
 * @param field   搜索框、输入框的底(filterInputInactiveBg)
 * @param cta     强调色填充(windowBgActive):按钮、未读数、发送键
 * @param onCta   强调色填充上的字
 * @param accent  强调色文字(windowActiveTextFg):@ 到的名字、回复条
 * @param aiFill  她的气泡(msgInBg)
 * @param ownFill 主人的气泡(msgOutBg)
 * @param inMeta  她的气泡里的时间(msgInDateFg)
 * @param outMeta 主人的气泡里的时间(msgOutDateFg)
 * @param active  左栏选中行(dialogsBgActive)
 * @param onActive 选中行上的字
 * @param serviceBg 服务消息的半透明底(msgServiceBg):日期牌、未读消息、清空记录之类的居中小条
 * @param serviceFg 服务消息的字(msgServiceFg)
 * @param botKbOver 指针移到消息下面的内联按钮上时叠的一层(msgBotKbOverBgAdd);按钮本身的底与字是 serviceBg、serviceFg
 * @param botKbRipple 按下内联按钮时漾开的那一圈(msgBotKbRippleBg)
 */
public record UiTheme(
        String id, String label,
        int ground, int dot, int band, int onBand, int border,
        int text, int textDim, int field,
        int cta, int onCta, int accent,
        int aiFill, int ownFill, int inMeta, int outMeta,
        int active, int onActive,
        int reply, int ok, int run, int fail,
        int serviceBg, int serviceFg, int botKbOver, int botKbRipple) {

    /** 日间:Telegram 的 Day(蓝色版),聊天区用浅一档的蓝灰,淡字在上面照样读得清。 */
    public static final UiTheme LIGHT = new UiTheme("light", "Day",
            0xFFD7E3EC, 0x142B5278, 0xFFFFFFFF, 0xFF000000, 0xFFAEBBC6,
            0xFF000000, 0xFF8A8F94, 0xFFF1F1F1,
            0xFF40A7E3, 0xFFFFFFFF, 0xFF168ACD,
            0xFFFFFFFF, 0xFFDEF1FD, 0xFFA0ACB6, 0xFF86A8C2,
            0xFF419FD9, 0xFFFFFFFF,
            0xFF1C7C9C, 0xFF4AB44A, 0xFFE0A030, 0xFFD14E4E,
            0x59005180, 0xFFFFFFFF, 0x20FFFFFF, 0x20000000);

    /** 夜间:Telegram 的 night 主题原值。 */
    public static final UiTheme DARK = new UiTheme("dark", "Night",
            0xFF0E1621, 0x0CFFFFFF, 0xFF17212B, 0xFFF5F5F5, 0xFF0B1118,
            0xFFF5F5F5, 0xFF708499, 0xFF242F3D,
            0xFF5288C1, 0xFFFFFFFF, 0xFF6AB3F3,
            0xFF182533, 0xFF2B5278, 0xFF6D7F8F, 0xFF7DA8D3,
            0xFF2B5278, 0xFFFFFFFF,
            0xFF6AB3F3, 0xFF57AB5A, 0xFFC79432, 0xFFEC3942,
            0xD5213040, 0xFFFFFFFF, 0x0F80B1DB, 0x0B92C0E5);

    public static final List<UiTheme> ALL = List.of(LIGHT, DARK);

    private static UiTheme current = LIGHT;

    public static UiTheme current() { return current; }

    /** 按 id 换主题;认不出的 id(存档里还记着已经删掉的旧主题)落回日间。 */
    public static void set(String id) {
        for (UiTheme t : ALL) {
            if (t.id().equals(id)) { current = t; return; }
        }
        current = LIGHT;
    }

    // ---- 由上面的槽推出来的几样:只是深浅一档的变化,不是新的颜色 ----

    /** 夜间还是日间:按聊天区底色的亮度判,☰ 菜单的夜间模式也按它说现在是哪边。 */
    public boolean isDark() {
        int r = (ground >> 16) & 0xFF, g = (ground >> 8) & 0xFF, b = ground & 0xFF;
        return (r * 3 + g * 6 + b) / 10 < 96;
    }

    /** Faintest text tier (placeholders, pending items, empty-state hints). */
    public int faint() { return mix(textDim, field, 0.5f); }
    /** 窗口底色上的淡字(抬头第二行、人设名)。 */
    public int onBandFaint() { return mix(onBand, band, 0.5f); }
    /** 窗口底色上浮起来的东西(菜单、卡片、下拉)的描边。 */
    public int aiBorder() { return isDark() ? mix(band, 0xFFFFFFFF, 0.12f) : mix(band, 0xFF000000, 0.12f); }
    /** 窗口底色上的一块"纸面"(资料卡、设置里的分区底):比底色深浅一档。 */
    public int surface() { return isDark() ? mix(band, 0xFFFFFFFF, 0.04f) : mix(band, 0xFF000000, 0.03f); }
    public int surfaceBorder() { return isDark() ? mix(band, 0xFFFFFFFF, 0.12f) : mix(band, 0xFF000000, 0.10f); }
    /** 指针悬停的行(windowBgOver)。 */
    public int over() { return isDark() ? mix(band, 0xFFFFFFFF, 0.05f) : mix(band, 0xFF000000, 0.055f); }
    /** Queued prompt: a half-present owner bubble. */
    public int queuedFill() { return (ownFill & 0xFFFFFF) | 0x80000000; }
    /** Tool chip: translucent wash — status, not a message(暗主题下用亮色洗)。 */
    public int chipFill() { return isDark() ? 0x28FFFFFF : (border & 0xFFFFFF) | 0x22000000; }
    /** Sidebar card (plan panel): a fainter wash of the same tone. */
    public int cardFill() { return isDark() ? 0x14FFFFFF : (border & 0xFFFFFF) | 0x16000000; }

    /** 群里成员名字色的种数:Telegram 的七色(红、橙、紫、绿、青、蓝、粉),按人取模挑一种。 */
    public static final int PEER_COLORS = 7;
    private static final int[] PEER_NAME_DAY = {
            0xFFC03D33, 0xFFCE671B, 0xFF8544D6, 0xFF4FAD2D, 0xFF2996AD, 0xFF168ACD, 0xFFCD4073};
    private static final int[] PEER_NAME_NIGHT = {
            0xFFFB6169, 0xFFFAA357, 0xFFB48BF2, 0xFF85DE85, 0xFF62D4E3, 0xFF65BDF3, 0xFFFF5694};

    /** 第 {@code i} 种成员名字色:日间、夜间各用 Telegram 那一套。 */
    public int peerName(int i) {
        return (isDark() ? PEER_NAME_NIGHT : PEER_NAME_DAY)[Math.floorMod(i, PEER_COLORS)];
    }

    /** Per-channel RGB mix of {@code a} toward {@code b} by {@code t}; alpha forced opaque. */
    public static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    // ---- 选择的持久化归 ClientPrefs;这里只管颜色 ----

    /** 客户端启动:把主人存的主题应用上。 */
    public static void init(java.nio.file.Path numenConfigDir) {
        com.dwinovo.numen.client.data.ClientPrefs.init(numenConfigDir);
        set(com.dwinovo.numen.client.data.ClientPrefs.theme());
    }

    /** 主题选择器的入口:切换并记住。 */
    public static void select(String id) {
        set(id);
        com.dwinovo.numen.client.data.ClientPrefs.setTheme(id);
    }
}
