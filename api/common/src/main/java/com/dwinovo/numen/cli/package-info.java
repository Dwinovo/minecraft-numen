/**
 * <strong>Public API.</strong> Numen 的命令行:她只有一个能力——执行一行命令({@code command} 工具)。一行分两层:
 * 不带 {@code /} 的是 Numen 给她的命令层(第 1 层,Numen 自己的调度器,不挂进 MC 的指令树),带 {@code /} 的是 MC 的
 * 指令树(第 0 层,原版与模组的指令)。第 1 层常用的动作再提升成快捷工具,两个入口是同一个处理函数。设计稿见
 * {@code docs/cli.md}。
 *
 * <p>插件经 {@code NumenApi.registerCommands} 拿到自己的 {@link com.dwinovo.numen.cli.CommandGroup},往里加
 * {@link com.dwinovo.numen.cli.Action}:参数用 {@link com.dwinovo.numen.cli.Param} 与
 * {@link com.dwinovo.numen.cli.ArgType} 声明,处理函数拿到 {@link com.dwinovo.numen.cli.ServerSource} 或
 * {@link com.dwinovo.numen.cli.ClientSource} 与读好的 {@link com.dwinovo.numen.cli.CommandArgs}。动作以她自己的权威执行;
 * 包装模组管理指令的动作可以声明 {@link com.dwinovo.numen.cli.Authority#SERVER_ON_HER},经
 * {@link com.dwinovo.numen.cli.ServerSource#onHer()} 拿到只对她执行的 {@link com.dwinovo.numen.cli.OnHer}。
 * {@link com.dwinovo.numen.cli.NumenCli}、{@link com.dwinovo.numen.cli.CommandRunner} 与 {@code command} 工具是
 * 引擎内部的机器。
 *
 * <p>一行命令也能只读不执行({@link com.dwinovo.numen.cli.NumenCli#read}):读成动作与参数,读好的参数再写回一行
 * ({@link com.dwinovo.numen.cli.CommandArgs#write})。存成命令文本的东西(建造的设计)与文字里写着的命令
 * ({@link com.dwinovo.numen.cli.WrittenCommands})都经它,和执行时是同一个解析器。
 */
package com.dwinovo.numen.cli;
