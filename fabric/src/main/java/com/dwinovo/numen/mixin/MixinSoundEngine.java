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
 * 0 目标掀桌——这正是分家的原因)。在 Fabric 上目标必然存在,保持默认
 * require:注入失败应当炸出来,而不是静默哑掉。vanilla 正式引入官方钩子的
 * 版本再删本 mixin 改覆写。
 */
@Mixin(SoundEngine.class)
public class MixinSoundEngine {

    @Redirect(method = "play",
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
