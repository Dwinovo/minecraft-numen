package com.dwinovo.numen.client.voice;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

import java.util.concurrent.CompletableFuture;

/**
 * NeoForge 侧的试听 2D 播放:同 {@link NeoEntityVoiceSound},覆写 NeoForge
 * 补丁提供的官方 {@code getStream} 钩子返回内存 PCM 流,零 mixin。
 */
public final class NeoVoicePreviewSound extends VoicePreviewSound {

    public NeoVoicePreviewSound(PcmAudio audio, float volume) {
        super(audio, volume);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound, boolean looping) {
        return openStream();
    }
}
