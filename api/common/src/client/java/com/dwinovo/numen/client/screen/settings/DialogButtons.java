package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 新建/编辑表单卡底部那一排纯字钮,照 Telegram 对话框排:右下角"取消""保存",左下角可放一个
 * 附加动作(检测、试听——Telegram 的 addLeftButton)。卡片右上角没有 ×:Esc 与"取消"就是关。
 * 五张表单卡共用这一处,按钮的位置、宽度、间距不各算各的。
 */
final class DialogButtons {

    /** 纯字钮的宽 = 字宽 + 两侧各这么多(悬停浮出的浅底要包住字)。 */
    private static final int TEXT_PAD = 8;
    /** 取消与保存之间的间距。 */
    private static final int GAP = 2;

    private DialogButtons() {}

    /**
     * 右下角的取消、保存,贴表单区 {@code (x, y, w, h)} 的底边与右边。
     * 返回保存钮——皮肤签名排队时要锁住它。
     */
    static Button cancelSave(UiRoot ui, int x, int y, int w, int h, Runnable onCancel, Runnable onSave) {
        int by = NumenStyle.footerTop(y, h);
        String saveLabel = I18n.get(ModLanguageData.Keys.GUI_SETTINGS_SAVE);
        int saveW = width(saveLabel);
        Button save = ui.add(new Button(saveLabel, Button.Style.LINK, onSave));
        save.setBounds(x + w - saveW, by, saveW, NumenStyle.CONTROL_H);
        String cancelLabel = I18n.get(ModLanguageData.Keys.GUI_SETTINGS_CANCEL);
        int cancelW = width(cancelLabel);
        ui.add(new Button(cancelLabel, Button.Style.LINK, onCancel))
                .setBounds(x + w - saveW - GAP - cancelW, by, cancelW, NumenStyle.CONTROL_H);
        return save;
    }

    /**
     * 左下角的附加动作(检测、试听)。{@code laterLabels} 是它进行中会换成的字(检测中…),
     * 宽按最长的那份给,换字时钮不跳、字不被截。
     */
    static Button left(UiRoot ui, int x, int y, int h, Runnable action, String label, String... laterLabels) {
        int w = width(label);
        for (String l : laterLabels) w = Math.max(w, width(l));
        Button b = ui.add(new Button(label, Button.Style.LINK, action));
        b.setBounds(x, NumenStyle.footerTop(y, h), w, NumenStyle.CONTROL_H);
        return b;
    }

    private static int width(String label) {
        return Minecraft.getInstance().font.width(label) + TEXT_PAD * 2;
    }
}
