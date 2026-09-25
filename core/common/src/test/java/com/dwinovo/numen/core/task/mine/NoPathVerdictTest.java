package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.task.mine.NoPathVerdict.Verdict.UNREACHABLE;
import static com.dwinovo.numen.core.task.mine.NoPathVerdict.Verdict.REQUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「找不到路」什么时候才算「够不着」的证据。
 *
 * <p>这条规矩是拿真机日志换来的:她已知的只有 51 格外的一簇,脚边那片还没进图。她走不到,
 * 可图一回来,她就在脚边正常开挖了:图回来之前的那一次无路什么都不能说明。
 */
class NoPathVerdictTest {

    @Test
    void aMapThatCameBackMakesNoPathRealEvidence() {
        // 图回来了还找不到路 —— 这才叫够不着,该收工
        assertEquals(UNREACHABLE, NoPathVerdict.of(true));
    }

    @Test
    void noPathBeforeTheMapIsInProvesNothing() {
        // 她自己都说"我还没看全",这时候的没路不构成定罪依据
        assertEquals(REQUERY, NoPathVerdict.of(false));
    }
}
