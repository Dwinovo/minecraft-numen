package com.dwinovo.numen.pathing.api;

import java.util.Objects;

import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.SearchClock;

/**
 * 宿主交给模块的端口(身体另给,见 {@link com.dwinovo.numen.pathing.body.Body}):寻路自己的几项策略与出口。
 *
 * @param terrain   一格能不能挖或放
 * @param materials 下一块垫路料用哪种
 * @param threats   此刻要避开的生物
 * @param clock     搜索看的表:正式运行是 {@link SearchClock#WALL},测试按做了多少活折算({@link SearchClock#STEADY})
 * @param trace     一趟怎么收的场、卡在哪、重搜了几次;没人听用 {@link Trace#NONE}
 */
public record Ports(TerrainPolicy terrain, Materials materials, Threats threats, SearchClock clock, Trace trace) {

    public Ports {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(threats, "threats");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(trace, "trace");
    }

    /** 不记轨迹的四项。 */
    public Ports(TerrainPolicy terrain, Materials materials, Threats threats, SearchClock clock) {
        this(terrain, materials, threats, clock, Trace.NONE);
    }
}
