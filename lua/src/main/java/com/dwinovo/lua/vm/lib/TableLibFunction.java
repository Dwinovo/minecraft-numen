package com.dwinovo.lua.vm.lib;

import com.dwinovo.lua.vm.LuaValue;

class TableLibFunction extends LibFunction {
	public LuaValue call() {
		return argerror(1, "table expected, got no value");
	}
}
