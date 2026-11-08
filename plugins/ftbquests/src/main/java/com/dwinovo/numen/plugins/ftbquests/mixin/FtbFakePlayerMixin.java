package com.dwinovo.numen.plugins.ftbquests.mixin;

import com.dwinovo.numen.api.entity.NumenPlayer;
import dev.ftb.mods.ftblibrary.neoforge.platform.NeoMiscImpl;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FTB 全家问"这是不是假玩家"时,同伴答"不是"。
 *
 * <p>FTB Library 在 NeoForge 上的 {@code isFakePlayer} 把凡是 {@code ServerPlayer} 子类的玩家都判成假的(不看 NeoForge 自己的
 * {@code isFakePlayer()},也没有事件或配置能改)。FTB Quests 的击杀、交互、自动提交、物品栏、检测方块与阶段任务都先问它;
 * 同伴是 {@code ServerPlayer} 的子类,于是被当成机械手那种自动化机器整个排除。她是一个在世界里活动的玩家,
 * 她做的算不算、算给谁,由 FTB 自己的队伍规则决定,所以在这里答"不是"。
 *
 * <p>只注入这一个方法;它单独一份 mixin 配置,只在 FTB Library 在场时挂上({@code neoforge.mods.toml} 里的 {@code requiredMods})。
 * 在场而方法没了或签名变了,{@code defaultRequire: 1} 与处理器签名校验让启动当场报错,不会悄悄失效。
 */
@Mixin(NeoMiscImpl.class)
public abstract class FtbFakePlayerMixin {

    @Inject(method = "isFakePlayer", at = @At("HEAD"), cancellable = true)
    private void numen$companionIsNotFake(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof NumenPlayer) {
            cir.setReturnValue(false);
        }
    }
}
