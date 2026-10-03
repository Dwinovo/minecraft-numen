package com.dwinovo.numen.mcp.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外接大脑的两条安全判据。
 *
 * <p>这个服务器一旦开着，外部 AI 就能操控主人的同伴——挖矿、放方块、攻击。所以"谁够得着"
 * 和"要不要凭据"是同一件事的两面：只绑回环时没有凭据无所谓（本机才连得上），地址一放开，
 * 没凭据就是把同伴的操控权对整个局域网敞开。
 */
class McpConfigGuardTest {

    private static McpConfig with(String host, String token) {
        return new McpConfig(true, host, 8765, token, 300, List.of(), false);
    }

    // ---- 够不够得着 ----

    @Test
    void loopbackIsNotExposed() {
        assertFalse(with("127.0.0.1", "").lanExposed());
        assertFalse(with("localhost", "").lanExposed());
        assertFalse(with("LOCALHOST", "").lanExposed(), "大小写不该改变判断");
    }

    @Test
    void anythingElseIsExposed() {
        assertTrue(with("0.0.0.0", "t").lanExposed());
        assertTrue(with("192.168.1.7", "t").lanExposed(), "绑到具体网卡同样是对外的");
        assertTrue(with("::", "t").lanExposed());
    }

    // ---- 要不要凭据 ----

    @Test
    void loopbackWithoutATokenIsFine() {
        // 只有本机连得上,凭据可有可无——这是配置注释里那句 "fine on loopback" 的适用范围
        assertFalse(with("127.0.0.1", "").unguarded());
    }

    @Test
    void exposedWithoutATokenIsTheOneCombinationWeMustBlock() {
        // 无鉴权 + 局域网可达 = 谁都能操控你的同伴。设置页据此拦住保存
        assertTrue(with("0.0.0.0", "").unguarded());
        assertTrue(with("0.0.0.0", "   ").unguarded(), "空白不算凭据");
        assertTrue(with("192.168.1.7", null).unguarded());
    }

    @Test
    void exposedWithATokenIsAllowed() {
        assertFalse(with("0.0.0.0", "numen-abc").unguarded());
    }

    // ---- 令牌 ----

    @Test
    void aMintedTokenIsRecognisableAndLongEnoughToBeWorthHaving() {
        String token = McpConfig.mintToken();
        assertTrue(token.startsWith("numen-"), token);
        assertTrue(token.length() >= 28, "太短的凭据等于没有: " + token);
    }

    @Test
    void mintedTokensAreUrlSafeBecauseTheyRideInAQueryString() {
        // 认证走 Authorization 头或 ?token=,后者要求不含 + / = 这些会被转义的字符
        assertTrue(McpConfig.mintToken().matches("numen-[A-Za-z0-9_-]+"));
    }

    @Test
    void mintedTokensDoNotRepeat() {
        Set<String> minted = IntStream.range(0, 200)
                .mapToObj(i -> McpConfig.mintToken())
                .collect(Collectors.toSet());
        assertEquals(200, minted.size(), "重复的令牌说明随机源不对");
    }

    @Test
    void aFreshConfigAlreadyCarriesAToken() {
        // 默认带令牌,而不是等主人改地址时自己想起来设一个
        assertFalse(McpConfig.mintToken().isBlank());
    }

    // ---- 改一项不动其它项 ----

    @Test
    void eachWitherLeavesTheRestAlone() {
        McpConfig base = new McpConfig(true, "127.0.0.1", 8765, "tok", 300, List.of("todowrite"), false);

        McpConfig moved = base.withEndpoint("0.0.0.0", 9000, 60);
        assertEquals("tok", moved.token());
        assertEquals(List.of("todowrite"), moved.hiddenTools());
        assertTrue(moved.enabled());

        McpConfig retokened = base.withToken("new");
        assertEquals(8765, retokened.port());
        assertEquals("127.0.0.1", retokened.host());

        McpConfig rehidden = base.withHiddenTools(List.of("a", "b"));
        assertEquals("tok", rehidden.token());
        assertEquals(300, rehidden.callTimeoutSeconds());
    }
}
