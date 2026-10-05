package com.dwinovo.numen.core.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.block.Block;

/**
 * 建造对账的服主参数:一格现有的方块什么时候算已经建对了({@link BuildValidity} 读它们)。字段 public 可变,运行期改写即生效;
 * 全局单例 {@link #get()}。方块与属性清单经由懒加载 getter 暴露,首次访问才触碰注册表。
 */
public final class BuildSettings {

    private static final BuildSettings INSTANCE = new BuildSettings();

    public static BuildSettings get() {
        return INSTANCE;
    }

    private BuildSettings() {}

    /** 任意非空气的现有方块都算已可接受。 */
    public boolean buildIgnoreExisting = false;
    /** 忽略朝向类方块属性。 */
    public boolean buildIgnoreDirection = false;
    /** 水与别的液体方块算已可接受。 */
    public boolean okIfWater = false;

    private List<Block> buildIgnoreBlocks;
    private List<Block> okIfAir;
    private List<String> buildIgnoreProperties;
    private Map<Block, List<Block>> buildValidSubstitutes;

    /** 目标为空气时仍算可接受的现有方块。 */
    public List<Block> buildIgnoreBlocks() {
        if (buildIgnoreBlocks == null) {
            buildIgnoreBlocks = new ArrayList<>();
        }
        return buildIgnoreBlocks;
    }

    /** 现有空气可接受的目标方块。 */
    public List<Block> okIfAir() {
        if (okIfAir == null) {
            okIfAir = new ArrayList<>();
        }
        return okIfAir;
    }

    /** 对账时忽略的方块状态属性名。 */
    public List<String> buildIgnoreProperties() {
        if (buildIgnoreProperties == null) {
            buildIgnoreProperties = new ArrayList<>();
        }
        return buildIgnoreProperties;
    }

    /** 目标方块到可接受替代方块的映射。 */
    public Map<Block, List<Block>> buildValidSubstitutes() {
        if (buildValidSubstitutes == null) {
            buildValidSubstitutes = new HashMap<>();
        }
        return buildValidSubstitutes;
    }
}
