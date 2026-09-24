/**
 * <strong>Public API.</strong> Numen 的命令行:模型经 {@code numen} 工具写一行命令,常用的动作再提升成快捷工具,
 * 两个入口是同一个处理函数。设计稿见 {@code docs/cli.md}。
 *
 * <p>插件经 {@code NumenApi.registerCommands} 拿到自己的 {@link com.dwinovo.numen.cli.CommandGroup},往里加
 * {@link com.dwinovo.numen.cli.Action}:参数用 {@link com.dwinovo.numen.cli.Param} 与
 * {@link com.dwinovo.numen.cli.ArgType} 声明,处理函数拿到 {@link com.dwinovo.numen.cli.ServerSource} 或
 * {@link com.dwinovo.numen.cli.ClientSource} 与读好的 {@link com.dwinovo.numen.cli.CommandArgs}。
 * {@link com.dwinovo.numen.cli.NumenCli} 与 {@code numen} 工具是引擎内部的机器。
 */
package com.dwinovo.numen.cli;
