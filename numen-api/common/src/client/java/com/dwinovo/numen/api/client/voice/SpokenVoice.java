package com.dwinovo.numen.api.client.voice;

import com.dwinovo.numen.api.client.ClientKeys;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.client.data.ClientPrefs;
import com.dwinovo.numen.api.voice.VoiceProfile;

import java.util.UUID;

/**
 * 一条能出声的声线:服务端声线库里的一条({@link VoiceProfile},服务商、音色、参数)加这台电脑钥匙串里那家服务的 key。
 * 合成在这里发生,所以 key 不离开这台电脑。
 */
public record SpokenVoice(VoiceProfile profile, String apiKey) {

    public SpokenVoice {
        apiKey = apiKey == null ? "" : apiKey;
    }

    /**
     * 语音管线的唯一入口:这个同伴此刻应该用哪条声线。读她的绑定(服务端推来的状态)、声线库(服务端推来的表)和本机钥匙串;
     * 语音总开关关了、她没绑声线、绑的那条被删了或被玩家关掉了,都是 {@code null}(= 静音)。
     */
    public static SpokenVoice of(UUID companion) {
        if (!ClientPrefs.voiceEnabled() || companion == null) {
            return null;
        }
        ServerLibraries.Voice voice = ServerLibraries.voice(RemoteCompanions.getOrCreate(companion).voiceId());
        return voice == null ? null : new SpokenVoice(voice.profile(), ClientKeys.keychain().get(voice.profile().keyService()));
    }

    public float volume() {
        return profile.volume();
    }

    /** 据 backend 字段实例化对应 TTS 实现。 */
    public TtsBackend createBackend() {
        return switch (profile.backend()) {
            case VoiceProfile.BACKEND_SOVITS -> new GptSovitsTts(profile.url(), profile.refAudio(), profile.promptText(),
                    profile.textLang());
            case VoiceProfile.BACKEND_MINIMAX -> new MiniMaxTts(profile.url(), apiKey, profile.groupId(),
                    profile.model(), profile.voice());
            case VoiceProfile.BACKEND_FISH -> new FishAudioTts(profile.url(), apiKey, profile.voice(), profile.model());
            case VoiceProfile.BACKEND_DASHSCOPE -> new DashScopeTts(profile.url(), apiKey, profile.model(), profile.voice());
            case VoiceProfile.BACKEND_MIMO -> new MimoTts(profile.url(), apiKey, profile.model(), profile.voice());
            case VoiceProfile.BACKEND_DOUBAO -> new DoubaoTts(profile.url(), apiKey, profile.model(), profile.voice());
            default -> new OpenAiCompatibleTts(profile.url(), apiKey, profile.model(), profile.voice());
        };
    }
}
