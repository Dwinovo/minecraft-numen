package com.dwinovo.numen.client.voice;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fabric 侧的同伴 3D 语音:Fabric API 的 sound-api-v1 自带取数重定向
 * ({@code SoundSystemMixin} 已把 {@code SoundEngine.play} 里 vanilla 形状的
 * {@code SoundBufferLibrary.getStream} INVOKE 重定向到接口注入的
 * {@code FabricSoundInstance.getAudioStream})。自己再对同一 INVOKE 上
 * @Redirect 必然与之冲突(先到者改写字节码,后到者 0 目标掀桌),所以
 * 这侧覆写 Fabric API 的钩子返回 {@link #openStream()},零自有 mixin。
 */
public final class FabricEntityVoiceSound extends EntityVoiceSound {

    public FabricEntityVoiceSound(UUID entityUuid, AbstractClientPlayer body, PcmAudio audio, float volume) {
        super(entityUuid, body, audio, volume);
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id,
                                                         boolean repeatInstantly) {
        return openStream();
    }
}
