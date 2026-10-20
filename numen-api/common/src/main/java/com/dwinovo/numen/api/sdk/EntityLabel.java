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
 * 给模型看的实体身份,<b>只有这一种</b>:生物写成 {@code 种类@短码}({@code zombie@3f9a1c}),玩家(包括别的同伴)写名字。
 * 生成与解析都只在这里,工具、事件、运行期状态各处不另写一份。
 *
 * <p>短码是实体 UUID 的前 {@value #CODE_LENGTH} 位小写十六进制:跨重启、跨区块卸载不变,不像运行期编号会发给别的东西。种类只供阅读,
 * 认实体时不参与匹配;6 位在同一层世界里偶有撞码({@link #find} 说"撞了",并列出是哪几只),不靠它猜。
 *
 * <p>线程:服务端主线程。
 */
public final class EntityLabel {

    /** 短码长 {@value} 位:模型抄得起、一层世界里通常不撞。 */
    public static final int CODE_LENGTH = 6;

    private static final Pattern MOB = Pattern.compile("([a-z0-9_./-]+)@([0-9a-f]{" + CODE_LENGTH + "})");
    private static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private EntityLabel() {}

    /** 这个 UUID 的短码。 */
    public static String code(UUID uuid) {
        return uuid.toString().substring(0, CODE_LENGTH);
    }

    /** 这只实体写给模型的样子:玩家是名字,别的是 {@code 种类@短码}。 */
    public static String of(Entity entity) {
        return entity instanceof Player player
                ? player.getGameProfile().getName()
                : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + "@" + code(entity.getUUID());
    }

    /** 这段文字写得像一个身份(生物 {@code 种类@短码},或玩家名字);对不对得上某一只是 {@link #find} 的事。 */
    public static boolean valid(String text) {
        return MOB.matcher(text).matches() || PLAYER.matcher(text).matches();
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
     * 这个身份此刻是这一层世界里已加载的哪一只。生物按短码精确匹配(种类前缀不参与),玩家按名字。
     *
     * @param label 通过 {@link #valid} 的身份
     */
    public static Match<Entity> find(ServerLevel level, String label) {
        Matcher mob = MOB.matcher(label);
        List<Entity> hits = new ArrayList<>();
        if (mob.matches()) {
            String code = mob.group(2);
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof Player) && !e.isRemoved() && code(e.getUUID()).equals(code)) {
                    hits.add(e);
                }
            }
        } else {
            for (Player p : level.players()) {
                if (p.getGameProfile().getName().equals(label)) {
                    hits.add(p);
                }
            }
        }
        return classify(hits);
    }
}
