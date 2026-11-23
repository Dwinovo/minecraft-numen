package com.dwinovo.numen.api.describe;

import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.Map;

/**
 * "这是什么"的一个回答者:只说它认得的那一样。她细看一样东西时被叫一次,认得就交回几个字段,不认得交回 null。登记时带的名字就是这些
 * 字段在结果里归在哪个名字下:{@code numen.answer(Questions.BLOCK, "smelter", 回答者)} 说的出现在
 * {@code numen.scan.block(pos).details.smelter}。
 *
 * <pre>{@code
 * numen.answer(Questions.BLOCK, "smelter", (her, pos) -> {
 *     if (!(her.level().getBlockEntity(pos) instanceof MySmelter smelter)) {
 *         return null;                                  // 不是我的东西
 *     }
 *     return Map.of("running", smelter.isRunning(), "progress_percent", smelter.progressPercent());
 * });
 * }</pre>
 *
 * <p>字段的值是脚本的普通值:字符串、数、布尔、列表、名字到值的表,可以套着;别的类型写不成脚本的值,整次"看"当场失败。字段名用小写
 * 加下划线,英文(读的是模型)。要字段有固定的先后就交回 {@link java.util.LinkedHashMap},否则按名字排。
 *
 * <p>规矩:只说事实;不判能不能做(那是权限层的事);不改世界、不动她的身体。别的回答者说了什么你看不到也改不了,回答之间不讲先后。
 * 抛了异常只丢你这一条、记进日志,别的回答照常。在服务端主线程上被叫,她每细看一次就问一次(物品是清单里的每一种、回执里写到的
 * 每一叠都问),别在这里做慢事。
 *
 * @param <T> 看的是什么:一格方块({@link net.minecraft.core.BlockPos})、一只实体({@link net.minecraft.world.entity.Entity})、
 *            一叠物品({@link net.minecraft.world.item.ItemStack})
 */
@FunctionalInterface
public interface Describer<T> {

    /**
     * @param target 看的是谁
     * @return 关于它你知道的事,字段名到值;不认得、没什么可说是 null 或空表
     */
    Map<String, Object> describe(NumenPlayer her, T target);
}
