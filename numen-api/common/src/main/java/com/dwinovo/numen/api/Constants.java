package com.dwinovo.numen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	public static final String MOD_ID = "numen";
	public static final String MOD_NAME = "Numen";

	/** 玩家配置的根目录名:{@code config/numen/}——同伴、人设、皮肤、技能、providers 全在那底下;和模组 id 是同一个名字。 */
	public static final String CONFIG_ROOT = MOD_ID;
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);
}
