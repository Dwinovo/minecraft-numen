package com.dwinovo.numen.api.sdk;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给模型看的实体身份,<b>只有这一种</b>:{@code 种类@短码}({@code zombie@3f9a1c}),玩家(包括别的同伴)也一样({@code player@ab12cd})。
 * 生成与解析都只在这里,工具、事件、运行期状态各处不另写一份。名字只是给人读的附加信息,不是身份({@link #shown})。
 *
 * <p>短码是实体 UUID 的前 {@value #CODE_LENGTH} 位小写十六进制:跨重启、跨区块卸载不变,不像运行期编号会发给别的东西。种类只供阅读,
 * 认实体时不参与匹配;6 位在同一层世界里偶有撞码({@link #find} 说"撞了",并列出是哪几只),不靠它猜。
 *
 * <p>线程:服务端主线程。
 */
public final class EntityLabel {

    /** 短码长 {@value} 位:模型抄得起、一层世界里通常不撞。 */
    public static final int CODE_LENGTH = 6;

    private static final Pattern LABEL = Pattern.compile("([a-z0-9_./-]+)@([0-9a-f]{" + CODE_LENGTH + "})");

    private EntityLabel() {}

    /** 这个 UUID 的短码。 */
    public static String code(UUID uuid) {
        return uuid.toString().substring(0, CODE_LENGTH);
    }

    /** 这只实体的身份:{@code 种类@短码}。 */
    public static String of(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + "@" + code(entity.getUUID());
    }

    /** 写给模型读的样子:身份,玩家再带上名字({@code player@ab12cd (Steve)});名字不是身份,点名只认身份。 */
    public static String shown(Entity entity) {
        return entity instanceof Player player ? shown(player.getUUID(), player.getGameProfile().getName()) : of(entity);
    }

    /** 同上,给只知道 UUID 的玩家(不在线的主人):名字查不到就只写身份。 */
    public static String shown(UUID player, String name) {
        String id = "player@" + code(player);
        return name == null || name.isEmpty() ? id : id + " (" + name + ")";
    }

    /** 这段文字符合身份的格式 {@code kind@6位十六进制};对不对得上某一只是 {@link #find} 的事。 */
    public static boolean valid(String text) {
        return LABEL.matcher(text).matches();
    }

    /** 找的结果:恰一只、一只都没有、或者撞了码。 */
    public sealed interface Match<T> {

        /** 恰一只。 */
        record Found<T>(T entity) implements Match<T> {}

        /** 一只都没有。 */
        record None<T>() implements Match<T> {}

        /** 好几只同码,按发现顺序。 */
        record Ambiguous<T>(List<T> candidates) implements Match<T> {}
    }

    /** 按命中的个数分类:0 没有、1 就是它、多个是撞码。 */
    static <T> Match<T> classify(List<T> hits) {
        return switch (hits.size()) {
            case 0 -> new Match.None<>();
            case 1 -> new Match.Found<>(hits.get(0));
            default -> new Match.Ambiguous<>(List.copyOf(hits));
        };
    }

    /**
     * 这个身份此刻是这一层世界里已加载的哪一只(玩家也在内)。按短码精确匹配,种类前缀不参与。
     *
     * @param label 通过 {@link #valid} 的身份
     */
    public static Match<Entity> find(ServerLevel level, String label) {
        Matcher parsed = LABEL.matcher(label);
        if (!parsed.matches()) {
            throw new IllegalArgumentException("not an entity id: " + label);
        }
        String code = parsed.group(2);
        List<Entity> hits = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!e.isRemoved() && code(e.getUUID()).equals(code)) {
                hits.add(e);
            }
        }
        return classify(hits);
    }
}
