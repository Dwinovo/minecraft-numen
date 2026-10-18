package com.dwinovo.numen.api.library;

/**
 * 一种库只声明"一条长什么样":放在哪个目录,以及一条是一个文件还是一个目录。存储、合并、开关、内置的同步都是
 * {@link Library} 与 {@link Shipped} 这一套,与库里装的是什么无关。
 *
 * <p>布局统一是 {@code <根>/<库>/<命名空间>/<名字>}:根是各 jar 里的 {@code numen/}、服务器的 {@code config/numen/server/}、
 * 玩家的 {@code config/numen/players/<uuid>/}。
 *
 * @param dir    库的目录名,如 {@code persona}
 * @param suffix 文件型的库:一条是 {@code <名字><suffix>} 一个文件;目录型的库为 {@code null}
 * @param marker 目录型的库:一条是 {@code <名字>/} 一个目录,里面必须有这个文件(如 {@code SKILL.md});文件型的库为 {@code null}
 */
public record LibraryKind(String dir, String suffix, String marker) {

    /** 一条一个文件:{@code <库>/<命名空间>/<名字><suffix>}。 */
    public static LibraryKind files(String dir, String suffix) {
        return new LibraryKind(dir, suffix, null);
    }

    /** 一条一个目录:{@code <库>/<命名空间>/<名字>/<marker>} 加目录里的其余文件。 */
    public static LibraryKind directories(String dir, String marker) {
        return new LibraryKind(dir, null, marker);
    }

    /** 一条是不是一个目录。 */
    public boolean isDirectory() {
        return marker != null;
    }
}
