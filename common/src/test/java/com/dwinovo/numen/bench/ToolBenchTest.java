package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.http.HttpLlmTransport;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the offline tool-call benchmark. Run with:
 *
 * <pre>{@code  NUMEN_BENCH_API_KEY=sk-... NUMEN_BENCH_MODEL=deepseek-chat \
 *      NUMEN_BENCH_PROVIDER=deepseek ./gradlew :common:toolBench }</pre>
 *
 * Skips (does not fail) when no API key is configured, so a normal
 * {@code ./gradlew test} never makes a network call. Tagged {@code bench} so
 * the default {@code test} task excludes it.
 *
 * <p>Output: a per-case + aggregate report to stdout, and one appended row to
 * {@code benchmark/history.csv} at the repo root — so the headline numbers are
 * comparable across commits and a regression is a number that dropped.
 */
@Tag("bench")
class ToolBenchTest {

    /**
     * Bring up the vanilla registries once. Arg-validity scoring runs each
     * world-action tool's real {@code toTaskRecord}, which resolves block/item ids
     * against {@code BuiltInRegistries} — those static-init only after bootstrap, so
     * without this the first registry touch throws {@code ExceptionInInitializerError}.
     */
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void runBenchmark() throws Exception {
        ToolBench.Config cfg = ToolBench.Config.fromEnv();
        Assumptions.assumeTrue(cfg.hasKey(),
                "NUMEN_BENCH_API_KEY not set — skipping the tool-call benchmark");

        List<ToolBench.BenchCase> cases = loadCases();
        Assumptions.assumeFalse(cases.isEmpty(), "no benchmark cases found on the classpath");

        LlmProvider provider = ToolBench.provider(cfg);
        HttpLlmTransport transport = new HttpLlmTransport(cfg.proxy, java.util.Map.of());
        String url = ToolBench.composeUrl(cfg, provider);
        List<NumenTool> tools = ToolBench.tools();

        System.out.printf("%n[tool-bench] provider=%s model=%s tools=%d cases=%d samples=%d url=%s%n",
                provider.name(), cfg.model, tools.size(), cases.size(), cfg.samples, url);

        List<ToolBench.CaseResult> results = new java.util.ArrayList<>();
        for (ToolBench.BenchCase c : cases) {
            ToolBench.CaseResult r = ToolBench.runCase(c, cfg, provider, transport, url, tools);
            results.add(r);
            System.out.println(formatCaseLine(r));
        }

        Report report = aggregate(results, cfg.samples);
        System.out.println(report.text);

        boolean anyAnswered = results.stream().flatMap(r -> r.samples().stream())
                .anyMatch(s -> s.calledTool() != null || s.argsValid());
        // A run where every single sample errored means misconfig (bad key/url/model),
        // not a harness regression — surface that as a real failure.
        org.junit.jupiter.api.Assertions.assertTrue(anyAnswered,
                "every sample errored — check API key / base URL / model");

        writeHistory(cfg, report);
    }

    // ---- reporting ----

    private record Report(double selection, double passk, double argsValid, Double argsMatch,
                          int cases, int samples, String text) {}

    private static Report aggregate(List<ToolBench.CaseResult> results, int samples) {
        double sel = results.stream().mapToDouble(ToolBench.CaseResult::selectionRate).average().orElse(0);
        double passk = results.isEmpty() ? 0
                : (double) results.stream().filter(ToolBench.CaseResult::allSelectionPassed).count() / results.size();
        double valid = results.stream().mapToDouble(ToolBench.CaseResult::argsValidRate).average().orElse(0);
        List<Double> matches = results.stream()
                .map(ToolBench.CaseResult::argsMatchRate).filter(java.util.Objects::nonNull).toList();
        Double match = matches.isEmpty() ? null
                : matches.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("\n──────── tool-bench summary ────────\n");
        sb.append(String.format(Locale.ROOT, "  tool selection    : %5.1f%%   (mean across samples)%n", sel * 100));
        sb.append(String.format(Locale.ROOT, "  reliability pass^%d : %5.1f%%   (cases where ALL %d samples passed)%n",
                samples, passk * 100, samples));
        sb.append(String.format(Locale.ROOT, "  args valid        : %5.1f%%%n", valid * 100));
        sb.append(match == null ? "  args match        :   n/a\n"
                : String.format(Locale.ROOT, "  args match        : %5.1f%%  (cases with an args check)%n", match * 100));
        sb.append("  ── by bucket ──\n");
        java.util.Map<String, List<ToolBench.CaseResult>> byBucket = new java.util.TreeMap<>();
        for (ToolBench.CaseResult r : results) {
            byBucket.computeIfAbsent(r.bucket(), k -> new java.util.ArrayList<>()).add(r);
        }
        for (var e : byBucket.entrySet()) {
            double bsel = e.getValue().stream().mapToDouble(ToolBench.CaseResult::selectionRate).average().orElse(0);
            double bpk = (double) e.getValue().stream()
                    .filter(ToolBench.CaseResult::allSelectionPassed).count() / e.getValue().size();
            sb.append(String.format(Locale.ROOT, "    %-15s sel %3.0f%%  pass^%d %3.0f%%  (%d cases)%n",
                    e.getKey(), bsel * 100, samples, bpk * 100, e.getValue().size()));
        }
        sb.append("────────────────────────────────────");
        return new Report(sel, passk, valid, match, results.size(), samples, sb.toString());
    }

    private static String formatCaseLine(ToolBench.CaseResult r) {
        Double m = r.argsMatchRate();
        String matchStr = m == null ? " — " : String.format(Locale.ROOT, "%3.0f%%", m * 100);
        long passes = r.samples().stream().filter(ToolBench.Sample::selectionOk).count();
        // surface the first non-passing sample's detail to make a failure legible
        String detail = r.samples().stream()
                .filter(s -> !s.selectionOk() || !s.argsValid())
                .map(ToolBench.Sample::detail).findFirst().orElse("");
        return String.format(Locale.ROOT, "  %-22s %-14s sel %d/%d  valid %3.0f%%  match %s   %s",
                r.c().name, "[" + r.bucket() + "]", passes, r.samples().size(),
                r.argsValidRate() * 100, matchStr,
                ToolBench.truncate(detail, 60));
    }

    // ---- io ----

    private static List<ToolBench.BenchCase> loadCases() throws Exception {
        try (InputStream in = ToolBenchTest.class.getResourceAsStream("/bench/cases.json")) {
            if (in == null) return List.of();
            var type = new TypeToken<List<ToolBench.BenchCase>>() {}.getType();
            List<ToolBench.BenchCase> cases =
                    new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
            return cases == null ? List.of() : cases;
        }
    }

    private static void writeHistory(ToolBench.Config cfg, Report report) {
        try {
            Path root = repoRoot();
            if (root == null) return;
            Path dir = root.resolve("benchmark");
            Files.createDirectories(dir);
            Path csv = dir.resolve("history.csv");
            if (!Files.exists(csv)) {
                Files.writeString(csv,
                        "timestamp,provider,model,samples,cases,selection_pct,passk_pct,args_valid_pct,args_match_pct\n",
                        StandardCharsets.UTF_8);
            }
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String row = String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%.1f,%.1f,%.1f,%s%n",
                    stamp, cfg.providerName, cfg.model, cfg.samples, report.cases(),
                    report.selection() * 100, report.passk() * 100, report.argsValid() * 100,
                    report.argsMatch() == null ? "" : String.format(Locale.ROOT, "%.1f", report.argsMatch() * 100));
            Files.writeString(csv, row, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            System.out.println("[tool-bench] appended → " + csv);
        } catch (Exception ex) {
            System.out.println("[tool-bench] could not write history: " + ex);
        }
    }

    /** Walk up from the working dir to the gradle root (the dir with settings.gradle). */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.exists(p.resolve("settings.gradle")) || Files.exists(p.resolve("settings.gradle.kts"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }
}
