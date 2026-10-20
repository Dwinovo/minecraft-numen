package com.dwinovo.numen.api.skin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 一张签好的皮肤在签名缓存({@link SkinCache})里的名字:图片内容的哈希加手臂模型。同一张图同一个手臂模型,永远是同一个名字
 * (签一次就够);改了图、或换了手臂模型,就是另一个名字(要重签)。同伴的绑定里记的就是它的文字写法 {@code <哈希>-<手臂模型>},
 * 界面拿本机文件夹里图片的哈希对一对,就知道她现在穿的是不是这一张。
 *
 * <p>哈希取完整的 SHA-256:缓存里的签名数据是穿到身上的,短哈希被人撞出同一个名字就能让别人穿上自己的皮肤。
 */
public record SkinRef(String hash, String variant) {

    public SkinRef {
        variant = SkinProfile.VARIANT_SLIM.equals(variant) ? SkinProfile.VARIANT_SLIM : SkinProfile.VARIANT_CLASSIC;
    }

    /** 这张图在这个手臂模型下的名字。 */
    public static SkinRef of(byte[] image, String variant) {
        return new SkinRef(hashOf(image), variant);
    }

    /** 一张图的内容哈希。 */
    public static String hashOf(byte[] image) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 读文字写法;写法不对是 {@code null}。 */
    public static SkinRef parse(String text) {
        if (text == null) {
            return null;
        }
        int dash = text.lastIndexOf('-');
        if (dash != 64 || !text.substring(0, dash).chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return null;
        }
        String variant = text.substring(dash + 1);
        return SkinProfile.VARIANT_SLIM.equals(variant) || SkinProfile.VARIANT_CLASSIC.equals(variant)
                ? new SkinRef(text.substring(0, dash), variant) : null;
    }

    /** 文字写法:{@code <哈希>-<手臂模型>},也是缓存里的文件名。 */
    @Override
    public String toString() {
        return hash + "-" + variant;
    }
}
