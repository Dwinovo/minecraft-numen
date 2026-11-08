package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Atomic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 签名缓存:服务端内部的一份按内容寻址的存储,玩家看不见也不管理。键是 {@link SkinRef}(图片内容的哈希加手臂模型),值是签好的皮肤
 * ({@link SkinProfile}:Mojang 签名的 textures 数据加原图)。同一张图同一个手臂模型签过一次,谁再要都直接取用,不再联网;
 * 同伴身上记的是对它的引用,换手臂模型时用这里存着的原图重签。
 *
 * <p>一个键一个文件 {@code <键>.json}。内存里一份,写落盘交 {@link Home#io 车道}。读可以在任何线程(签名队的工作线程在签之前先问它),
 * 写在服务端主线程。
 */
public final class SkinCache {

    private final Home home;
    private final Map<SkinRef, SkinProfile> memory = new ConcurrentHashMap<>();

    public SkinCache(Home home) {
        this.home = home;
    }

    private Path file(SkinRef ref) {
        return home.file("skin-cache").resolve(ref + ".json");
    }

    /** 这个键签好的皮肤;没有是 {@code null}。 */
    public SkinProfile find(SkinRef ref) {
        SkinProfile cached = memory.get(ref);
        if (cached != null) {
            return cached;
        }
        Path file = file(ref);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            SkinProfile read = SkinProfile.read(Files.readString(file, StandardCharsets.UTF_8));
            memory.put(ref, read);
            return read;
        } catch (IOException | RuntimeException unreadable) {
            Constants.LOG.warn("[numen-skin] 签名缓存 {} 读不了,当作没签过: {}", file.getFileName(), unreadable.toString());
            return null;
        }
    }

    /** 存下(换掉)这个键签好的皮肤。 */
    public void put(SkinRef ref, SkinProfile signed) {
        memory.put(ref, signed);
        String json = signed.write();
        home.io().write(() -> Atomic.write(file(ref), json));
    }
}
