package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.task.mine.NoPathVerdict.Verdict.UNREACHABLE;
import static com.dwinovo.numen.core.task.mine.NoPathVerdict.Verdict.REQUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「找不到路」什么时候才算「够不着」的证据。
 *
 * <p>这条规矩是拿真机日志换来的:退出存档再进入,共享目标索引随世界丢掉了,第一次
 * 查询烧完构建预算也没扫完({@code complete=false}),她已知的最近泥土在 51 格外——
 * 而脚边那片只是还没进图。她走不到,可一秒后图补齐,她就在脚边正常开挖了:那一次
 * 无路什么都不能说明。
 */
class NoPathVerdictTest {

    @Test
    void aCompleteMapMakesNoPathRealEvidence() {
        // 图是全的还找不到路 —— 这才叫够不着,该收工
        assertEquals(UNREACHABLE, NoPathVerdict.of(true, 0));
    }

    @Test
    void anIncompleteMapProvesNothing() {
        // 她自己都说"我还没看全",这时候的没路不构成定罪依据
        assertEquals(REQUERY, NoPathVerdict.of(false, 0));
    }

    @Test
    void aCompleteMapIsConclusiveEvenAfterColdRetries() {
        // 冷启动重查了几次,图一旦补齐就恢复正常判定 —— 之前的重试不该让它变得更宽容
        assertEquals(UNREACHABLE, NoPathVerdict.of(true, 5));
    }

    @Test
    void politenessHasAFloor() {
        // 图一直补不齐时不能无限客气:原地重查一辈子比如实收工更坏
        int last = NoPathVerdict.MAX_COLD_MAP_FAILS - 2;
        assertEquals(REQUERY, NoPathVerdict.of(false, last));
        assertEquals(UNREACHABLE, NoPathVerdict.of(false, last + 1));
    }
}
