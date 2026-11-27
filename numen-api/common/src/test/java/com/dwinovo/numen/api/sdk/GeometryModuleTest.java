package com.dwinovo.numen.api.sdk;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引擎自带的模块 {@code numen.geometry}:每个位置都会加减、量距离、比较、挪动,从她的入口跑。 */
class GeometryModuleTest {

    @Test
    void aPosAddsMeasuresAndCompares() {
        SdkFixture.ready();
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), """
                local p = numen.geometry.pos(1, 2, 3)
                local q = p:offset(1, 0, -1)
                print(q.x, q.y, q.z, p:dist(numen.geometry.pos(4, 6, 3)), (p + q).x, (q - p).z, p == numen.geometry.pos(1, 2, 3))
                """);
        assertTrue(run.ok(), run.message());
        String out = run.message().substring(run.message().indexOf("stdout:\n") + "stdout:\n".length());
        assertTrue(out.startsWith("2\t2\t2\t5\t3\t-1\ttrue"), out);
    }
}
