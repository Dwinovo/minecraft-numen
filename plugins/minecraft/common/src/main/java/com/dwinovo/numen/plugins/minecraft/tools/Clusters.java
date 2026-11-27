package com.dwinovo.numen.plugins.minecraft.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 脚本里的一团({@link ScanOps.Cluster},扫描交回的)按它的 {@code blocks} 展开成每一格:收一串格的函数读参数时先过这一步,
 * 引擎只认 Pos 与 Block,不认识团。
 */
public final class Clusters {

    private Clusters() {}

    /** 一团的那一串格;不是一团是 null。 */
    public static List<?> blocks(Object value) {
        return value instanceof Map<?, ?> map && map.get("blocks") instanceof List<?> blocks ? blocks : null;
    }

    /** 一串格的每一项:一团展开成它的每一格,一串逐项(一项是一团的也展开),别的就是只有它自己那一项。 */
    public static List<Object> items(Object value) {
        List<Object> out = new ArrayList<>();
        List<?> blocks = blocks(value);
        if (blocks != null) {
            out.addAll(blocks);
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                List<?> inner = blocks(item);
                if (inner != null) {
                    out.addAll(inner);
                } else {
                    out.add(item);
                }
            }
        } else {
            out.add(value);
        }
        return out;
    }
}
