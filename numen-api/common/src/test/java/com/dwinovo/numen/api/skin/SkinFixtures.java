package com.dwinovo.numen.api.skin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 单测用的皮肤图。 */
public final class SkinFixtures {

    private SkinFixtures() {}

    /** 一张 png 的头部(检查只看文件头与宽高)。 */
    public static byte[] header(int width, int height) {
        byte[] png = new byte[33];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(magic, 0, png, 0, 8);
        png[11] = 13;
        png[12] = 'I';
        png[13] = 'H';
        png[14] = 'D';
        png[15] = 'R';
        for (int i = 0; i < 4; i++) {
            png[16 + i] = (byte) (width >> (24 - 8 * i));
            png[20 + i] = (byte) (height >> (24 - 8 * i));
        }
        return png;
    }

    /** 真能解码的 64×64 皮肤 png;{@code slim} 为真时细手臂用不到的一块区域留成透明。 */
    public static byte[] real(boolean slim) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF3A7BD5);
            }
        }
        if (slim) {
            image.setRGB(50, 16, 0);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
