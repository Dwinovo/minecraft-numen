package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.brain.OwnerSchedules;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.DialogBox;
import com.dwinovo.numen.api.client.ui.widget.MultilineTextField;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.UUID;

/**
 * 一条定时的编辑卡:到点对她说的话、间隔(一个数加一个单位:游戏小时或游戏日,玩家看得懂的单位;存的是游戏刻)。新增时没有号(服务端起),
 * 编辑时多一个红字的"删除"(点了过确认卡)。条数与间隔够不够格由服务端判,没办成的原因由宿主放进提示。
 */
public final class ScheduleEditPanel extends ModalCard {

    /** 屏幕侧的面:哪只同伴、编辑哪一条({@code null} = 新增)、存、删、关卡。 */
    public interface Host {
        UUID companion();

        ServerLibraries.Schedule schedule();

        void onSave(String id, String text, long everyTicks);

        void onDelete(String id);

        void onClose();
    }

    /** 一个游戏小时、一个游戏日各多少刻。 */
    private static final long HOUR_TICKS = 1000L;
    private static final long DAY_TICKS = OwnerSchedules.DAY_TICKS;
    private static final int BODY_H = 44;
    private static final int GAP = 4;

    private final Host host;
    private String text = "";
    private String amount = "1";
    private boolean days = true;
    private Button save;

    public ScheduleEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 开卡:编辑时草稿是这条现在的样子(整日的按日显示,否则按小时),新增是一个游戏日一次。 */
    @Override
    void reset() {
        text = "";
        amount = "1";
        days = true;
        ServerLibraries.Schedule s = host.schedule();
        if (s != null) {
            text = s.text();
            days = s.every() % DAY_TICKS == 0;
            amount = String.valueOf(Math.max(1, Math.round(s.every() / (float) (days ? DAY_TICKS : HOUR_TICKS))));
        }
    }

    @Override
    int height() {
        return heightFor(10 + BODY_H + GAP + FIELD_H + GAP + ROW_H);
    }

    @Override
    protected void layout(int top) {
        title(I18n.get(host.schedule() == null ? ModLanguageData.Keys.SCHEDULE_TITLE_NEW
                : ModLanguageData.Keys.SCHEDULE_TITLE_EDIT));
        int fx = x + DialogBox.PAD_X;
        int fw = w - DialogBox.PAD_X * 2;
        int ry = top;

        MultilineTextField words = multiline(fx, ry, fw, BODY_H, I18n.get(ModLanguageData.Keys.SCHEDULE_TEXT),
                new MultilineTextField(text, v -> {
                    text = v;
                    refresh();
                }).maxLength(OwnerSchedules.MAX_TEXT));
        ry += 10 + BODY_H + GAP;

        field(fx, ry, fw, I18n.get(ModLanguageData.Keys.SCHEDULE_EVERY), new TextField(amount, v -> {
            amount = v;
            refresh();
        }).numeric());
        ry += FIELD_H + GAP;

        select(ry, I18n.get(ModLanguageData.Keys.SCHEDULE_UNIT), List.of(
                        I18n.get(ModLanguageData.Keys.SCHEDULE_UNIT_HOUR), I18n.get(ModLanguageData.Keys.SCHEDULE_UNIT_DAY)),
                days ? 1 : 0, i -> days = i == 1);

        save = buttons(I18n.get(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onClose,
                I18n.get(ModLanguageData.Keys.GUI_SETTINGS_SAVE), this::save);
        refresh();
        if (host.schedule() != null) {
            Button delete = ui.add(new Button(I18n.get(ModLanguageData.Keys.SCHEDULE_DELETE), Button.Style.LINK_DANGER,
                    () -> host.onDelete(host.schedule().id())));
            delete.setBounds(x + DialogBox.BUTTON_RIGHT, DialogBox.buttonTop(y, h),
                    DialogBox.buttonW(Minecraft.getInstance().font.width(delete.label())), DialogBox.BUTTON_H);
        }
        ui.requestFocus(words);
    }

    /** 话不空、数是正整数才能存;够不够格(间隔的上下限、条数)服务端说了算。 */
    private void refresh() {
        if (save != null) save.setEnabled(!text.isBlank() && count() >= 1);
    }

    private int count() {
        try {
            return Integer.parseInt(amount.strip());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private void save() {
        host.onSave(host.schedule() == null ? null : host.schedule().id(), text.strip(),
                count() * (days ? DAY_TICKS : HOUR_TICKS));
    }
}
