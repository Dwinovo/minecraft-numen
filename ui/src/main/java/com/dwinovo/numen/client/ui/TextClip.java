package com.dwinovo.numen.client.ui;

/** Single-line text clipping shared by compact widgets. */
public final class TextClip {

    private static final String ELLIPSIS = "...";

    private TextClip() {}

    public static String ellipsize(IDrawSurface surface, String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (maxWidth <= 0) {
            return "";
        }
        if (surface.textWidth(value) <= maxWidth) {
            return value;
        }

        String marker = ELLIPSIS;
        while (!marker.isEmpty() && surface.textWidth(marker) > maxWidth) {
            marker = marker.substring(0, marker.length() - 1);
        }
        if (marker.isEmpty()) {
            return "";
        }

        int low = 0;
        int high = value.codePointCount(0, value.length());
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            String candidate = prefix(value, mid) + marker;
            if (surface.textWidth(candidate) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return prefix(value, low) + marker;
    }

    private static String prefix(String value, int codePoints) {
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }
}
