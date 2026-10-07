package com.dwinovo.numen.pathing.api;

import java.util.Objects;

import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;

/**
 * 宿主交给模块的端口(身体另给,见 {@link com.dwinovo.numen.pathing.body.Body}):寻路自己的几项策略。
 *
 * @param terrain   一格能不能挖或放
 * @param materials 下一块垫路料用哪种
 * @param threats   此刻要避开的生物
 */
public record Ports(TerrainPolicy terrain, Materials materials, Threats threats) {

    public Ports {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(threats, "threats");
    }
}
