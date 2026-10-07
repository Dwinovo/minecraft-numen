package com.dwinovo.numen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	public static final String MOD_ID = "numen_api";
	public static final String MOD_NAME = "Numen";

	/**
	 * 玩家配置的根目录名:{@code config/numen/}。
	 *
	 * <p><b>刻意不等于 {@link #MOD_ID}。</b> MOD_ID 是引擎这个 jar 在加载器眼里的身份
	 * ({@code numen_api});而配置目录是玩家眼里的产品名({@code numen})——同伴、人设、
	 * 皮肤、技能、providers 全在那底下。两者是两个问题,共用一个常量的结果是配置根
	 * 一分为二:技能和 mcp_clients 落进 config/numen_api/,其余落进 config/numen/,
	 * 而界面文案和文档说的一直是后者,于是玩家照提示放的技能根本不会被加载(#66)。
	 */
	public static final String CONFIG_ROOT = "numen";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);
}
