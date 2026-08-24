package com.dwinovo.numen.client.voice;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/**
 * Fabric 侧的试听 2D 播放:与 {@link FabricEntityVoiceSound} 同理,覆写
 * Fabric API sound-api-v1 的 {@code FabricSoundInstance.getAudioStream}
 * 取数钩子,零自有 mixin。
 */
public final class FabricVoicePreviewSound extends VoicePreviewSound {

    public FabricVoicePreviewSound(PcmAudio audio, float volume) {
        super(audio, volume);
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id,
                                                         boolean repeatInstantly) {
        return openStream();
    }
}
