package com.dwinovo.numen.platform.services;

import com.dwinovo.numen.client.voice.EntityVoiceSound;
import com.dwinovo.numen.client.voice.PcmAudio;
import com.dwinovo.numen.client.voice.VoicePreviewSound;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;

/**
 * 语音声音实例的平台工厂。语音的 PCM 取数在两个 loader 上走不同机制,
 * 所以实例创建必须分家:
 * <ul>
 *   <li><b>NeoForge</b> — 1.21.1 的 NeoForge 补丁已提前引入后续 MC 版本的
 *       {@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)}
 *       官方钩子(vanilla 1.21.1 没有),其 {@code SoundEngine.play} 直接调它。
 *       该侧返回覆写了这个钩子的子类
 *       ({@code NeoEntityVoiceSound} / {@code NeoVoicePreviewSound}),零 mixin;</li>
 *   <li><b>Fabric</b> — 运行 vanilla 字节码,取数仍是
 *       {@code SoundBufferLibrary.getStream(ResourceLocation, boolean)} 调用,
 *       由 fabric 侧的 {@code MixinSoundEngine} @Redirect 到
 *       {@code VoicePcmSource.openStream()}。该侧直接返回 common 原类。</li>
 * </ul>
 * 客户端专用(声音引擎只存在于客户端);服务端永远不该调这两个方法。
 */
public interface IVoiceSoundFactory {

    /** 挂在同伴身上的一句 3D 空间语音(跟随实体、距离衰减)。 */
    EntityVoiceSound entityVoice(UUID entityUuid, AbstractClientPlayer body, PcmAudio audio, float volume);

    /** 设置界面试听的一句 2D 就地播放(不挂实体、无衰减)。 */
    VoicePreviewSound previewVoice(PcmAudio audio, float volume);
}
