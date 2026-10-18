package com.dwinovo.numen.api.client.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 改动先按办成画、等服务端真表来了再对齐:真表在服务端,界面等不起一个来回,所以改了的那几条先记在这里、盖在真表上画;
 * 服务端下一次推来真表(换了一份)时全部作废,以真表为准——办不成的,服务端也把真表推回来。会话与各种库的列表都走这一处。
 *
 * <p>客户端主线程专用。
 */
final class PendingChanges<T> {

    /** 条目 id → 改后的样子;{@code null} = 删了。 */
    private final Map<String, T> changes = new LinkedHashMap<>();
    /** 上一次盖着的那份真表;按引用认,换了一份就是服务端又说话了。还没盖过是 null。 */
    private List<T> basis;

    void put(String id, T entry) {
        changes.put(id, entry);
    }

    /** 真表盖上还没回真表的改动。 */
    List<T> over(List<T> truth, Function<T, String> idOf) {
        if (basis != null && truth != basis) {
            changes.clear();
        }
        basis = truth;
        if (changes.isEmpty()) {
            return truth;
        }
        Map<String, T> out = new LinkedHashMap<>();
        for (T entry : truth) {
            out.put(idOf.apply(entry), entry);
        }
        changes.forEach((id, entry) -> {
            if (entry == null) out.remove(id); else out.put(id, entry);
        });
        return new ArrayList<>(out.values());
    }
}
