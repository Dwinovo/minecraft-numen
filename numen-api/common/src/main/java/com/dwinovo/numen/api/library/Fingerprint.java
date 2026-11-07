package com.dwinovo.numen.api.library;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 一份内容的指纹:SHA-256 的前 12 位十六进制。"这份文件被动过没有"问的都是它——jar 同步的清单({@link Shipped})、
 * 模块账本记的内置指纹。48 位对一个库里的几百份内容,偶然撞上的概率可以忽略,所以不另造更长的。
 */
public final class Fingerprint {

    private Fingerprint() {}

    public static String of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }
}
