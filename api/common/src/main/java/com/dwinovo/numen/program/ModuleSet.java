package com.dwinovo.numen.program;

import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.script.BuiltinModules;
import com.dwinovo.numen.script.Modules;
import net.minecraft.network.FriendlyByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 一段程序要用的她的模块,客户端交给服务端的样子:清单(模块名 → 内容指纹)加这个连接上服务端还没有的那些正文(指纹 → 正文)。
 * 正文以指纹为名({@link Modules#fingerprint}),所以同一份正文只送一次,服务端按指纹缓存({@link ModuleCache})。
 *
 * <p>模块的真源是主人客户端目录里的文件;这里只是某一刻的一张快照。
 *
 * @param manifest 她此刻每个模块的名字和正文的指纹,按名字排
 * @param bodies   清单里服务端还没有的那些正文,指纹到正文;服务端缓存里已有的不再送
 */
public record ModuleSet(Map<String, String> manifest, Map<String, String> bodies) {

    /** 上行:两张表的键值都是长度不由我们定的文字。 */
    public void write(FriendlyByteBuf buf) {
        buf.writeMap(manifest, Wire::writeText, Wire::writeText);
        buf.writeMap(bodies, Wire::writeText, Wire::writeText);
    }

    public static ModuleSet read(FriendlyByteBuf buf) {
        return new ModuleSet(buf.readMap(LinkedHashMap::new, Wire::readText, Wire::readText),
                buf.readMap(LinkedHashMap::new, Wire::readText, Wire::readText));
    }

    /** 随模组与插件发布的那一套,正文全带上:没有主人的客户端在场(管理员的 drive、重启后再跑的那一行)的程序用。 */
    public static ModuleSet factory() {
        SortedMap<String, String> manifest = new TreeMap<>();
        Map<String, String> bodies = new LinkedHashMap<>();
        BuiltinModules.all().forEach((name, builtin) -> {
            String hash = Modules.fingerprint(builtin.code());
            manifest.put(name, hash);
            bodies.put(hash, builtin.code());
        });
        return new ModuleSet(manifest, bodies);
    }
}
