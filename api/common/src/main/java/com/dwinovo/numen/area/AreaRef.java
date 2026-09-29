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
