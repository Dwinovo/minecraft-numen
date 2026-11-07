package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.OnHer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 她身上有没有一个 YSM 动作在放,以及真人客户端替动作收尾的那一半。
 *
 * <h2>为什么要补这一半</h2>
 * YSM 动作的生命周期一半在服务端(记住动作名、广播、有人走近时整份重放),一半在本地玩家的客户端:本地玩家一按移动键,
 * 客户端就清掉动作并发一个 stop 回服务端。同伴是服务端假玩家,没有那个客户端,动作就一直挂着,每次有人走近还会重放。
 * 同伴的键盘是 {@link Controls},缺的客户端那一半由这里补上:她动了就发 {@code ysm play <她> stop}。
 *
 * <p>判据照 YSM 的客户端:前、后、左、右、跳、潜行任一有输入就算动了。潜行看身体本身
 * ({@link ServerPlayer#isShiftKeyDown}),因为有的地方直接设身体的潜行、不经 {@link Controls}。
 *
 * <p>标记只在内存里:YSM 自己的动作状态也不落盘,她进世界时 YSM 会自己 stop(出场时标记跟着清,见 {@link #onSpawn})。
 * 服务端不知道一个动作多长,所以单次动作放完、她又一直站着不动时标记仍在,动作要等她动、换模型或 stop 才收尾。
 */
final class Emotes {

    private final Ysm ysm;
    private final Set<UUID> playing = new HashSet<>();

    Emotes(Ysm ysm) {
        this.ysm = ysm;
    }

    /** 放一个动作,并记下她在放。 */
    void play(NumenPlayer body, OnHer her, String animation) {
        ysm.playAnimation(her, animation);
        playing.add(body.getUUID());
    }

    /** 停掉动作并清掉标记。返回停之前是不是记着她在放。 */
    boolean stop(NumenPlayer body, OnHer her) {
        ysm.stopAnimation(her);
        return playing.remove(body.getUUID());
    }

    /** 她刚进世界:YSM 在出生、重生、换维度时自己 stop,标记跟着清。 */
    void onSpawn(NumenPlayer body) {
        playing.remove(body.getUUID());
    }

    /** 每刻:有动作在放的同伴动了就停。 */
    void tick(MinecraftServer server) {
        if (playing.isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer body && playing.contains(body.getUUID())
                    && moved(body.controls(), body.isShiftKeyDown())) {
                playing.remove(body.getUUID());
                ysm.stopAnimation(server, body);
            }
        }
    }

    /** 这一刻她算不算在动:和 YSM 客户端判"本地玩家动了没有"同一组键。 */
    static boolean moved(Controls controls, boolean sneaking) {
        return sneaking
                || controls.held(Controls.Key.FORWARD) || controls.held(Controls.Key.BACK)
                || controls.held(Controls.Key.LEFT) || controls.held(Controls.Key.RIGHT)
                || controls.held(Controls.Key.JUMP);
    }

    boolean isPlaying(UUID id) {
        return playing.contains(id);
    }
}
