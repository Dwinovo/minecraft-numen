package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点名的实体此刻不在附近(死了、走远了、所在区块没加载、换了维度):参数解析那一步就失败,活不派下去;说的是那只实体的短码,下一步是
 * 重新看一眼附近。攻击一只刚死的猪、跟一个世界上没有的编号,走的都是这一句。
 */
class ServerCallEntityTest {

    @Test
    void aMissingEntityIsRefusedNamingItAndPointingAtTheScan() {
        ApiError gone = ServerCall.notNear(new EntityRef("pig@0a1b2c"));
        assertEquals(ErrorKind.NOT_FOUND, gone.kind());
        assertTrue(gone.getMessage().startsWith("pig@0a1b2c is not near you"), gone.getMessage());
        assertEquals("numen.scan.entities()", gone.hint());
    }
}
