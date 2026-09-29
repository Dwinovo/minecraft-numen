package com.dwinovo.numen.area;

import com.dwinovo.numen.cli.Names;

import java.util.Map;

/**
 * 点名一块区域的写法:{@code ores} 指整块,{@code ores/g3} 指其中一部分。凡是收区域的地方两种写法都收,全仓只在
 * {@link #parse} 读这种写法。
 *
 * @param name 区域名({@link Names} 的规矩)
 * @param part 部分编号;指整块时为 null
 */
public record AreaRef(String name, String part) {

    /**
     * 和方块、格子种类并列写在一串里时的记号:{@code area:house}、{@code area:ores/g3}(路线标志 {@code --avoid_break area:house}、
     * {@code --avoid area:farm})。
     */
    public static final String MARK = "area:";

    /**
     * 以 {@link #MARK} 打头的就读出它点名的区域,否则是 null(那是方块、种类之类别的东西)。
     *
     * @throws IllegalArgumentException 打头是 {@code area:},后面的名字或编号不合规矩
     */
    public static AreaRef marked(String text) {
        return text != null && text.startsWith(MARK) ? parse(text.substring(MARK.length())) : null;
    }

    /** 带记号写回去:{@code area:ores/g3}。 */
    public String marked() {
        return MARK + this;
    }

    /**
     * 读 {@code 名字} 或 {@code 名字/部分}。
     *
     * @throws IllegalArgumentException 名字或编号不合规矩,说清该怎么写
     */
    public static AreaRef parse(String text) {
        String raw = text == null ? "" : text.strip();
        int slash = raw.indexOf('/');
        String name = Names.checked("area", slash < 0 ? raw : raw.substring(0, slash));
        if (slash < 0) {
            return new AreaRef(name, null);
        }
        String part = raw.substring(slash + 1);
        if (!Area.isPartId(part)) {
            throw new IllegalArgumentException("a part of an area is a letter and a number (g3, b1, p2, s1, c1), got \""
                    + part + "\" in \"" + raw + "\"");
        }
        return new AreaRef(name, part);
    }

    /**
     * 在这些区域里找它指的东西:整块,或只剩那一部分的区域({@link Area#only})。没有这块区域、或区域里没有这一部分,
     * 是 null。
     */
    public Area resolve(Map<String, Area> areas) {
        Area area = areas.get(name);
        if (area == null || part == null) {
            return area;
        }
        return area.part(part) == null ? null : area.only(part);
    }

    @Override
    public String toString() {
        return part == null ? name : name + "/" + part;
    }
}
