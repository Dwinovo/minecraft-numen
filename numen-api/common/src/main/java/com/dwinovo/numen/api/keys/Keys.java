package com.dwinovo.numen.api.keys;

/**
 * 一位玩家用一个服务时找 key 的顺序:先找他自己的钥匙串,再找服主的。找到的带着出处——服主的 key 花服主的钱,
 * 要受每人每天的 token 限额({@link com.dwinovo.numen.api.config.ServerSettings});主人自己的不受限。
 */
public final class Keys {

    /** key 从哪本钥匙串来。 */
    public enum Source { OWNER, SERVER }

    /** 找到的 key 与它的出处。key 不进日志,所以 {@code toString} 不带它。 */
    public record Found(String key, Source source) {

        @Override
        public String toString() {
            return "Found[" + source + "]";
        }
    }

    private final Keychain owner;
    private final Keychain server;

    public Keys(Keychain owner, Keychain server) {
        this.owner = owner;
        this.server = server;
    }

    /** 这个服务用哪把 key;两本里都没有是 {@code null}。 */
    public Found find(String service) {
        String own = owner.get(service);
        if (own != null) {
            return new Found(own, Source.OWNER);
        }
        String shared = server.get(service);
        return shared == null ? null : new Found(shared, Source.SERVER);
    }

    /** 他自己的那本。 */
    public Keychain owner() {
        return owner;
    }

    /** 服主的那本。 */
    public Keychain server() {
        return server;
    }
}
