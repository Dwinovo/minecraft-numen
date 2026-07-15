package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.voice.EntityVoiceSound;
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
 * 同伴语音的取数重定向。{@code SoundEngine.play} 对流式声音固定调用
 * {@code SoundBufferLibrary.getStream(sound.getPath(), loop)} 去开 ogg——
 * 1.21.1 的 {@link SoundInstance} 还没有后续版本那个可覆写的
 * {@code getStream} default 钩子,所以在这里把同伴语音实例
 * （{@link EntityVoiceSound}）的取数换成它自带的 PCM 流,其余声音原样放行。
 * 语义与后续版本官方钩子一致,升版本时删掉本 mixin 改成覆写即可。
 */
@Mixin(SoundEngine.class)
public class MixinSoundEngine {

    @Redirect(method = "play",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/ResourceLocation;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> numen$voicePcmStream(SoundBufferLibrary library,
                                                                ResourceLocation path, boolean looping,
                                                                SoundInstance sound) {
        if (sound instanceof EntityVoiceSound voice) {
            return voice.openStream();
        }
        return library.getStream(path, looping);
    }
}
