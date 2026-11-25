package com.dwinovo.numen.api.library;

import com.dwinovo.numen.api.home.Home;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主人电脑上她的大脑要用的各种库的出处:每种库在这台电脑上只有一份货架({@link Shelf}),随模组带来的默认内容(人设、模型档案、声线、
 * 接进来的 MCP 服务器的推荐配置……)开张时同步进去({@link Shipped#sync}),玩家自己写的、改的也在这一份里——{@link Library} 的"服务器目录"与"我的"
 * 在这里是同一个货架({@link Library#merged}),条目是随模组带来的、改过了,还是自己的,看清单({@link Shipped.Manifest})。
 * 各库的消费方(人设、模型档案、声线、技能、MCP 工具)拿同一种库的同一份 {@link Library},读写的是同一个对象。
 *
 * <p>随模组发布的技能与内置模块不在这里:它们在服务端,客户端从座位上的目录知道名字、用到时才取(见 {@code SkillRegistry.Bundled}、
 * {@code HerModules.Builtins})。
 *
 * <p>线程:任何线程取;写走货架的车道。
 */
public final class LibraryHub {

    private final Home home;
    private final Switches switches;
    private final Shipped.Manifest manifest;
    private final Map<String, Shelf> shelves = new ConcurrentHashMap<>();
    private final Map<String, Library> libraries = new ConcurrentHashMap<>();

    private LibraryHub(Home home, Switches switches, Shipped.Manifest manifest) {
        this.home = home;
        this.switches = switches;
        this.manifest = manifest;
    }

    /**
     * 在 {@code home} 下开一份:随模组带来的、属于 {@code kinds} 这几种库的默认内容同步进去,开关记在 {@code switches}。再调一次相当于重启:
     * 同步按清单重新做一遍。
     *
     * @param kinds 要同步的库的目录名(别的库里随发布的内容不进来)
     */
    public static LibraryHub local(Home home, Switches switches, List<Shipped.Bundle> bundles, Set<String> kinds) {
        return new LibraryHub(home, switches, Shipped.sync(home.dir(), bundles, kinds));
    }

    /** 这种库。 */
    public Library library(LibraryKind kind) {
        return libraries.computeIfAbsent(kind.dir(), dir -> {
            Shelf shelf = shelf(kind);
            return new Library(kind, shelf, shelf, switches, manifest);
        });
    }

    /** 随模组同步的清单。 */
    public Shipped.Manifest manifest() {
        return manifest;
    }

    /** 这种库的货架。 */
    public Shelf shelf(LibraryKind kind) {
        return shelves.computeIfAbsent(kind.dir(), dir -> new Shelf(kind, home));
    }

    /** 把已经开过的库都重扫一遍:手改磁盘上的文件在这里读到。 */
    public void reloadAll() {
        shelves.values().forEach(Shelf::reload);
    }
}
