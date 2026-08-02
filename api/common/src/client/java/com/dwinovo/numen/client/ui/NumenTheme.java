package com.dwinovo.numen.client.ui;

/**
 * NumenUI 设计令牌:主题 = 一张全语义色表。组件只引用语义槽位
 * (textPrimary/hover/...),永不硬编码色值——换主题、调配色都只动这里。
 */
public enum NumenTheme {
    DARK,
    LIGHT;

    /** 全部语义槽位。新增控件先问"现有槽位够不够",不够才添——槽位膨胀即失控。 */
    public record Colors(
            int panelBg, int sectionBg, int divider,
            int textPrimary, int textSecondary, int textMuted,
            int accent, int danger, int success,
            int inputBg, int inputBorder,
            int hover, int selected,
            int badgeBg, int badgeText,
            int toastInfoBg, int toastWarnBg, int toastErrorBg, int toastText) {}

    public Colors colors() {
        return switch (this) {
            case DARK -> new Colors(
                    0xF01A1A1E, 0xFF232328, 0xFF33333A,
                    0xFFEDEDEF, 0xFFA8A8B0, 0xFF6E6E78,
                    0xFF4C8DFF, 0xFFE5534B, 0xFF57AB5A,
                    0xFF2A2A30, 0xFF3D3D46,
                    0xFF2F2F36, 0xFF3A4E77,
                    0xFF31313A, 0xFFC8C8D2,
                    0xF02A2A30, 0xF0574625, 0xF05C2B28, 0xFFEDEDEF);
            case LIGHT -> new Colors(
                    0xF0F4F4F6, 0xFFFFFFFF, 0xFFDDDDE2,
                    0xFF1E1E24, 0xFF55555E, 0xFF9090A0,
                    0xFF2F6FE0, 0xFFC7392F, 0xFF3E8E41,
                    0xFFECECEF, 0xFFCFCFD8,
                    0xFFE6E6EB, 0xFFCBDAF5,
                    0xFFE2E2E8, 0xFF3A3A44,
                    0xF0FFFFFF, 0xF0FFF3D6, 0xF0FDE3E0, 0xFF1E1E24);
        };
    }
}
