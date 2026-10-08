package com.dwinovo.numen.api.platform.services;

import com.dwinovo.numen.api.platform.services.BlockStorageReading.Exposed;
import net.minecraft.core.Direction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

/**
 * 把一个方块从"不指定面"和六个面露出的存储合并成"一个存储 + 它从哪几面露出"。
 *
 * <p>什么算同一份存储,只在这里定:从某一面看进去的读数(各格各槽的内容和容量)相等,就是同一份。
 * 不按加载器给的对象身份判,因为两边的存储系统都不保证一份存储对应一个对象——NeoForge 的箱子每个面一个新的
 * 处理器,Fabric 的双箱每次查询一个新的组合存储,也没有"是不是同一份"的判断可用;而一根管道或一个漏斗
 * 从某一面能观察到的恰恰就是这份读数。
 *
 * <p>面的写法("all"、各面的名字)和探哪几面也只在这里定义。
 */
public final class Exposures<V> {

    /** 不指定面的叫法。 */
    public static final String ALL = "all";

    private final Map<V, List<String>> sidesOf = new LinkedHashMap<>();

    /** 逐面读一遍:{@code read} 在某一面(null 是不指定面)读出存储的读数,这一面没有存储就返回 null。 */
    public Exposures<V> probe(Function<Direction, V> read) {
        add(ALL, read.apply(null));
        for (Direction d : Direction.values()) {
            add(d.getName(), read.apply(d));
        }
        return this;
    }

    private void add(String side, V reading) {
        if (reading == null) return;
        sidesOf.computeIfAbsent(reading, r -> new ArrayList<>()).add(side);
    }

    /** 按首次出现的顺序。 */
    public List<Exposed<V>> found() {
        List<Exposed<V>> out = new ArrayList<>();
        sidesOf.forEach((reading, sides) -> out.add(new Exposed<>(sides, reading)));
        return out;
    }
}
