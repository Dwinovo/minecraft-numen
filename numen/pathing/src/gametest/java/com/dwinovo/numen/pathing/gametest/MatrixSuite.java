package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;
import java.util.function.Supplier;

/**
 * 可靠性矩阵的慢批({@link MatrixGameTests}):必须在真世界里穷举的证明,一遍要两分多钟,不在日常批里。
 * 只用 {@code -Pgametest=numen_pathing_matrix} 选跑;改了寻路的执行或走法前提时跑。
 */
public final class MatrixSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(MatrixGameTests.class);
    }

    @Override
    public List<Supplier<String>> background() {
        return List.of(com.dwinovo.numen.pathing.search.Searches::busy);
    }
}
