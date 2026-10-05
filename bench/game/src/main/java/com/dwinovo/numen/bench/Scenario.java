package com.dwinovo.numen.bench;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * 一个评测场景:搭什么场景、主人开场说什么、模拟主人怎么答、怎么算成功、标准解是什么。
 *
 * <p>每一次运行造一个新实例({@link Suite#add} 收的是构造器),所以场景可以把这一次里生成的东西(一只女仆、一块矿的位置)
 * 放在自己的字段里,在断言与标准解里用。坐标都相对场地:地板在 y=0,站在地板上是 y=1,x、z 从 0 到场地边长减一。
 */
public interface Scenario {

    /** 场景名:小写字母、数字、下划线;{@code -Dbench.scenarios} 按它选。 */
    String id();

    /** 场地多大。 */
    default Arena arena() {
        return Arena.DEFAULT;
    }

    /** 这个场景一次最多花多少。 */
    default Budget budget() {
        return Budget.DEFAULT;
    }

    /** 她站在哪一格开始。 */
    default BlockPos start() {
        return new BlockPos(2, 1, 2);
    }

    /** 主人站在哪一格。 */
    default BlockPos ownerAt() {
        return new BlockPos(1, 1, 1);
    }

    /** 搭场景:她和主人都已经在场地里了。放方块、给她物品、生成实体。 */
    void setup(Scene scene);

    /** 主人开场对她说的话。 */
    String opening();

    /** 模拟主人:怎么答她的征询,要不要回她的话。 */
    default OwnerScript owner() {
        return OwnerScript.ALLOW_ONCE;
    }

    /** 成功断言、负面断言、子目标。"她没死"每个场景都有,不用写。 */
    List<Check> checks();

    /** 收场时场景要记的数(指标,不决定成败),名字到数值;默认没有。 */
    default Map<String, Double> metrics(Scene scene) {
        return Map.of();
    }

    /**
     * 标准解:一段 Lua 程序(和她调 {@code lua} 工具写的一样),跑完这个场景必须成功。它证明场景可解;空操作必须失败,
     * 证明断言不被什么都不做骗过。
     */
    String solution(Scene scene);
}
