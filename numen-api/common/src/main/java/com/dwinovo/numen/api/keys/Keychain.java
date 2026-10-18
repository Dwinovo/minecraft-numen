package com.dwinovo.numen.api.keys;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.store.Atomic;
import com.dwinovo.numen.api.store.Trees;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 一把把钥匙按服务(模型服务商、MCP 服务)存:{@code <归属根>/keys/<服务>.key},一个文件一把,文件里就是 key 原文。
 * 条目(模型档案、MCP 工具)里只写"用哪个服务",key 不在条目里,所以条目可以放心分享、同步、让别人看。
 *
 * <p>两处各一本:服务器的({@code server/keys/},服主出钱,玩家看不到)与玩家的({@code players/<uuid>/keys/},只有本人看得到、
 * 改得了)。用的时候先找主人的、再找服主的——{@link Keys}。内存里一份,写落盘交 {@link Home#io 车道}。
 * key 不进日志:这里没有任何一处把 key 或它的一段写进日志或异常消息。
 *
 * <p>线程:任何线程。
 */
public final class Keychain {

    private static final String SUFFIX = ".key";

    private final Home home;
    private final Map<String, String> keys = new ConcurrentHashMap<>();

    public Keychain(Home home) {
        this.home = home;
        reload();
    }

    private Path dir() {
        return home.file("keys");
    }

    /** 重扫磁盘。 */
    public synchronized void reload() {
        keys.clear();
        if (!Files.isDirectory(dir())) {
            return;
        }
        try (Stream<Path> files = Files.list(dir())) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(SUFFIX)).toList()) {
                String name = file.getFileName().toString();
                String service = name.substring(0, name.length() - SUFFIX.length());
                String key = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (EntryId.segment(service) && !key.isEmpty()) {
                    keys.put(service, key);
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-keys] 读不了钥匙串 {}: {}", dir(), e.toString());
        }
    }

    /** 这个服务的 key;没存过是 {@code null}。 */
    public String get(String service) {
        return service == null ? null : keys.get(service);
    }

    /** 存下(换掉)这个服务的 key;空 key 等于删掉。 */
    public synchronized void put(String service, String key) {
        if (!EntryId.segment(service)) {
            throw new IllegalArgumentException("服务名不能当文件名: " + service);
        }
        String clean = key == null ? "" : key.strip();
        Path file = dir().resolve(service + SUFFIX);
        if (clean.isEmpty()) {
            keys.remove(service);
            home.io().write(() -> Trees.deleteFile(file));
        } else {
            keys.put(service, clean);
            home.io().write(() -> Atomic.write(file, clean));
        }
    }

    /** 存了哪些服务的 key(不含 key 本身)。 */
    public Set<String> services() {
        return new TreeMap<>(keys).keySet();
    }
}
