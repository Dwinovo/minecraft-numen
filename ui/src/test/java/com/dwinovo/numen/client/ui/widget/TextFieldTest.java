package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.KeyCodes;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void anUnderlinedFieldDrawsOnlyItsBottomLine() {
        UiRoot root = new UiRoot();
        TextField f = root.add(new TextField("", s -> {}).placeholder("告诉她该怎么做").underlined(true));
        f.setBounds(10, 20, 100, 14);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertEquals(1, s.rects.size(), "不画卡壳,只有一道线");
        assertArrayEquals(new int[]{10, 33, 100, 1}, s.rects.get(0), "线贴着底边、横跨整宽");
        assertTrue(s.texts.contains("告诉她该怎么做"), "占位照常");
    }

    @Test
    void placeholderShowsWhileEmptyEvenWhenFocused() {
        UiRoot root = new UiRoot();
        TextField f = root.add(new TextField("", s -> {}).placeholder("默认基址"));
        f.setBounds(0, 0, 100, 14);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertTrue(s.texts.contains("默认基址"), "没聚焦时有占位");
        root.requestFocus(f);
        WidgetTestSupport.FakeSurface focused = new WidgetTestSupport.FakeSurface();
        f.render(focused, WidgetTestSupport.C, 0, 0, 0);
        assertTrue(focused.texts.contains("默认基址"), "聚焦着、还没打字,占位还在");
        f.setValue("x");
        WidgetTestSupport.FakeSurface typed = new WidgetTestSupport.FakeSurface();
        f.render(typed, WidgetTestSupport.C, 0, 0, 0);
        assertTrue(!typed.texts.contains("默认基址"), "打了字占位让开");
    }

    @Test
    void aBareFieldDrawsNoFrame() {
        UiRoot root = new UiRoot();
        TextField f = root.add(new TextField("", s -> {}).placeholder("写消息").bare(true));
        f.setBounds(10, 20, 100, 14);
        root.requestFocus(f);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 500);   // 光标这一刻灭着
        assertEquals(0, s.rects.size(), "不画框、不画底——底色是宿主那一整条的");
        assertTrue(s.texts.contains("写消息"));
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

    /** 假宿主:绑上之后文本住在这里,改动经回调告诉 TextField——和真 EditBox 一个形状。 */
    private static final class FakeInput implements TextInput {
        private String text;
        private final Consumer<String> onChange;
        private boolean focused;
        /** -1 = 没人定位过,光标在末尾(香草 setValue 的行为)。 */
        private int cursor = -1;

        FakeInput(String initial, Consumer<String> onChange) {
            this.text = initial;
            this.onChange = onChange;
        }

        /** 模拟用户在宿主控件里打字。 */
        void type(String s) {
            text = s;
            onChange.accept(s);
        }

        @Override public String text() { return text; }
        @Override public void setText(String s) { text = s; }
        @Override public int cursor() { return cursor < 0 ? text.length() : Math.min(cursor, text.length()); }
        @Override public void setCursor(int pos) { cursor = Math.max(0, Math.min(pos, text.length())); }
        @Override public boolean focused() { return focused; }
        @Override public void setFocused(boolean f) { focused = f; }
        @Override public void moveTo(int x, int y, int w, int h) {}
    }

    private static TextField hostedField(String initial, AtomicReference<FakeInput> host, UiRoot root) {
        root.setInputFactory((initial0, onChange) -> {
            FakeInput in = new FakeInput(initial0, onChange);
            host.set(in);
            return in;
        });
        TextField f = root.add(new TextField(initial, s -> {}).numeric());
        assertNotNull(host.get(), "add 之后应当已经绑上宿主");
        return f;
    }

    @Test
    void hostedIntValueReadsHostText() {
        UiRoot root = new UiRoot();
        AtomicReference<FakeInput> host = new AtomicReference<>();
        TextField f = hostedField("8080", host, root);
        assertEquals(8080, f.intValue(-1));
        host.get().type(" 9090 ");
        assertEquals(9090, f.intValue(-1));   // 绑了宿主之后的值住在宿主里
        host.get().type("");
        assertEquals(-1, f.intValue(-1));
    }

    @Test
    void hostedEditClearsInlineError() {
        UiRoot root = new UiRoot();
        AtomicReference<FakeInput> host = new AtomicReference<>();
        TextField f = hostedField("1", host, root);
        f.setError("端口不对");
        assertTrue(f.hasError());
        host.get().type("12");
        assertFalse(f.hasError());   // 用户一开始修改错误标记就撤下,宿主模式也一样
    }
    @Test
    void highlightedSpansAreDrawnAsSeparateRunsInTheirOwnColour() {
        // 宿主说哪几段换色,框就拆成几笔:正文 / 换色段 / 正文,颜色各归各的
        TextField f = new TextField("@小柚 去挖铁 @阿岚", s -> {})
                .highlight(t -> java.util.List.of(
                        new TextField.Span(0, 3, 0xFF112233),
                        new TextField.Span(8, 11, 0xFF112233)));
        f.setBounds(0, 0, 200, 14);
        RecordingSurface s = new RecordingSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertEquals(java.util.List.of("@小柚", " 去挖铁 ", "@阿岚"), s.runs);
        assertEquals(java.util.List.of(0xFF112233, WidgetTestSupport.C.textPrimary(), 0xFF112233), s.colours);
    }

    /** 记下每一笔的文字与颜色(每字符 6px,与 FakeSurface 同一量尺)。 */
    private static final class RecordingSurface implements com.dwinovo.numen.client.ui.IDrawSurface {
        final java.util.List<String> runs = new java.util.ArrayList<>();
        final java.util.List<Integer> colours = new java.util.ArrayList<>();

        @Override public void fillRect(int x, int y, int w, int h, int argb) {}
        @Override public void drawText(String t, int x, int y, int argb, boolean shadow) {
            runs.add(t);
            colours.add(argb);
        }
        @Override public int textWidth(String t) { return t.length() * 6; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) {}
        @Override public void popScissor() {}
    }
}
