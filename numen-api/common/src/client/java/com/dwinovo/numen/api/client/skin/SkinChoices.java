package com.dwinovo.numen.api.client.skin;

import com.dwinovo.numen.api.client.agent.ServerLibraries;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.skin.SkinVariants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 选皮肤时列出来的图:这台机器自己皮肤文件夹里的("我的")和服务器皮肤文件夹里的("服务器提供",服务端经皮肤那张表推来)。
 * 每张图带内容哈希(拿它和她穿着的那张对)与看图判断的手臂粗细({@link SkinVariants},判断一次、按哈希记着)。
 * 房主的两个文件夹是同一个,服务端不再给他一份"服务器提供",所以他只看到"我的"一节。
 */
public final class SkinChoices {

    /** 图在哪一边:这台机器上的,还是服务器上的。 */
    public enum Where { MINE, SERVER }

    /**
     * 一张可选的图。
     *
     * @param variant 看图判断的手臂粗细;图不能用时是 {@code null}
     * @param problem 图不能当皮肤用的原因;能用是 {@code null}
     */
    public record Choice(Where where, String name, byte[] png, String hash, String variant, SkinError problem) {

        public boolean usable() {
            return problem == null;
        }

        /** 用这个手臂粗细穿上,在签名缓存里的名字。 */
        public SkinRef ref(String armVariant) {
            return new SkinRef(hash, armVariant);
        }
    }

    /** 哈希 → 手臂粗细:同一张图只解码一次。 */
    private static final Map<String, String> VARIANTS = new ConcurrentHashMap<>();

    private SkinChoices() {}

    /** 这台机器皮肤文件夹里的全部,按名字排。 */
    public static List<Choice> mine() {
        SkinFolder folder = SkinFolder.here();
        List<Choice> out = new ArrayList<>();
        for (SkinFolder.Entry file : folder.list()) {
            try {
                out.add(choice(Where.MINE, file.name(), folder.read(file.name())));
            } catch (SkinError unusable) {
                out.add(new Choice(Where.MINE, file.name(), new byte[0], "", null, unusable));
            }
        }
        return out;
    }

    /** 服务器皮肤文件夹里的全部(服务端推来的最近一张表);房主没有。 */
    public static List<Choice> server() {
        List<Choice> out = new ArrayList<>();
        for (ServerLibraries.ServerSkin file : ServerLibraries.serverSkins()) {
            out.add(file.problem() != null ? new Choice(Where.SERVER, file.name(), new byte[0], "", null, file.problem())
                    : choice(Where.SERVER, file.name(), file.png()));
        }
        return out;
    }

    private static Choice choice(Where where, String name, byte[] png) {
        String hash = SkinRef.hashOf(png);
        try {
            return new Choice(where, name, png, hash, VARIANTS.computeIfAbsent(hash, h -> SkinVariants.infer(png)), null);
        } catch (SkinError undecodable) {
            return new Choice(where, name, new byte[0], "", null, undecodable);
        }
    }
}
