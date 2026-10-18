package com.dwinovo.numen.api.library;

/**
 * 服务器目录里一条内置的内容和 jar 的关系({@link Shipped} 的清单说的),界面据此提示。
 *
 * @param modified        管理员改过它(现在的内容不是上次同步写进去的)
 * @param updateAvailable jar 里有新版,他改过所以没换;新版写在旁边的 {@code <名字>.new}
 * @param sourceGone      它的来源(插件)已经卸载,或来源这一版不再带它;他改过所以留着
 */
public record Provenance(boolean modified, boolean updateAvailable, boolean sourceGone) {

    /** 没有来源可言:我的组、不经同步放进来的条目。 */
    public static final Provenance NONE = new Provenance(false, false, false);

    /** 一条内置的来历从哪里查。 */
    @FunctionalInterface
    public interface Source {

        /** 没有清单的地方(单测、玩家目录)用:什么来历也没有。 */
        Source NONE = (kind, item) -> Provenance.NONE;

        Provenance of(LibraryKind kind, Shelf.Item item);
    }
}
