package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.voice.VoicePcmSource;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/**
 * 同伴语音的取数重定向——<b>Fabric 专属</b>。Fabric 运行 vanilla 字节码,
 * {@code SoundEngine.play} 对流式声音固定调用
 * {@code SoundBufferLibrary.getStream(sound.getPath(), loop)} 去开 ogg
 * (vanilla 1.21.1 的 {@link SoundInstance} 还没有后续版本那个可覆写的
 * {@code getStream} default 钩子),所以在这里把带内存 PCM 的实例
 * ({@link VoicePcmSource}:同伴 3D 语音与设置界面 2D 试听)的取数换成
 * 它自带的流,其余声音原样放行。
 *
 * <p>NeoForge <b>不走这里</b>:其 1.21.1 补丁已提前引入官方钩子,那侧由
 * {@code NeoEntityVoiceSound}/{@code NeoVoicePreviewSound} 直接覆写,零 mixin
 * (vanilla 形状的 INVOKE 在其运行时不存在,本 mixin 若留在 common 会因
 * 0 目标掀桌——这正是分家的原因)。
 *
 * <p><b>require = 0</b>:完整版 Fabric API 的 fabric-sound-api-v1 会抢先
 * @Redirect 同一个调用点(本重定向被让位跳过是<b>预期</b>,不是故障)——
 * 那条路上由 {@link MixinVoicePcmFabricSound} 给声音实例补
 * {@code FabricSoundInstance} 接口走它的官方扩展点。本重定向只在
 * sound-api 不在场的精简环境里生效,双保险互斥、必有一条通。
 */
@Mixin(SoundEngine.class)
public class MixinSoundEngine {

    @Redirect(method = "play", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/ResourceLocation;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> numen$voicePcmStream(SoundBufferLibrary library,
                                                                ResourceLocation path, boolean looping,
                                                                SoundInstance sound) {
        if (sound instanceof VoicePcmSource voice) {
            return voice.openStream();
        }
        return library.getStream(path, looping);
    }
}
