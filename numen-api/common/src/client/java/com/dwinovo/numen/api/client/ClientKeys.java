package com.dwinovo.numen.api.client;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.keys.Keychain;

/**
 * 这台电脑的钥匙串({@code config/numen/client/keys/}):语音合成与语音识别用的 key。合成和识别都在主人的客户端上做,
 * 所以这两类 key 只在他的机器上,不上传到服务器——服主也看不到。模型档案的 key 是另一回事,按服务商存在服务器上他自己的钥匙串里。
 *
 * <p>语音合成的 key 按声线条目写的服务名存({@link com.dwinovo.numen.api.voice.VoiceProfile#keyService});
 * 语音识别的 key 按识别服务商存({@link #stt})。客户端主线程专用。
 */
public final class ClientKeys {

    private static Keychain keychain;

    private ClientKeys() {}

    /** 这台电脑的钥匙串;第一次用时从磁盘读。 */
    public static Keychain keychain() {
        if (keychain == null) {
            keychain = new Keychain(new Home(Homes.clientDir(), NumenIo.inline("numen-keys")));
        }
        return keychain;
    }

    /** 语音识别服务商({@code SttProviders} 的 id)的 key 在钥匙串里叫什么:与合成用的服务名分开,各存各的。 */
    public static String stt(String provider) {
        return "stt-" + provider;
    }
}
