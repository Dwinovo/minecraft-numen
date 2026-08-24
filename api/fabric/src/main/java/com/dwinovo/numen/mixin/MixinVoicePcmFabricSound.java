package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.voice.EntityVoiceSound;
import com.dwinovo.numen.client.voice.VoicePcmSource;
import com.dwinovo.numen.client.voice.VoicePreviewSound;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

/**
 * 与 fabric-sound-api-v1 的合作通道:完整版 Fabric API 会 @Redirect
 * {@code SoundEngine.play} 的流式取数(和 {@link MixinSoundEngine} 同一个调用点,
 * 我们的重定向会被让位跳过),但它自己带扩展点——实现了
 * {@link FabricSoundInstance} 的声音实例,取数交回实例的 {@code getAudioStream}。
 * 这里给两个内存 PCM 声音实例(同伴 3D 语音 + 设置界面试听)补上该接口,
 * 数据源仍是 {@link VoicePcmSource#openStream()};sound-api 不在场时
 * {@link MixinSoundEngine} 作为后备照常接管。
 */
@Mixin(value = {EntityVoiceSound.class, VoicePreviewSound.class}, remap = false)
public abstract class MixinVoicePcmFabricSound implements FabricSoundInstance {

    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader,
                                                         Identifier id, boolean repeatInstantly) {
        return ((VoicePcmSource) (Object) this).openStream();
    }
}
