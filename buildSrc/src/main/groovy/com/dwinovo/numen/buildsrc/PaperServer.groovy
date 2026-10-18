package com.dwinovo.numen.buildsrc

/**
 * 测试用的 Paper 服务端怎么起:端到端测试(numen-e2e-paper.gradle)与 Paper 上的 GameTest(numen-gametest-paper.gradle)共用。
 *
 * <p>起的就是 numen/paper 的 {@code runServer}(run-paper)那一套:同一个 Paper 版本、同一份 JVM 参数、同一组插件 jar,
 * 只换运行目录、不接 Gradle 的标准输入输出。run-paper 的任务类型在 buildSrc 里看不见,所以这里按鸭子类型取它的几个公开属性。
 */
final class PaperServer {

    /** Paper 自己的下载缓存:每次整个清空就要重新下载原版服务端并打补丁(一两分钟),所以留着。 */
    private static final Set<String> KEEP = ['cache', 'libraries', 'versions'] as Set

    /** 测试世界:平坦,基岩 + 两层土 + 草地(服务端不给生成设置就报错并退回默认,所以写明)。 */
    static final String FLAT_SETTINGS = '{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},' +
            '{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],' +
            '"features":false,"lakes":false,"structure_overrides":[]}'

    private PaperServer() {}

    /** 现取一个空闲端口。 */
    static int freePort() {
        new ServerSocket(0).withCloseable { it.localPort }
    }

    /**
     * 清空运行目录(Paper 的下载缓存除外),写入 eula 与 server.properties:每次一个新世界。
     * 其余的 server.properties 项(端口、世界类型……)由调用方给。
     */
    static void freshDir(File dir, Map<String, String> properties) {
        dir.mkdirs()
        dir.listFiles().findAll { !(it.name in KEEP) }.each { it.deleteDir() }
        new File(dir, 'eula.txt').text = 'eula=true\n'
        new File(dir, 'server.properties').text = properties.collect { key, value -> "${key}=${value}" }.join('\n') + '\n'
    }

    /**
     * 起 Paper 的命令行:java、{@code run} 的 JVM 参数与系统属性、{@code extraJvmArgs}、Paper 服务端 jar、它要加载的插件。
     * 服务端 jar 由 run-paper 的下载服务给(已经下载过就是缓存里的那份)。{@code run} 是 numen/paper 的 {@code runServer} 任务。
     */
    static List<String> command(def run, List<String> extraJvmArgs, List<File> extraPlugins) {
        def jar = run.downloadsApiService.get().resolveBuild(run.progressLoggerFactory, run.version.get(), run.build.get()).toFile()
        def java = run.javaLauncher.get().executablePath.asFile.absolutePath
        def plugins = (run.pluginJars.files.toList() + extraPlugins).collect { "-add-plugin=${it.absolutePath}".toString() }
        // 输出写进日志文件,按 UTF-8 写,不跟着系统代码页(中文系统上是 GBK,日志里的中文就成了乱码)
        def encoding = ['-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
        return [java] + run.allJvmArgs + encoding + extraJvmArgs + ['-jar', jar.absolutePath, '--nogui'] + plugins
    }
}
