package com.dwinovo.numen.api.client.ui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 标签折行与按钮式字段的点击。 */
class TagRowTest {

    @Test
    void wrapsWhenARowIsFullAndHeightFollows() {
        List<TagRow.Tag> tags = List.of(new TagRow.Tag("aaaa", false), new TagRow.Tag("bbbb", false),
                new TagRow.Tag("cccc", true));
        // 每字 6px:一枚 24+6=30,两枚 64,三枚 98
        assertEquals(9, TagRow.height(t -> t.length() * 6, 9, tags, 200));
        assertEquals(9 + 2 + 9, TagRow.height(t -> t.length() * 6, 9, tags, 70));
        assertEquals(0, TagRow.height(t -> t.length() * 6, 9, List.of(), 70));

        TagRow row = new TagRow(tags);
        row.setBounds(0, 0, 70, 20);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        row.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertEquals(List.of("aaaa", "bbbb", "cccc"), s.texts);
    }

    @Test
    void pickFieldShowsItsPlaceholderWhileEmpty() {
        PickField f = new PickField("", "", () -> {}).placeholder("选择型号");
        f.setBounds(0, 0, 300, 18);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertTrue(s.texts.contains("选择型号"));
    }

    @Test
    void pickFieldClickRunsTheAction() {
        AtomicInteger n = new AtomicInteger();
        PickField f = new PickField("DeepSeek", "api.deepseek.com", n::incrementAndGet);
        f.setBounds(0, 0, 300, 18);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertTrue(s.texts.contains("DeepSeek") && s.texts.contains("api.deepseek.com"));
        assertTrue(f.mouseClicked(5, 5, 0));
        assertEquals(1, n.get());
    }
}
