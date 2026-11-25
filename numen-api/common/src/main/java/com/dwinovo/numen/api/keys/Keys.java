package com.dwinovo.numen.api.keys;

/**
 * 找一个服务的 key:主人自己的钥匙串,客户端上的一个文件({@link Keychain}),key 不离开这台电脑。
 */
public final class Keys {

    private final Keychain keychain;

    public Keys(Keychain keychain) {
        this.keychain = keychain;
    }

    /** 这个服务用哪把 key;没有是 {@code null}。 */
    public String find(String service) {
        return keychain.get(service);
    }

    /** 钥匙串本身。 */
    public Keychain keychain() {
        return keychain;
    }
}
