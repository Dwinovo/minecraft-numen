package com.dwinovo.numen.buildsrc

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

import java.util.concurrent.TimeUnit

/**
 * 测试起的那台服务端进程(端到端测试见 numen-e2e-neoforge.gradle 与 numen-e2e-paper.gradle,Paper 上的 GameTest 见
 * numen-gametest-paper.gradle)。做成构建服务,是为了进程一定收得干净:
 * 任务失败、被中断、构建结束,Gradle 都会关闭它,不会留一台还占着端口的服务端。
 */
abstract class TestServer implements BuildService<BuildServiceParameters.None>, AutoCloseable {

    private Process process
    private File log

    /** 在 {@code dir} 里起这条命令,输出(含错误)写进 {@code logFile};标准输入留着,关服时往里写 stop。 */
    synchronized void start(List<String> command, File dir, Map<String, String> env, File logFile) {
        if (process != null) {
            throw new IllegalStateException('the e2e server is already running')
        }
        log = logFile
        def builder = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).redirectOutput(logFile)
        builder.environment().clear()
        builder.environment().putAll(env)
        process = builder.start()
    }

    /** 等日志里出现 {@code marker}(服务端报"起好了"的那一行)。进程先死了或到点没等到是 false。 */
    boolean awaitReady(String marker, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (log.exists() && log.getText('UTF-8').contains(marker)) {
                return true
            }
            if (!process.alive) {
                return false
            }
            Thread.sleep(500)
        }
        return false
    }

    /** 等进程自己退出(服务端跑完该跑的、自己关服)。到点没退是 false,调用方决定怎么收场。 */
    boolean awaitExit(long timeoutMillis) {
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    File logFile() {
        return log
    }

    /** 先让服务端自己存盘退出(控制台的 stop),限时内没退就连子进程一起杀掉。 */
    synchronized void stop() {
        if (process == null) {
            return
        }
        def finished = process
        process = null
        def descendants = finished.toHandle().descendants().toList()
        try {
            finished.outputStream.write('stop\n'.getBytes('UTF-8'))
            finished.outputStream.flush()
        } catch (IOException ignored) {
            // 进程已经没了,下面照样收尾
        }
        if (!finished.waitFor(60, TimeUnit.SECONDS)) {
            finished.destroyForcibly()
            finished.waitFor(10, TimeUnit.SECONDS)
        }
        descendants.each { it.destroyForcibly() }
    }

    @Override
    void close() {
        stop()
    }
}
