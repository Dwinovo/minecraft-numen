# AGENTS.md

Instructions for coding agents working in this repository. Read `CONTRIBUTING.md` too; the asks there apply to you.

## Where things are

Numen is a Minecraft mod: an AI companion that is a server-side fake player (`ServerPlayer`), driven by an LLM agent loop running on the owner's client.

- `ai/` — LLM transport, pure JVM.
- `ui/` — widget library, pure JVM, no Minecraft classes.
- `api/` — the engine: agent loop, inbox, task slot, tool transport, the companion body, the permission layer (`com.dwinovo.numen.permission`).
- `core/` — content: tools, tasks, pathing, instincts, bundled skills. Loader entry points in `core/fabric`, `core/neoforge` or `core/forge`.
- `plugins/` — integrations with other mods, loaded only when the target mod is present.
- `docs/architecture-mind-model.md` — the architecture rules. Read it before changing engine behavior.

## How to write the change

- Change only what the task asks for. Do not refactor, rename, reformat or move code you were not asked to touch. If you notice an unrelated problem, mention it in your summary instead of fixing it.
- Work only on the branch you were asked to change. Do not port to other version branches unless asked.
- Fix the root cause. Do not add fallbacks, try/catch blocks that swallow an error and take a second path, or workarounds around a symptom.
- Before writing a check, a parser, a message or a helper, search for an existing one and reuse it. Never leave an old implementation beside a new one.
- Do not add abstractions or options for requirements nobody stated.
- Machinery belongs in `api/`, content in `core/`. `api/` must not reference `core/` types. Common code must not branch on the mod loader.
- Actions that change the world or hurt an entity are decided only by the permission layer. Do not add "may I do this" checks inside tools or tasks.
- Whatever the companion's body does must be reported to the model. No silent teleports or inventory edits.
- Comments say what the code does now and why, never how it used to be. Match the comment language of the surrounding file.
- Do not create new documentation or summary files unless asked. Do not change `version` in `gradle.properties`.

## Verify

Run the commands in `CONTRIBUTING.md` with `--no-daemon`, one at a time. The GameTest run ends with `All N required tests passed`; log lines like `[numen-task] ... FAILED(...)` are scenarios the tests set up, not failures. Report failing tests as they are. Never weaken, skip or delete a test to make it pass.

## Commits

One-line subject `type(scope): short description`, one concern per commit. Put the reasoning in code comments, not in the commit body.
