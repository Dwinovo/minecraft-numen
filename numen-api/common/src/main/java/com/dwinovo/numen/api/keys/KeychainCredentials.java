package com.dwinovo.numen.api.keys;

import com.dwinovo.numen.mcp.client.auth.CredentialStore;

/**
 * MCP 客户端的登录令牌存进钥匙串({@link Keychain}),和模型服务的 key 同住一本:服务器那一处用服主的,玩家自己那一处用玩家的。
 * 令牌在钥匙串里以 {@code mcp-oauth.<服务器名>} 为"服务名"。
 */
public final class KeychainCredentials implements CredentialStore {

    private final Keychain keychain;

    public KeychainCredentials(Keychain keychain) {
        this.keychain = keychain;
    }

    @Override
    public String get(String key) {
        return keychain.get(key);
    }

    @Override
    public void put(String key, String value) {
        keychain.put(key, value);
    }

    @Override
    public void remove(String key) {
        keychain.put(key, "");   // 空 key 等于删掉
    }
}
