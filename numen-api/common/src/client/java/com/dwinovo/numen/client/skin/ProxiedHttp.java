package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.platform.Services;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 跟随玩家代理设置的 HTTP 客户端工厂——皮肤相关的外网请求(MineSkin 代签、
 * Mojang 档案查询)共用这一处。
 *
 * <p>每次按当前设置新建:代理可随时改,缓存住的 ProxySelector 会过期。
 */
final class ProxiedHttp {

    private ProxiedHttp() {}

    static HttpClient client() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = Services.CONFIG.getProxy();
        if (proxy != null && !proxy.isBlank()) {
            int colon = proxy.lastIndexOf(':');
            if (colon > 0) {
                try {
                    b.proxy(ProxySelector.of(new InetSocketAddress(proxy.substring(0, colon),
                            Integer.parseInt(proxy.substring(colon + 1).trim()))));
                } catch (RuntimeException ignored) {
                    // 端口不是数字之类——按直连走
                }
            }
        }
        return b.build();
    }
}
