package com.dwinovo.numen.pathing.api;

import java.util.Objects;

import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;

/**
 * 宿主交给模块的端口(身体另给,见 {@link com.dwinovo.numen.pathing.body.Body}):
 *
 * @param effector  真的动手:挖、放、开关门,如实交回结果
 * @param terrain   一格能不能挖或放
 * @param materials 下一块垫路料用哪种
 * @param threats   此刻要避开的生物
 */
public record Ports(Effector effector, TerrainPolicy terrain, Materials materials, Threats threats) {

    public Ports {
        Objects.requireNonNull(effector, "effector");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(threats, "threats");
    }
}
