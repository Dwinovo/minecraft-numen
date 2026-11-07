# AGENTS.md

Instructions for coding agents working in this repository. Read `CONTRIBUTING.md` too; the asks there apply to you.

## Where things are

Numen is a Minecraft mod: an AI companion that is a server-side fake player (`ServerPlayer`), driven by an LLM agent loop running on the owner's client.

- `ai/` — LLM transport, pure JVM.
- `ui/` — widget library, pure JVM, no Minecraft classes.
- `numen-api/` — Numen API: agent loop, inbox, task slot, tool transport, the companion body, the permission layer (`com.dwinovo.numen.api.permission`).
- `numen/` — content: tools, tasks, pathing, instincts, bundled skills. Loader entry points in `numen/fabric`, `numen/neoforge` or `numen/forge`.
- `plugins/` — integrations with other mods, loaded only when the target mod is present.
- Tests are source sets of the project they test (`test`, `gametest`, `e2e`), run per platform from the platform project (e.g. `:numen:neoforge:e2eTest`); the evaluation (`bench/`) is a separate module.
- `docs/architecture-mind-model.md` — the architecture rules. Read it before changing Numen API behavior.

## How to write the change

- Change only what the task asks for. Do not refactor, rename, reformat or move code you were not asked to touch. If you notice an unrelated problem, mention it in your summary instead of fixing it.
- Work only on the branch you were asked to change. Do not port to other version branches unless asked.
- Fix the root cause. Do not add fallbacks, try/catch blocks that swallow an error and take a second path, or workarounds around a symptom.
- Before writing a check, a parser, a message or a helper, search for an existing one and reuse it. Never leave an old implementation beside a new one.
- Do not add abstractions or options for requirements nobody stated.
- Machinery belongs in `numen-api/`, content in `numen/`. `numen-api/` must not reference `numen/` types. Common code must not branch on the mod loader.
- Actions that change the world or hurt an entity are decided only by the permission layer. Do not add "may I do this" checks inside tools or tasks.
- Whatever the companion's body does must be reported to the model. No silent teleports or inventory edits.
- Comments say what the code does now and why, never how it used to be. Match the comment language of the surrounding file.
- Do not create new documentation or summary files unless asked. Do not change `version` in `gradle.properties`.

## Verify

Commands are in `CONTRIBUTING.md`; run them with `--no-daemon`, one at a time. The cadence is the one in "Verification cadence" there:

- While working, run only the relevant GameTest batch (`-Pgametest=<suite>`, narrowed with `-PgametestOnly`) and the unit tests that cover your change. No clean, no datagen (unless language files changed).
- Before handing in, run the default GameTest suite once, NeoForge and Fabric in parallel. It ends with `All N required tests passed`; log lines like `[numen-task] ... FAILED(...)` are scenarios the tests set up, not failures.
- The slow batch (`numen_slow`, `numen_pathing_slow`) and `pathingBench` run only when you changed pathing execution or planning. A GameTest case with a time limit over 600 ticks belongs to the slow batch.
- Do not retry a failing case and do not add retries (`attempts`, `requiredSuccesses`). Find the cause; a result that varies with load or machine speed is a defect in the code under test or in the case. To prove a case stable, spread copies of it inside one server start (`-PgametestCopies=N`), never a loop of restarts.
- Report failing tests as they are. Never weaken, skip or delete a test to make it pass.

## Commits

One-line subject `type(scope): short description`, one concern per commit. Put the reasoning in code comments, not in the commit body.
