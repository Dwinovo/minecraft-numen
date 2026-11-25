package com.dwinovo.numen.api.persona;

import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.library.Shelf;

import java.util.List;

/**
 * 主人的人设库:库的一种({@link #KIND}),<b>一个 .md 文件就是一个人设</b>——{@code persona/<命名空间>/<名字>.md}。
 * 文件内容想写啥写啥,全文原样注入 {@code <persona>},零结构约束,界面列表用正文截断做预览。随 Numen 与插件发布的
 * 与主人自己写的在同一个货架里,存储、开关、同步都是 {@link Library} 这一套;
 * 这里只声明条目长什么样,并给消费方一个按人设 id 取正文的口。
 *
 * <p>人设的 id 是 {@code 命名空间:名字}({@link EntryId}),同伴的绑定记的是它。
 */
public final class PersonaLibrary {

    /** 人设这种库:一条一个 {@code .md}。 */
    public static final LibraryKind KIND = LibraryKind.files("persona", ".md");

    /** 一个人设。{@code id} 是 {@code 命名空间:名字},{@code name} 是名字。 */
    public record Persona(String id, String name, String text) {}

    private final Library library;

    public PersonaLibrary(Library library) {
        this.library = library;
    }

    /** 重扫目录——外部编辑器的修改在这里读到。 */
    public void reload() {
        library.reload();
    }

    /** 此刻在用的人设(没关掉的、有正文的)。 */
    public List<Persona> list() {
        return library.active().stream().map(PersonaLibrary::persona).filter(p -> !p.text().isEmpty()).toList();
    }

    /** 叫这个 id、此刻在用的人设;没有、被关掉或没有正文是 {@code null}。 */
    public Persona get(String id) {
        Library.Entry entry = library.find(EntryId.parse(id));
        return entry == null || persona(entry).text().isEmpty() ? null : persona(entry);
    }

    /** 新建人设 = 写一个 .md。重名自动加 _2 后缀(文件名即身份)。 */
    public Persona create(String name, String text) {
        EntryId id = library.shelf().unique(EntryId.MINE, EntryId.clean(name, "persona"));
        return persona(library.shelf().write(id, normalized(text)));
    }

    /** 编辑人设;改名 = 换文件名(旧文件删除,id 随之更换)。返回落盘后的条目;没有这一条是 {@code null}。 */
    public Persona update(String id, String name, String text) {
        EntryId old = EntryId.parse(id);
        if (old == null || library.shelf().get(old) == null) {
            return null;
        }
        String cleaned = EntryId.clean(name, "persona");
        EntryId next = cleaned.equals(old.name()) ? old : library.shelf().unique(EntryId.MINE, cleaned);
        if (!next.equals(old)) {
            library.shelf().delete(old);
        }
        return persona(library.shelf().write(next, normalized(text)));
    }

    /** 删人设文件。绑定跟着同伴走,这里不管;在用的同伴回落兜底快照,不会失忆。 */
    public void remove(String id) {
        EntryId entry = EntryId.parse(id);
        if (entry != null) {
            library.shelf().delete(entry);
        }
    }

    private static Persona persona(Library.Entry entry) {
        return persona(entry.item());
    }

    private static Persona persona(Shelf.Item item) {
        return new Persona(item.id().toString(), item.id().name(), item.text().strip());
    }

    private static String normalized(String text) {
        return (text == null ? "" : text.strip()) + "\n";
    }
}
