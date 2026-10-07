package com.dwinovo.numen.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 札记本:她写什么就存什么,索引是算出来的。
 *
 * <p>这里守的是三件事——索引行等于 {@code description}、天数由我们盖、模型写的名字
 * 落不到目录外面去。
 */
class NoteBookTest {

    @TempDir
    Path tmp;

    private static final UUID SHE = UUID.randomUUID();

    private int day = 12;

    @BeforeEach
    void wire() {
        NoteBook.init(uuid -> tmp.resolve(uuid.toString()), () -> day);
    }

    @Test
    void aNoteSurvivesAndItsDescriptionIsTheIndexLine() {
        NoteBook book = NoteBook.of(SHE);
        book.write("main-base", "主基地 -340,68,120,门朝东", "world",
                "门口两个箱子:左边放矿,右边放食物。");

        List<NoteBook.Note> index = NoteBook.of(SHE).index();
        assertEquals(1, index.size());
        NoteBook.Note n = index.get(0);
        assertEquals("main-base", n.name());
        assertEquals("主基地 -340,68,120,门朝东", n.description());
        assertEquals("world", n.type());
        assertEquals(12, n.day());
    }

    @Test
    void theDayIsStampedByUsNotByHer() {
        NoteBook book = NoteBook.of(SHE);
        book.write("a", "第一条", "world", "");
        day = 40;
        book.write("b", "第二条", "lesson", "");

        // 新的排在前:索引的顺序要稳定,注入的字节才稳定
        List<NoteBook.Note> index = book.index();
        assertEquals(List.of("b", "a"), index.stream().map(NoteBook.Note::name).toList());
        assertEquals(40, index.get(0).day());
        assertEquals(12, index.get(1).day());
    }

    @Test
    void theIndexBlockCarriesTheBalanceButNotTheBodies() {
        NoteBook book = NoteBook.of(SHE);
        book.write("swamp", "东边沼泽过不去,得绕北", "lesson", "试了三次,A* 每次都报无路。");

        String xml = book.formatXml();
        assertEquals("""
                <memory count="1/50">
                swamp | D12 lesson | 东边沼泽过不去,得绕北
                </memory>""", xml);
        assertFalse(xml.contains("试了三次"), "正文要她自己 recall,不白塞进上下文");
    }

    @Test
    void anEmptyBookInjectsNothing() {
        assertEquals("", NoteBook.of(SHE).formatXml());
    }

    @Test
    void recallReadsTheBody() {
        NoteBook book = NoteBook.of(SHE);
        book.write("swamp", "东边沼泽过不去", "lesson", "试了三次,A* 每次都报无路。");

        assertEquals("试了三次,A* 每次都报无路。", book.read("swamp").content());
        assertNull(book.read("never-wrote-this"), "没有的条目就是没有,不编一条出来");
    }

    /** 模型写的名字直接当路径用,这是唯一一道闸。 */
    @Test
    void aNameCannotEscapeTheDirectory() {
        NoteBook book = NoteBook.of(SHE);
        book.write("../../escaped", "想跑出去", "world", "");

        assertFalse(Files.exists(tmp.resolve("escaped.md")), "不能落到同伴目录外面");
        assertEquals("escaped", book.index().get(0).name());
    }

    @Test
    void aNameWithNoLettersIsRefused() {
        NoteBook book = NoteBook.of(SHE);
        assertThrows(IllegalArgumentException.class, () -> book.write("///", "x", "world", ""));
    }

    @Test
    void aNoteWithoutADescriptionIsRefused() {
        NoteBook book = NoteBook.of(SHE);
        assertThrows(IllegalArgumentException.class,
                () -> book.write("x", "  ", "world", "正文写了也没用"));
    }

    /** 索引行按定义是一行:她写成多行,frontmatter 会被截断,整条记忆就读不回来。 */
    @Test
    void aMultiLineDescriptionIsFoldedBackOntoOneLine() {
        NoteBook book = NoteBook.of(SHE);
        book.write("base", "主基地\n-340,68,120", "world", "");

        assertEquals("主基地 -340,68,120", book.index().get(0).description());
        assertEquals("主基地 -340,68,120", book.read("base").description(), "落盘后还读得回来");
    }

    @Test
    void writingTheSameNameReplacesIt() {
        NoteBook book = NoteBook.of(SHE);
        book.write("base", "旧的说法", "world", "");
        book.write("base", "新的说法", "world", "");

        assertEquals(1, book.index().size());
        assertEquals("新的说法", book.index().get(0).description());
    }

    @Test
    void forgettingRemovesIt() {
        NoteBook book = NoteBook.of(SHE);
        book.write("base", "主基地", "world", "");

        assertTrue(book.forget("base"));
        assertTrue(book.index().isEmpty());
        assertFalse(book.forget("base"), "已经没了就是没了");
    }

    @Test
    void everyWriteBumpsTheRevisionSoTheInjectorKnowsToResend() {
        NoteBook book = NoteBook.of(SHE);
        int before = book.revision();
        book.write("a", "一", "world", "");
        book.forget("a");
        assertEquals(before + 2, book.revision());
    }

    /**
     * 工具那侧和注入那侧各自 {@code of(uuid)},拿到的必须是同一本——不然 remember 写完,
     * 注入那侧的 revision 纹丝不动,索引就再也不重贴了。
     */
    @Test
    void theToolSideAndTheInjectorSideShareOneBook() {
        NoteBook injector = NoteBook.of(SHE);
        int before = injector.revision();

        NoteBook.of(SHE).write("base", "主基地", "world", "");

        assertEquals(before + 1, injector.revision(), "写在另一处,这一侧也得看得见");
    }

    /** 主人手改坏的文件不能把整本札记拖垮——读不出索引行的那条跳过,其余照常。 */
    @Test
    void aHandEditedFileMissingItsDescriptionIsSkipped() throws Exception {
        NoteBook book = NoteBook.of(SHE);
        book.write("good", "读得出来", "world", "");
        Path dir = tmp.resolve(SHE.toString());
        Files.writeString(dir.resolve("broken.md"), "---\nname: broken\n---\n\n正文\n",
                StandardCharsets.UTF_8);

        assertEquals(List.of("good"), book.index().stream().map(NoteBook.Note::name).toList());
    }
}
