package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 基于 Architectury 的模组问"这是不是假玩家"时,同伴答"不是"。
 *
 * <p>Architectury 的 {@code PlayerHooks.isFake} 在 NeoForge 上把凡是 {@code ServerPlayer} 子类的玩家都判成假的
 * (不看 NeoForge 自己的 {@code isFakePlayer()},也没有事件或配置能改),Fabric 上先问 {@code FakePlayers.EVENT}、
 * 没结论再用同一条判断。同伴是 {@code ServerPlayer} 的子类,于是被 FTB 全家这类模组当成机械手、海龟那种
 * 自动化机器整个排除:她击杀、持物、到场都不计进度。她是一个在世界里活动的玩家,由那些模组自己的规则
 * (队伍、领地)决定她做的算不算,所以在这里答"不是"。
 *
 * <p>注入公共类 {@code PlayerHooks}:两个加载器、各个版本都是这个名字,平台实现类
 * ({@code …forge.PlayerHooksImpl})的包名是历史遗留,不作依据。Architectury 不在场时 {@link Pseudo}
 * 让这条 mixin 整个跳过,编译期也不依赖它;在场而 {@code isFake} 没了或签名变了,
 * {@code defaultRequire: 1} 与处理器签名校验让启动当场报错,不会悄悄失效。
 *
 * <p>只按方法名定位、不写描述符:Fabric 成品里 {@code Player} 是中间名,而本条 {@code remap = false}
 * 不经映射表;参数类型由处理器的签名把关。
 */
@Pseudo
@Mixin(targets = "dev.architectury.hooks.level.entity.PlayerHooks", remap = false)
public abstract class ArchitecturyPlayerHooksMixin {

    @Inject(method = "isFake", at = @At("HEAD"), cancellable = true)
    private static void numen$companionIsNotFake(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof NumenPlayer) {
            cir.setReturnValue(false);
        }
    }
}
