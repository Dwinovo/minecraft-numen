package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.agent.memory.NoteBook;
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
 * 一条札记的编辑卡:一句话(她在索引里看到的那行)、类型、正文。新增时没有名字(服务端自动起),编辑时多一个红字的"删除"
 * (点了过确认卡)。版式照别的卡:字段、选择行、右下取消/保存;正文是多行框,回车在里面是换行。
 */
public final class NoteEditPanel extends ModalCard {

    /** 屏幕侧的面:哪只同伴、编辑哪一条({@code null} = 新增)、存、删、关卡。 */
    public interface Host {
        UUID companion();

        String note();

        void onSave(String name, String description, String type, String content);

        void onDelete(String name);

        void onClose();
    }

    /** 正文框的高。 */
    private static final int BODY_H = 72;
    private static final int GAP = 4;

    private final Host host;
    private String description = "";
    private String type = NoteBook.DEFAULT_TYPE;
    private String content = "";

    public NoteEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI——见 McTextInput。
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 开卡:编辑时草稿是服务端刚给的全文,新增是空白。 */
    @Override
    void reset() {
        description = "";
        type = NoteBook.DEFAULT_TYPE;
        content = "";
        if (host.note() != null) {
            ServerLibraries.NoteText text = ServerLibraries.noteText(host.companion(), host.note());
            if (text != null) {
                description = text.description();
                type = text.type();
                content = text.content() == null ? "" : text.content();
            }
        }
    }

    @Override
    int height() {
        return heightFor(FIELD_H + GAP + ROW_H + GAP + 10 + BODY_H);
    }

    @Override
    protected void layout(int top) {
        title(I18n.get(host.note() == null ? ModLanguageData.Keys.NOTE_TITLE_NEW : ModLanguageData.Keys.NOTE_TITLE_EDIT));
        int fx = x + DialogBox.PAD_X;
        int fw = w - DialogBox.PAD_X * 2;
        int ry = top;

        TextField line = field(fx, ry, fw, I18n.get(ModLanguageData.Keys.NOTE_DESCRIPTION),
                new TextField(description, v -> {
                    description = v;
                    if (save != null) save.setEnabled(!description.isBlank());
                }));
        ry += FIELD_H + GAP;

        List<String> labels = NoteBook.TYPES.stream().map(t -> I18n.get("numen.note.type." + t)).toList();
        select(ry, I18n.get(ModLanguageData.Keys.NOTE_TYPE), labels, Math.max(0, NoteBook.TYPES.indexOf(type)),
                i -> type = NoteBook.TYPES.get(i));
        ry += ROW_H + GAP;

        multiline(fx, ry, fw, BODY_H, I18n.get(ModLanguageData.Keys.NOTE_CONTENT),
                new MultilineTextField(content, v -> content = v).maxLength(4096));

        save = buttons(I18n.get(ModLanguageData.Keys.GUI_SETTINGS_CANCEL), host::onClose,
                I18n.get(ModLanguageData.Keys.GUI_SETTINGS_SAVE),
                () -> host.onSave(host.note(), description.strip(), type, content));
        save.setEnabled(!description.isBlank());
        if (host.note() != null) {
            // 删除在左下,红字;右下是取消/保存
            Button delete = ui.add(new Button(I18n.get(ModLanguageData.Keys.NOTE_DELETE), Button.Style.LINK_DANGER,
                    () -> host.onDelete(host.note())));
            delete.setBounds(x + DialogBox.BUTTON_RIGHT, DialogBox.buttonTop(y, h),
                    DialogBox.buttonW(Minecraft.getInstance().font.width(delete.label())), DialogBox.BUTTON_H);
        }
        ui.requestFocus(line);
    }

    private Button save;
}
