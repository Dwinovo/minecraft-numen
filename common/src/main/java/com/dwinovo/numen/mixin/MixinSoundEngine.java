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
 * 同伴语音的取数重定向。{@code SoundEngine.play} 对流式声音固定调用
 * {@code SoundBufferLibrary.getStream(sound.getPath(), loop)} 去开 ogg——
 * 1.21.1 的 {@link SoundInstance} 还没有后续版本那个可覆写的
 * {@code getStream} default 钩子,所以在这里把带内存 PCM 的实例
 * （{@link VoicePcmSource}:同伴 3D 语音与设置界面 2D 试听）的取数换成
 * 它自带的流,其余声音原样放行。语义与后续版本官方钩子一致,
 * 升版本时删掉本 mixin 改成覆写即可。
 */
@Mixin(SoundEngine.class)
public class MixinSoundEngine {

    // require = 0:NeoForge 的补丁把这处取数换成了 SoundInstance.getStream 官方钩子,
    // vanilla 形状的 INVOKE 在其运行时不存在——注入不上时静默放行(该侧走各自的
    // 平台实现),不许把整个游戏拉下水。
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
