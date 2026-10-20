package com.dwinovo.numen.api.skin;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 手臂粗细的判断:照 skinview-utils 的 {@code inferModelType},只看细手臂用不到的四块区域。 */
class SkinVariantsTest {

    private static final int SKIN = 0xFF3A7BD5;
    private static final int CLEAR = 0x00000000;
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    /** 一张 {@code size}×{@code height} 的图,每个点是 {@code fill}。 */
    private static int[][] image(int size, int height, int fill) {
        int[][] pixels = new int[height][size];
        for (int[] row : pixels) {
            java.util.Arrays.fill(row, fill);
        }
        return pixels;
    }

    private static String infer(int[][] pixels) {
        return SkinVariants.infer(new SkinVariants.Pixels() {
            @Override
            public int width() {
                return pixels[0].length;
            }

            @Override
            public int height() {
                return pixels.length;
            }

            @Override
            public int argb(int x, int y) {
                return pixels[y][x];
            }
        });
    }

    /** 把一块区域(64×64 坐标)涂成某色,{@code scale} 倍放大。 */
    private static void paint(int[][] pixels, int[] area, int scale, int color) {
        for (int y = area[1] * scale; y < (area[1] + area[3]) * scale; y++) {
            for (int x = area[0] * scale; x < (area[0] + area[2]) * scale; x++) {
                pixels[y][x] = color;
            }
        }
    }

    @Test
    void anOpaqueSkinIsClassic() {
        assertEquals(SkinProfile.VARIANT_CLASSIC, infer(image(64, 64, SKIN)));
    }

    @Test
    void aSingleTransparentPixelInAnyOfTheFourAreasMakesItSlim() {
        for (int[] area : SkinVariants.UNUSED) {
            int[][] pixels = image(64, 64, SKIN);
            pixels[area[1] + area[3] - 1][area[0] + area[2] - 1] = CLEAR;   // 区域最后一个点
            assertEquals(SkinProfile.VARIANT_SLIM, infer(pixels), java.util.Arrays.toString(area));
        }
    }

    @Test
    void aTransparentPixelJustOutsideTheAreasDoesNotCount() {
        int[][] pixels = image(64, 64, SKIN);
        pixels[16][52] = CLEAR;   // (50,16,2,4) 的右邻
        pixels[20][53] = CLEAR;   // (54,20,2,12) 的左邻
        pixels[47][42] = CLEAR;   // (42,48,2,4) 的上邻
        pixels[52][45] = CLEAR;   // (46,52,2,12) 的左邻
        assertEquals(SkinProfile.VARIANT_CLASSIC, infer(pixels));
    }

    @Test
    void fourAreasAllPureBlackOrAllPureWhiteMeanSlim() {
        for (int color : new int[] {BLACK, WHITE}) {
            int[][] pixels = image(64, 64, SKIN);
            for (int[] area : SkinVariants.UNUSED) {
                paint(pixels, area, 1, color);
            }
            assertEquals(SkinProfile.VARIANT_SLIM, infer(pixels), Integer.toHexString(color));
        }
    }

    @Test
    void anAreaThatIsOnlyPartlyBlackOrBlackMixedWithWhiteStaysClassic() {
        int[][] threeBlack = image(64, 64, SKIN);
        for (int i = 0; i < 3; i++) {
            paint(threeBlack, SkinVariants.UNUSED[i], 1, BLACK);
        }
        assertEquals(SkinProfile.VARIANT_CLASSIC, infer(threeBlack), "四块要全黑才算");

        int[][] mixed = image(64, 64, SKIN);
        paint(mixed, SkinVariants.UNUSED[0], 1, BLACK);
        paint(mixed, SkinVariants.UNUSED[1], 1, BLACK);
        paint(mixed, SkinVariants.UNUSED[2], 1, WHITE);
        paint(mixed, SkinVariants.UNUSED[3], 1, WHITE);
        assertEquals(SkinProfile.VARIANT_CLASSIC, infer(mixed), "黑白各占一半不算");
    }

    @Test
    void aHighResolutionSkinScalesTheAreasWithItsSize() {
        int[][] slim = image(128, 128, SKIN);
        slim[2 * 16 + 7][2 * 50 + 3] = CLEAR;   // 放大一倍后 (50,16,2,4) 里面
        assertEquals(SkinProfile.VARIANT_SLIM, infer(slim));

        int[][] classic = image(128, 128, SKIN);
        classic[16][50] = CLEAR;   // 还在没放大的坐标上:放大后那里是别的部位
        assertEquals(SkinProfile.VARIANT_CLASSIC, infer(classic));

        int[][] blackHd = image(256, 256, SKIN);
        for (int[] area : SkinVariants.UNUSED) {
            paint(blackHd, area, 4, BLACK);
        }
        assertEquals(SkinProfile.VARIANT_SLIM, infer(blackHd));
    }

    @Test
    void theLegacy64By32FormatHasNoLeftArmAndIsAlwaysClassic() {
        int[][] legacy = image(64, 32, CLEAR);
        assertEquals(SkinProfile.VARIANT_CLASSIC, infer(legacy));
    }

    @Test
    void aPngIsDecodedAndAskedTheSameQuestion() throws IOException {
        BufferedImage slim = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                slim.setRGB(x, y, SKIN);
            }
        }
        BufferedImage classic = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        classic.setData(slim.getRaster());
        slim.setRGB(55, 22, CLEAR);

        assertEquals(SkinProfile.VARIANT_SLIM, SkinVariants.infer(png(slim)));
        assertEquals(SkinProfile.VARIANT_CLASSIC, SkinVariants.infer(png(classic)));
        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> SkinVariants.infer(new byte[] {1, 2, 3})).code());
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
