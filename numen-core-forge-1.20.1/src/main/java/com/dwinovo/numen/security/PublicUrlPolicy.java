package com.dwinovo.numen.security;

import java.net.*;
import java.util.List;
import java.util.Locale;

/** Resolves and validates public targets. Callers must connect to one of the returned addresses. */
public final class PublicUrlPolicy {
    private PublicUrlPolicy() { }

    public static ResolvedTarget resolve(String raw) throws UnknownHostException { return resolve(URI.create(raw)); }

    public static ResolvedTarget resolve(URI uri) throws UnknownHostException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("only HTTP(S) URLs are allowed");
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("URL credentials are forbidden");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL has no host");
        if (host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".localhost"))
            throw new IllegalArgumentException("localhost is forbidden");
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new UnknownHostException(host);
        for (InetAddress address : addresses) if (!isPublic(address))
            throw new IllegalArgumentException("non-public target address is forbidden: " + address.getHostAddress());
        int port = uri.getPort() >= 0 ? uri.getPort() : (scheme.equals("https") ? 443 : 80);
        return new ResolvedTarget(uri, host, port, List.of(addresses));
    }

    /** Compatibility validation when the caller only needs policy checking. */
    public static URI validate(String raw) throws UnknownHostException { return resolve(raw).uri(); }
    public static URI validate(URI uri) throws UnknownHostException { return resolve(uri).uri(); }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && c >= 64 && c <= 127) return false;
            if (a == 169 && c == 254) return false;
            if (a == 172 && c >= 16 && c <= 31) return false;
            if (a == 192 && c == 168) return false;
            if (a == 198 && (c == 18 || c == 19)) return false;
            if ((a == 192 && c == 0 && d == 2) || (a == 198 && c == 51 && d == 100)
                    || (a == 203 && c == 0 && d == 113)) return false;
        } else if (address instanceof Inet6Address) {
            int first = b[0] & 255;
            if ((first & 0xfe) == 0xfc) return false;
            if (first == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8) return false;
        }
        return true;
    }

    public record ResolvedTarget(URI uri, String host, int port, List<InetAddress> addresses) { }
}
