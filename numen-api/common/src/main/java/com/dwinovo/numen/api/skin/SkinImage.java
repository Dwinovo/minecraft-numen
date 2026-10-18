package com.dwinovo.numen.api.skin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * 皮肤图:一张 png 能不能当皮肤用({@link #check}),以及按网址把它取回来({@link #fetch}、{@link #fetchTexture})。
 * 玩家给的网址由服务端去取,所以只收公网上的 http(s) 地址,不跟重定向,大小有上限——否则这条路就成了借服务器的手探内网。
 * 不合格一律抛 {@link SkinError#BAD_IMAGE}。
 */
public final class SkinImage {

    /** 一张图最大多少字节:64×64 的 png 远小于此。 */
    public static final int MAX_BYTES = 256 * 1024;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final String TEXTURE_HOST = "textures.minecraft.net";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private SkinImage() {}

    /** 是不是 64×64(或旧版 64×32)的 png,且不超过 {@link #MAX_BYTES}。 */
    public static void check(byte[] png) {
        if (png == null || png.length > MAX_BYTES) {
            throw SkinError.of(SkinError.BAD_IMAGE, "the image is bigger than " + MAX_BYTES / 1024 + " KiB");
        }
        if (png.length < 24 || !startsWith(png, PNG_MAGIC) || png[12] != 'I' || png[13] != 'H' || png[14] != 'D'
                || png[15] != 'R') {
            throw SkinError.of(SkinError.BAD_IMAGE, "it is not a png");
        }
        int width = intAt(png, 16);
        int height = intAt(png, 20);
        if (width != 64 || height != 64 && height != 32) {
            throw SkinError.of(SkinError.BAD_IMAGE, "a skin is 64x64 (or legacy 64x32), this one is "
                    + width + "x" + height);
        }
    }

    /** 玩家给的网址:取回来,检查过是皮肤图。 */
    public static byte[] fetch(String url) {
        byte[] png = download(url, SkinImage::publicHost);
        check(png);
        return png;
    }

    /** Mojang 签名数据里的皮肤图网址:只认它自己的图床。 */
    public static byte[] fetchTexture(String url) {
        byte[] png = download(url, uri -> TEXTURE_HOST.equals(uri.getHost()));
        check(png);
        return png;
    }

    private static byte[] download(String url, Predicate<URI> allowed) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException malformed) {
            throw SkinError.of(SkinError.BAD_IMAGE, "the address is not a url");
        }
        if (uri.getHost() == null || !"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())
                || !allowed.test(uri)) {
            throw SkinError.of(SkinError.BAD_IMAGE, "that address is not allowed");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<InputStream> response = ProxiedHttp.client(HttpClient.Redirect.NEVER).send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw SkinError.http(uri.getHost(), response.statusCode(), "");
                }
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw SkinError.of(SkinError.BAD_IMAGE, "the image is bigger than " + MAX_BYTES / 1024 + " KiB");
                }
                return bytes;
            }
        } catch (IOException e) {
            throw SkinError.of(e, SkinError.NETWORK, uri.getHost());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SkinError.of(e, SkinError.INTERRUPTED);
        }
    }

    /** 主机名解析出来的每个地址都是公网的(不是本机、内网、链路本地、组播)。 */
    static boolean publicHost(URI uri) {
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException unresolvable) {
            return false;
        }
    }

    static boolean isPublic(InetAddress address) {
        byte[] raw = address.getAddress();
        boolean uniqueLocalV6 = raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
        return !(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalV6);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int intAt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | bytes[at + 3] & 0xFF;
    }
}
