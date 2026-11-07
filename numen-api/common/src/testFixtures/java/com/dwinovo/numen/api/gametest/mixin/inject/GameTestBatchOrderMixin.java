package com.dwinovo.numen.api.gametest.mixin.inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 原版按测试环境把用例分批,分组用的是以环境为键的散列表,而环境的键按对象身份散列:每次开服各批的先后不一样,前面几批跑了多久、
 * 留下了什么就跟着变。测试服里改成按用例登记的先后分组(各批按它第一条用例的先后排),每次开服同样的顺序。
 */
@Mixin(GameTestBatchFactory.class)
public abstract class GameTestBatchOrderMixin {

    @Redirect(method = "divideIntoBatches", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Collectors;groupingBy(Ljava/util/function/Function;)Ljava/util/stream/Collector;"))
    private static <T, K> Collector<T, ?, Map<K, List<T>>> numen$inRegistrationOrder(Function<? super T, ? extends K> key) {
        return Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList());
    }
}
