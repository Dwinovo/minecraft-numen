package com.dwinovo.numen.mcp.client.auth;

/**
 * 登录凭据存在哪:宿主给的一个只存字符串的口(游戏里是钥匙串,和模型档案的 key 住在一起)。令牌与密钥只在这里,
 * 永不进日志、永不进配置文件。
 */
public interface CredentialStore {

    /** 没有是 {@code null}。 */
    String get(String key);

    void put(String key, String value);

    void remove(String key);
}
