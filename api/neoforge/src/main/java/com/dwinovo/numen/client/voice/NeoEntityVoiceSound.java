package com.dwinovo.numen.client.voice;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * NeoForge 侧的同伴 3D 语音:NeoForge 的 1.21.1 补丁已提前引入后续 MC 版本的
 * {@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)} 官方钩子,
 * {@code SoundEngine.play} 直接调它——覆写返回 {@link #openStream()} 即可,
 * 零 mixin,这已是官方钩子的最终形态。(vanilla 形状的取数 INVOKE 在 NeoForge
 * 运行时不存在,所以 Fabric 那个 @Redirect mixin 不能用在这侧。)
 */
public final class NeoEntityVoiceSound extends EntityVoiceSound {

    public NeoEntityVoiceSound(UUID entityUuid, AbstractClientPlayer body, PcmAudio audio, float volume) {
        super(entityUuid, body, audio, volume);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound, boolean looping) {
        return openStream();
    }
}
