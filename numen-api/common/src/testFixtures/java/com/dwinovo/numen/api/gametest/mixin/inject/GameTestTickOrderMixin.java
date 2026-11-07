package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.TickCallbacks;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版把用例排到某一刻的回调({@code runAfterDelay}、{@code onEachTick} 都落在这张表里)放在按对象哈希散列的表里,每刻边遍历边执行边删。
 * 同一刻到点的几个回调于是按对象的身份哈希的先后执行,每次开服不一样:"第 5 刻起放行"与"每刻看放没放行"谁先跑,决定这件事落在第 5 刻
 * 还是第 6 刻;同一批里一条用例早一刻、晚一刻,别的用例掷到的世界随机数(掉落物的初速)也跟着错开。
 *
 * <p>测试服里换成按登记先后排的表,每刻遍历的是这一刻开头的一份副本({@link TickCallbacks#copyOf}):同一刻到点的回调按用例登记它们的
 * 先后执行;回调里新排的回调进真表、从下一刻起算,不在遍历中途改动正被遍历的表。
 */
@Mixin(GameTestInfo.class)
public abstract class GameTestTickOrderMixin {

    @Shadow
    @Final
    @Mutable
    private Object2LongMap<Runnable> runAtTickTimeMap;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void numen$runInTheOrderRegistered(CallbackInfo ci) {
        runAtTickTimeMap = new Object2LongLinkedOpenHashMap<>();
    }

    @Redirect(method = "tickInternal", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Object2LongMap;object2LongEntrySet()Lit/unimi/dsi/fastutil/objects/ObjectSet;"))
    private ObjectSet<Object2LongMap.Entry<Runnable>> numen$walkACopy(Object2LongMap<Runnable> map) {
        return TickCallbacks.copyOf(map);
    }
}
