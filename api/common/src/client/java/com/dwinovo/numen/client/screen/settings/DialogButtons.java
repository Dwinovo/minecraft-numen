package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.DialogBox;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 新建/编辑表单卡底部那一排纯字钮,照 Telegram 对话框排:右下角"取消""保存",左下角可放一个
 * 附加动作(检测、试听——Telegram 的 addLeftButton)。卡片右上角没有 ×:Esc 与"取消"就是关。
 * 钮的样子、宽、间距与面板里的对话框卡、确认卡是同一种({@link DialogBox#placeButtons});
 * 这里只定表单卡上它们贴在哪:收尾行({@link NumenStyle#footerTop})的右端与左端。
 */
final class DialogButtons {

    private DialogButtons() {}

    /**
     * 右下角的取消、保存,贴表单区 {@code (x, y, w, h)} 的底边与右边。
     * 返回保存钮——皮肤签名排队时要锁住它。
     */
    static Button cancelSave(UiRoot ui, int x, int y, int w, int h, Runnable onCancel, Runnable onSave) {
        Button cancel = ui.add(new Button(I18n.get(ModLanguageData.Keys.GUI_SETTINGS_CANCEL),
                Button.Style.LINK, onCancel));
        Button save = ui.add(new Button(I18n.get(ModLanguageData.Keys.GUI_SETTINGS_SAVE),
                Button.Style.LINK, onSave));
        DialogBox.placeButtons(Minecraft.getInstance().font::width, x + w, NumenStyle.footerTop(y, h), save, cancel);
        return save;
    }

    /**
     * 左下角的附加动作(检测、试听)。{@code laterLabels} 是它进行中会换成的字(检测中…),
     * 宽按最长的那份给,换字时钮不跳、字不被截。
     */
    static Button left(UiRoot ui, int x, int y, int h, Runnable action, String label, String... laterLabels) {
        var font = Minecraft.getInstance().font;
        int textW = font.width(label);
        for (String l : laterLabels) textW = Math.max(textW, font.width(l));
        Button b = ui.add(new Button(label, Button.Style.LINK, action));
        b.setBounds(x, NumenStyle.footerTop(y, h), DialogBox.buttonW(textW), DialogBox.BUTTON_H);
        return b;
    }
}
