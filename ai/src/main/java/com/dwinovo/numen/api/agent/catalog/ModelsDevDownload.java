package com.dwinovo.numen.api.agent.catalog;

import com.dwinovo.numen.api.agent.http.HttpLlmTransport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** 从 models.dev 拉 {@code api.json} 并裁成快照格式。运行时缓存和手动更新快照共用。 */
final class ModelsDevDownload {

    static final URI API = URI.create("https://models.dev/api.json");

    private ModelsDevDownload() {}

    /** 裁过的文本;非 200、网络错、格式不对都让 future 失败。代理是 {@code host:port},空 = 直连。 */
    static CompletableFuture<String> slimmed(URI source, String proxy) {
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET().build();
        return HttpLlmTransport.newClient(proxy)
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + " from " + source);
                    }
                    String slim = ModelsDev.slim(response.body());
                    ModelsDev.parse(slim);   // 落盘前先确认读得回来
                    return slim;
                });
    }
}
