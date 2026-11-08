package com.dwinovo.numen.api.platform.services;

import com.dwinovo.numen.api.client.voice.EntityVoiceSound;
import com.dwinovo.numen.api.client.voice.PcmAudio;
import com.dwinovo.numen.api.client.voice.VoicePreviewSound;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;

/**
 * 语音声音实例的平台工厂。语音的 PCM 取数在两个 loader 上走不同机制,
 * 所以实例创建必须分家:
 * <ul>
 *   <li><b>Forge</b> — Forge 的补丁把 {@code SoundEngine.play} 的取数改成调
 *       {@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)} 钩子。
 *       该侧返回覆写了这个钩子的子类
 *       ({@code ForgeEntityVoiceSound} / {@code ForgeVoicePreviewSound}),零 mixin;</li>
 *   <li><b>Fabric</b> — Fabric API 的 sound-api 把取数重定向到接口注入的
 *       {@code FabricSoundInstance.getAudioStream}。该侧返回覆写了它的子类
 *       ({@code FabricEntityVoiceSound} / {@code FabricVoicePreviewSound}),零自有 mixin。</li>
 * </ul>
 * 客户端专用(声音引擎只存在于客户端);服务端永远不该调这两个方法。
 */
public interface IVoiceSoundFactory {

    /** 挂在同伴身上的一句 3D 空间语音(跟随实体、距离衰减)。 */
    EntityVoiceSound entityVoice(UUID entityUuid, AbstractClientPlayer body, PcmAudio audio, float volume);

    /** 设置界面试听的一句 2D 就地播放(不挂实体、无衰减)。 */
    VoicePreviewSound previewVoice(PcmAudio audio, float volume);
}
