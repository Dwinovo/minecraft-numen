package com.dwinovo.numen.client.voice;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

import java.util.concurrent.CompletableFuture;

/**
 * Forge 侧的试听 2D 播放:与 {@link ForgeEntityVoiceSound} 同理,覆写 Forge
 * 1.20.1 补丁的 {@code SoundInstance.getStream} 取数钩子,零 mixin。
 */
public final class ForgeVoicePreviewSound extends VoicePreviewSound {

    public ForgeVoicePreviewSound(PcmAudio audio, float volume) {
        super(audio, volume);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound, boolean looping) {
        return openStream();
    }
}
