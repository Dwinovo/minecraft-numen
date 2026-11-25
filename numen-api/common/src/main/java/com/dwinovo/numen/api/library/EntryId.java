package com.dwinovo.numen.api.library;

/**
 * 库里一条的名字:命名空间加名字,写成 {@code 命名空间:名字}(像数据包的 {@code 命名空间:路径})。
 * 随模组带来的条目在各自来源的命名空间下,主人自己建的条目放在 {@link #MINE} 下。
 */
public record EntryId(String namespace, String name) {

    /** 主人自己建的条目习惯放的命名空间。 */
    public static final String MINE = "my";

    public EntryId {
        if (!segment(namespace) || !segment(name)) {
            throw new IllegalArgumentException("namespace and name must not be empty or contain path characters: " + namespace + ":" + name);
        }
    }

    /** 主人自己建的条目。 */
    public static EntryId mine(String name) {
        return new EntryId(MINE, name);
    }

    /** 读 {@code 命名空间:名字};写法不对是 {@code null}。 */
    public static EntryId parse(String text) {
        if (text == null) {
            return null;
        }
        int colon = text.indexOf(':');
        if (colon < 0 || !segment(text.substring(0, colon)) || !segment(text.substring(colon + 1))) {
            return null;
        }
        return new EntryId(text.substring(0, colon), text.substring(colon + 1));
    }

    /** 能不能当目录名或文件名的一段:不空、不以点开头、不含路径与 Windows 文件名的非法字符。 */
    public static boolean segment(String text) {
        if (text == null || text.isBlank() || text.startsWith(".") || !text.equals(text.strip())) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ("\\/:*?\"<>|".indexOf(text.charAt(i)) >= 0 || Character.isISOControl(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 把玩家随手写的名字清成能当一段用的:去掉非法字符与首尾空白、开头的点;清完是空的用 {@code fallback}。 */
    public static String clean(String raw, String fallback) {
        String s = raw == null ? "" : raw.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "");
        while (s.startsWith(".")) {
            s = s.substring(1).strip();
        }
        return s.isEmpty() ? fallback : s;
    }

    @Override
    public String toString() {
        return namespace + ":" + name;
    }
}
