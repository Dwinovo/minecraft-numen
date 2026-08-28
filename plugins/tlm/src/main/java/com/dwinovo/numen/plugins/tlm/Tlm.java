package com.dwinovo.numen.plugins.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.client.sound.CustomSoundLoader;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.CustomModelPack;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;

import java.util.List;
import java.util.Optional;

/**
 * 本插件对接车万女仆的那一面——<b>唯一</b>知道它存在的地方。
 *
 * <h2>为什么可以直接引用它的类,不像 YSM 那样只能喊命令</h2>
 * 车万女仆代码部分是 MIT 且开源,类名不混淆。所以这里直接编译依赖它,拿真正的
 * 模型对象,而不是隔着命令行和 NBT 猜。运行时那份由玩家自己装的模组提供
 * ({@code compileOnly}),本插件不携带它一个字节。
 *
 * <h2>两条渲染线</h2>
 * 车万女仆的模型分 Bedrock 与 GeckoLib 两种({@code maid_model.json} 里的
 * {@code is_gecko})。{@code EntityMaidRenderer} 内部自己分流,本插件不必区分——
 * 但"模型在不在"的判据必须用元信息表,见 {@link #exists}。
 *
 * <h2>模型是拿到了,动画为什么还要绕一圈</h2>
 * 它的 {@code BedrockModel.setupAnim} 里有这么一句:
 * <pre>if (entityIn instanceof Mob mob) { ...跑动画... return; }</pre>
 * 同伴是<b>假玩家</b>不是 Mob,落到这里什么都不做,模型会以 T-pose 僵住。
 * 而它留了 {@code ConvertMaidEvent} 这个官方扩展点(注释原话:"其他模组作者可以
 * 捕获此事件,调用 setMaid 方法传入 IMaid 实例,即可调用女仆渲染")——收的是 Mob。
 *
 * <p>所以走法是:给每个同伴配一个<b>不入世界的傀儡 Mob</b>,我们在那个事件里认领它、
 * 让它报告同伴的真实状态。动画照常跑,不用 mixin、不改车万女仆一行。见 {@link MaidPuppet}。
 */
public final class Tlm {

    private Tlm() {}

    /**
     * 这个模型在不在——"存在"的<b>唯一</b>判据。
     *
     * <p>必须问元信息表({@code idInfoMap},{@code getInfo}/{@code getModelIdSet} 读的
     * 都是它),不能问 {@code getModel}。后者读的是 {@code idModelMap},<b>只装
     * Bedrock 模型</b>;标了 {@code is_gecko} 的包走 GeckoLib 那条线,在那张表里
     * 永远查不到。拿它当判据的话,gecko 模型会被判成"不存在"而放弃接管渲染,
     * 表现是同伴露出原皮。
     */
    public static boolean exists(String modelId) {
        try {
            return CustomPackLoader.MAID_MODELS.getInfo(modelId).isPresent();
        } catch (Throwable ignored) {
            return false;   // 模组没装 / 资源还没加载
        }
    }

    /** 装了哪些模型包。清单按包分组给大模型看,见 {@link MaidCatalog}。 */
    public static List<CustomModelPack<MaidModelInfo>> packs() {
        try {
            return CustomPackLoader.MAID_MODELS.getPackList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** 模型的元信息:显示名、贴图、缩放。 */
    public static Optional<MaidModelInfo> info(String modelId) {
        try {
            return CustomPackLoader.MAID_MODELS.getInfo(modelId);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * 取一条语音的音频缓冲。同名多变体(hurt1..hurt4)由它自己随机挑一条。
     *
     * @param pack  音效包 id,就是模型 id 的命名空间(如 {@code gugu_gaga})
     * @param sound 声音 id,如 {@code maid/ai/hurt}
     */
    public static Optional<com.mojang.blaze3d.audio.SoundBuffer> voice(String pack, String sound) {
        try {
            var cache = CustomSoundLoader.getSoundCache(pack);
            if (cache == null) return Optional.empty();
            return Optional.ofNullable(
                    cache.getBuffer(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(pack, sound)));
        } catch (Throwable ignored) {
            return Optional.empty();   // 这个包没带音效,或者车万女仆还没加载完
        }
    }

    /** 车万女仆装了没有。没装的话本插件全程安静,不报错。 */
    public static boolean present() {
        try {
            CustomPackLoader.MAID_MODELS.getModelIdSet();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
