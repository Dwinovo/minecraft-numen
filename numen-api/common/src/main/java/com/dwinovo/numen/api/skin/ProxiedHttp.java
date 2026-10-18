package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.platform.Services;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 跟随服务器代理设置({@code Services.config().getProxy()},与调模型用的是同一处)的 HTTP 客户端工厂——皮肤相关的外网请求
 * (MineSkin 代签、Mojang 档案查询、取图片)共用这一处。
 *
 * <p>每次按当前设置新建:代理可随时改,缓存住的 ProxySelector 会过期。
 */
final class ProxiedHttp {

    private ProxiedHttp() {}

    /** 跟着重定向的客户端(对方是固定的几家服务)。 */
    static HttpClient client() {
        return client(HttpClient.Redirect.NORMAL);
    }

    /** 重定向怎么处理由调用方定:取玩家给的网址时不跟(跳去内网就是借我们的手探内网)。 */
    static HttpClient client(HttpClient.Redirect redirects) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(redirects);
        String proxy = Services.config().getProxy();
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
