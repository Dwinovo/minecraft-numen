package com.dwinovo.numen.api.skin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 一张皮肤图的手臂是粗是细,看图本身就能判断,不必让玩家选:照 skinview-utils 的 {@code inferModelType}。细手臂比粗手臂窄一列,
 * 图上细手臂用不到的那四块区域({@link #UNUSED})会被皮肤作者留成透明(或者全涂纯黑、全涂纯白,作为"没用到"的标记):
 * 任一块里有透明像素,或四块全是纯黑、或四块全是纯白,就是细,否则是粗。高清皮肤按边长比例放大坐标;64×32 的旧格式没有左臂区域
 * (细手臂是 1.8 版以后才有的样式),恒为粗。
 *
 * <p>判断是纯函数 {@link #infer(Pixels)}:只看像素,不认识任何图片库;{@link #infer(byte[])} 是把 png 解成像素再问它。
 */
public final class SkinVariants {

    /** 细手臂用不到的四块区域(64×64 皮肤的像素坐标:x、y、宽、高):右臂顶面与外侧的下半、左臂同位。 */
    static final int[][] UNUSED = {{50, 16, 2, 4}, {54, 20, 2, 12}, {42, 48, 2, 4}, {46, 52, 2, 12}};

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int OPAQUE = 0xFF000000;

    private SkinVariants() {}

    /** 只看像素的图:宽高与某一点的 ARGB(非预乘)。 */
    public interface Pixels {

        int width();

        int height();

        int argb(int x, int y);
    }

    /** 这张图的手臂模型:{@link SkinProfile#VARIANT_SLIM} 或 {@link SkinProfile#VARIANT_CLASSIC}。 */
    public static String infer(Pixels image) {
        if (image.width() != image.height()) {
            return SkinProfile.VARIANT_CLASSIC;   // 64×32 的旧格式
        }
        boolean allBlack = true;
        boolean allWhite = true;
        for (int[] area : UNUSED) {
            int fromX = area[0] * image.width() / 64;
            int fromY = area[1] * image.height() / 64;
            int toX = (area[0] + area[2]) * image.width() / 64;
            int toY = (area[1] + area[3]) * image.height() / 64;
            for (int y = fromY; y < toY; y++) {
                for (int x = fromX; x < toX; x++) {
                    int argb = image.argb(x, y);
                    if ((argb & OPAQUE) != OPAQUE) {
                        return SkinProfile.VARIANT_SLIM;
                    }
                    allBlack &= argb == BLACK;
                    allWhite &= argb == WHITE;
                }
            }
        }
        return allBlack || allWhite ? SkinProfile.VARIANT_SLIM : SkinProfile.VARIANT_CLASSIC;
    }

    /**
     * 同上,图是 png 字节。
     *
     * @throws SkinError {@link SkinError#BAD_IMAGE} 读不出图
     */
    public static String infer(byte[] png) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException unreadable) {
            throw SkinError.of(unreadable, SkinError.BAD_IMAGE, "it cannot be decoded");
        }
        if (image == null) {
            throw SkinError.of(SkinError.BAD_IMAGE, "it cannot be decoded");
        }
        return infer(new Pixels() {
            @Override
            public int width() {
                return image.getWidth();
            }

            @Override
            public int height() {
                return image.getHeight();
            }

            @Override
            public int argb(int x, int y) {
                return image.getRGB(x, y);
            }
        });
    }
}
