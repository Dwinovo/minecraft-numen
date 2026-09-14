# AGENTS.md

Instructions for coding agents working in this repository.

**Before changing anything, read `CONTRIBUTING.md` and follow every rule in it.** Its quality bar, commit format and verification commands are binding for agents. This file only adds what an agent needs on top.

## Where things are

Numen is a Minecraft mod: an AI companion that is a server-side fake player (`ServerPlayer`), driven by an LLM agent loop running on the owner's client.

- `ai/` — LLM transport, pure JVM.
- `ui/` — widget library, pure JVM, no Minecraft classes.
- `api/` — the engine: agent loop, inbox, task slot, tool transport, the companion body, the permission layer (`com.dwinovo.numen.permission`).
- `core/` — content: tools, tasks, pathing, instincts, bundled skills. Loader entry points in `core/fabric`, `core/neoforge` or `core/forge`.
- `plugins/` — integrations with other mods, loaded only when the target mod is present.
- `docs/architecture-mind-model.md` — the architecture rules.

## Agent-specific rules

- Work only on the branch you were asked to change. Do not port changes to other version branches unless asked.
- Change only what the task asks for. If you notice an unrelated problem, mention it in your summary instead of fixing it.
- Do not create new documentation or summary files unless asked.
- Run the Gradle commands from `CONTRIBUTING.md` with `--no-daemon`, one at a time.
- The GameTest run ends with `All N required tests passed`. Log lines like `[numen-task] ... FAILED(...)` are scenarios the tests set up, not failures.
- Report failing tests as they are. Never weaken, skip or delete a test to make it pass.
