package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.KeyCodes;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文本框编辑语义:插删/光标/Home End/粘贴清洗/掩码渲染/onChange。 */
class TextFieldTest {

    private static TextField focusedField(String initial, AtomicReference<String> changed, UiRoot root) {
        TextField f = root.add(new TextField(initial, changed::set));
        f.setBounds(0, 0, 100, 14);
        root.mouseClicked(5, 5, 0);   // 点击获焦
        assertTrue(f.isFocused());
        return f;
    }

    @Test
    void typingInsertsAtCursorAndFiresChange() {
        AtomicReference<String> changed = new AtomicReference<>();
        UiRoot root = new UiRoot();
        TextField f = focusedField("ac", changed, root);
        f.keyPressed(KeyCodes.LEFT, 0);
        f.charTyped('b');
        assertEquals("abc", f.value());
        assertEquals("abc", changed.get());
        assertEquals(2, f.cursor());
    }

    @Test
    void backspaceAndDeleteRespectCursor() {
        UiRoot root = new UiRoot();
        TextField f = focusedField("abc", new AtomicReference<>(), root);
        f.keyPressed(KeyCodes.HOME, 0);
        f.keyPressed(KeyCodes.DELETE, 0);
        assertEquals("bc", f.value());
        f.keyPressed(KeyCodes.END, 0);
        f.keyPressed(KeyCodes.BACKSPACE, 0);
        assertEquals("b", f.value());
    }

    @Test
    void pasteStripsNewlinesAndInsertsAtCursor() {
        UiRoot root = new UiRoot();
        root.setClipboard(() -> "sk-\nabc\r\ndef", s -> {});
        TextField f = focusedField("", new AtomicReference<>(), root);
        f.keyPressed(KeyCodes.KEY_V, KeyCodes.MOD_CTRL);
        assertEquals("sk-abcdef", f.value());   // API key 粘贴的换行必须清洗
    }

    @Test
    void copySendsFullValueToClipboard() {
        UiRoot root = new UiRoot();
        AtomicReference<String> copied = new AtomicReference<>();
        root.setClipboard(() -> "", copied::set);
        TextField f = focusedField("secret", new AtomicReference<>(), root);
        f.keyPressed(KeyCodes.KEY_C, KeyCodes.MOD_CTRL);
        assertEquals("secret", copied.get());
    }

    @Test
    void maskedFieldNeverRendersRawValue() {
        UiRoot root = new UiRoot();
        TextField f = root.add(new TextField("sk-12345", s -> {}).masked(true));
        f.setBounds(0, 0, 100, 14);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        for (String t : s.texts) {
            assertTrue(!t.contains("sk-12345") && !t.contains("12345"), "泄漏明文: " + t);
        }
    }

    @Test
    void placeholderShowsOnlyWhenEmptyAndUnfocused() {
        UiRoot root = new UiRoot();
        TextField f = root.add(new TextField("", s -> {}).placeholder("默认基址"));
        f.setBounds(0, 0, 100, 14);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertTrue(s.texts.contains("默认基址"));
    }

    @Test
    void cursorClampsAtBothEnds() {
        UiRoot root = new UiRoot();
        TextField f = focusedField("ab", new AtomicReference<>(), root);
        f.keyPressed(KeyCodes.RIGHT, 0);
        f.keyPressed(KeyCodes.RIGHT, 0);
        assertEquals(2, f.cursor());
        f.keyPressed(KeyCodes.HOME, 0);
        f.keyPressed(KeyCodes.LEFT, 0);
        assertEquals(0, f.cursor());
    }
}
