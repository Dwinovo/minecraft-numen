package com.dwinovo.numen.platform;

import com.dwinovo.numen.client.voice.EntityVoiceSound;
import com.dwinovo.numen.client.voice.NeoEntityVoiceSound;
import com.dwinovo.numen.client.voice.NeoVoicePreviewSound;
import com.dwinovo.numen.client.voice.PcmAudio;
import com.dwinovo.numen.client.voice.VoicePreviewSound;
import com.dwinovo.numen.platform.services.IVoiceSoundFactory;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;

/**
 * NeoForge 侧的语音声音工厂:返回覆写了 NeoForge 补丁版
 * {@code SoundInstance.getStream} 官方钩子的子类,零 mixin
 * (vanilla 形状的取数 INVOKE 在 NeoForge 运行时不存在,Fabric 的
 * @Redirect 在这侧扫不到目标——分家的原因)。
 */
public class NeoForgeVoiceSoundFactory implements IVoiceSoundFactory {

    @Override
    public EntityVoiceSound entityVoice(UUID entityUuid, AbstractClientPlayer body,
                                        PcmAudio audio, float volume) {
        return new NeoEntityVoiceSound(entityUuid, body, audio, volume);
    }

    @Override
    public VoicePreviewSound previewVoice(PcmAudio audio, float volume) {
        return new NeoVoicePreviewSound(audio, volume);
    }
}
