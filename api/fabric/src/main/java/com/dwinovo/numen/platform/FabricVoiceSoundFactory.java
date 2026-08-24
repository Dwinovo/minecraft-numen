package com.dwinovo.numen.platform;

import com.dwinovo.numen.client.voice.EntityVoiceSound;
import com.dwinovo.numen.client.voice.FabricEntityVoiceSound;
import com.dwinovo.numen.client.voice.FabricVoicePreviewSound;
import com.dwinovo.numen.client.voice.PcmAudio;
import com.dwinovo.numen.client.voice.VoicePreviewSound;
import com.dwinovo.numen.platform.services.IVoiceSoundFactory;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;

/**
 * Fabric 侧的语音声音工厂:Fabric API 的 sound-api-v1 自带
 * {@code SoundEngine.play} 取数重定向(重定向到接口注入的
 * {@code FabricSoundInstance.getAudioStream}),自己再上同目标 @Redirect
 * 必与之冲突,所以返回覆写了 {@code getAudioStream} 钩子的子类,零自有 mixin。
 */
public class FabricVoiceSoundFactory implements IVoiceSoundFactory {

    @Override
    public EntityVoiceSound entityVoice(UUID entityUuid, AbstractClientPlayer body,
                                        PcmAudio audio, float volume) {
        return new FabricEntityVoiceSound(entityUuid, body, audio, volume);
    }

    @Override
    public VoicePreviewSound previewVoice(PcmAudio audio, float volume) {
        return new FabricVoicePreviewSound(audio, volume);
    }
}
