package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.platform.Services;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * <b>目标模组只在客户端</b>的联动(Litematica 这一类纯客户端模组)的名册:哪些名字空间是这样的、各自的目标模组是哪个,以及
 * 一位主人的客户端是不是提供它。
 *
 * <h2>"有没有"由主人的客户端决定,不由服务器决定</h2>
 * 这类联动的函数全是客户端函数({@code ClientCall}),真正碰目标模组的代码只在主人的客户端上跑。所以它的<b>登记在两侧无条件</b>——
 * 函数签名里没有目标模组的类型,服务端不装目标模组也读得出来(见 {@link com.dwinovo.numen.api.NumenPlugins#registerForClientMod});
 * 判据只有一处:<b>主人客户端自己问平台"这个模组装了没有"</b>({@link #provided}),装了就在报到时把这个名字空间报给服务端
 * ({@code ClientCapsPayload}),服务端把它记在 {@link Presence} 里。服务端的每一处("模型的 API 清单里有没有它"、"技能表里有没有它"、
 * "调用它时放不放行")都只读 {@link Presence} 里的这份,{@link #offered} 是它们共用的问法,谁也不自己再算。
 *
 * <p>不是这一类的名字空间(两侧都装的模组、Numen 自己的)不在名册里,{@link #offered} 对它们恒为真。
 *
 * <p>线程:任何线程。
 */
public final class ClientMods {

    /** 名字空间 → 目标模组 id,按登记顺序。 */
    private static final Map<String, String> TARGETS = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 服务端:同伴 → 她的主人,不知道是谁就是 null(服务端起来时由大脑的接线注入);没注入时(单测)没有主人。 */
    private static volatile Function<UUID, UUID> ownerOf = companion -> null;

    private ClientMods() {}

    /** 登记一个目标模组只在客户端的名字空间。<b>Numen API 内部调用</b>,插件经 {@code NumenPlugins.registerForClientMod}。 */
    public static void declare(String namespace, String targetMod) {
        TARGETS.put(namespace, targetMod);
    }

    /** 这个名字空间是不是目标模组只在客户端的联动。 */
    public static boolean isClientMod(String namespace) {
        return TARGETS.containsKey(namespace);
    }

    /** 这个名字空间的目标模组;不是这一类的是 null。 */
    public static String target(String namespace) {
        return TARGETS.get(namespace);
    }

    /** 同伴到主人的对应由服务端大脑的接线注入。<b>Numen API 内部调用</b>。 */
    public static void bindOwners(Function<UUID, UUID> companionToOwner) {
        ownerOf = companionToOwner;
    }

    /**
     * 客户端:这一台装着哪些联动的目标模组,也就是报到时要报给服务端的名字空间。问的是平台("模组装了没有"),加载器的差别由
     * {@link Services#platform()} 吸收。
     */
    public static Set<String> provided() {
        Set<String> out = new LinkedHashSet<>();
        synchronized (TARGETS) {
            TARGETS.forEach((namespace, target) -> {
                if (Services.platform().modName(target) != null) {
                    out.add(namespace);
                }
            });
        }
        return out;
    }

    /**
     * 服务端:这位主人的客户端提供不提供这个名字空间。不是目标模组只在客户端的名字空间恒为真;是的,看主人报到时报上来的那份
     * ({@link Presence#provides})。
     */
    public static boolean offeredToOwner(UUID owner, String namespace) {
        return !isClientMod(namespace) || Presence.provides(owner, namespace);
    }

    /**
     * 服务端:这只同伴的主人的客户端提供不提供这个名字空间(同 {@link #offeredToOwner})。她没有可查的主人就没有客户端可提供,不提供;
     * {@code companion} 是 null(只读随模组发布的文字,不是谁的视图)当作都提供。
     */
    public static boolean offered(UUID companion, String namespace) {
        if (!isClientMod(namespace) || companion == null) {
            return true;
        }
        UUID owner = ownerOf.apply(companion);
        return owner != null && offeredToOwner(owner, namespace);
    }
}
