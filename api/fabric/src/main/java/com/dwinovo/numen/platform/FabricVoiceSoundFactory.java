package com.dwinovo.numen.platform;

import com.dwinovo.numen.client.voice.EntityVoiceSound;
import com.dwinovo.numen.client.voice.PcmAudio;
import com.dwinovo.numen.client.voice.VoicePreviewSound;
import com.dwinovo.numen.platform.services.IVoiceSoundFactory;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;

/**
 * Fabric 侧的语音声音工厂:运行 vanilla 字节码,取数由 fabric 模块的
 * {@code MixinSoundEngine} 重定向到 {@code VoicePcmSource.openStream()},
 * 所以直接返回 common 原类,无需子类。
 */
public class FabricVoiceSoundFactory implements IVoiceSoundFactory {

    @Override
    public EntityVoiceSound entityVoice(UUID entityUuid, AbstractClientPlayer body,
                                        PcmAudio audio, float volume) {
        return new EntityVoiceSound(entityUuid, body, audio, volume);
    }

    @Override
    public VoicePreviewSound previewVoice(PcmAudio audio, float volume) {
        return new VoicePreviewSound(audio, volume);
    }
}
