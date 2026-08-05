# Numen Bridge for macOS

## Goal

Ship a macOS-specific companion application that gives Numen reliable microphone
permission and hides provider-specific speech protocols behind a stable local API.
The existing Minecraft mod remains the user-facing client and retains its current
Windows/Linux and direct-provider paths.

The first usable build targets Apple Silicon and the official Minecraft Launcher.
The local protocol is intentionally provider-neutral so another mod can use the
same bridge without depending on Numen internals.

## Why a Separate Process

Minecraft is launched as a Java child process. On this machine the Java executable
has the audio-input entitlement, but its microphone usage description is not bound
to that executable's signed metadata. macOS has created no TCC microphone record
for Minecraft or Java, and Java Sound does not trigger the permission prompt.

A mod cannot repair the host process's signed identity. Modifying or re-signing the
official launcher/runtime would be fragile and would be overwritten by launcher
updates. A small native app can own the microphone permission legitimately and
communicate with the mod over loopback.

## Selected Architecture

### Numen Bridge.app

Add an Apple Silicon Swift application under `bridge/macos`. It will:

- declare `NSMicrophoneUsageDescription` and the audio-input entitlement;
- request microphone access explicitly before capture;
- capture with AVFoundation and convert input to signed 16-bit, 16 kHz, mono PCM;
- host a loopback-only service on `127.0.0.1`;
- adapt DashScope STT and TTS to a provider-neutral local protocol;
- return decoded transcripts and standard PCM WAV, never provider event objects;
- keep provider credentials in the macOS Keychain;
- expose current permission, capture, provider, and error state in a small menu-bar UI.

The development build may be locally signed for immediate testing. A distributable
download requires Developer ID signing and notarization; source and an unsigned CI
artifact can still be reviewed and built without those credentials.

### Local API

The default endpoint is `http://127.0.0.1:38471`. The listener must never bind to a
LAN or wildcard address. A random bearer token is generated on first launch and
stored in a user-readable discovery file with owner-only permissions.

Endpoints:

- `GET /v1/health` returns bridge version, API version, platform, permission state,
  and configured provider readiness. It contains no credentials.
- `WS /v1/audio/capture` accepts `capture.start` and `capture.stop`. It emits
  `capture.started`, `transcript.delta`, `transcript.done`, and structured `error`
  events. The bridge, not Minecraft, opens the microphone.
- `POST /v1/audio/transcriptions` accepts a standard WAV upload for clients that
  already capture audio. This keeps compatibility with OpenAI-style consumers.
- `POST /v1/audio/speech` accepts OpenAI-compatible `model`, `voice`, `input`, and
  `response_format` fields and returns `audio/wav`.

The WebSocket capture extension is necessary because the standard transcription
endpoint assumes the caller already has microphone samples, which is exactly what
fails inside Minecraft on macOS.

### Provider Adapters

The initial adapters are:

- DashScope realtime STT for `qwen-audio-3.0-realtime-flash`;
- DashScope realtime TTS for `qwen3-tts-flash-realtime` and compatible snapshots;
- generic OpenAI-compatible file transcription and speech endpoints.

DashScope event names, completion conditions, sample formats, and regional URLs
will be encoded in the Bridge adapter and verified against captured protocol
fixtures. Ollama may be used for LLM routing, but it is not treated as a speech
provider because it does not itself solve microphone capture or expose equivalent
STT/TTS APIs.

Additional models are configuration entries that select an adapter, endpoint,
model, and voice. They do not require Minecraft-side changes.

### Minecraft Mod Changes

Add a `bridge` STT/TTS backend to the original Numen mod:

- on macOS, prefer Bridge when it is installed and healthy;
- keep all current direct providers available for existing users;
- send start/stop over the capture WebSocket and put transcript events into the
  existing `VoiceInputController` callbacks;
- call the local speech endpoint and pass the returned WAV through the existing
  `WavCodec` and Minecraft sound engine;
- show distinct messages for Bridge missing, microphone permission denied,
  provider authentication failure, empty provider audio, and playback failure;
- never report recording active until `capture.started` arrives.

No native library is loaded into Minecraft, and the mod does not modify the
launcher, Java runtime, TCC database, or system security settings.

## Audio Output Contract

Bridge normalizes TTS output to RIFF/WAVE, signed 16-bit little-endian mono PCM at
the sample rate declared in the header. Before returning success it rejects empty
audio, malformed base64, odd PCM byte counts, and sample rates unsupported by the
Minecraft decoder.

Numen validates the WAV again, logs synthesis duration and byte count, and plays it
through the existing preview or entity-attached sound. Playback tests must verify
that the Fabric stream hook supplies the generated PCM rather than the placeholder
sound resource.

## Security and Privacy

- Provider keys remain in Keychain and are sent only to the configured provider.
- The local bearer token is never logged and all request logs redact authorization.
- Only one microphone capture may run at a time.
- Capture stops on client disconnect, explicit stop, permission failure, or the
  existing 60-second safety limit.
- No recording is persisted unless a debug option is explicitly enabled.
- The health endpoint and menu UI report metadata, never keys or raw audio.

## Failure Handling

Every boundary returns a visible, actionable error:

- macOS permission: not determined, denied, restricted, or granted;
- capture: device unavailable, conversion failed, or no samples received;
- provider: connection, authentication, protocol, timeout, or empty result;
- playback: invalid WAV, missing entity, or sound engine rejection.

Network sessions have connect and completion timeouts. Unexpected WebSocket close
is an error unless a complete transcript/audio response was already received.
Partial TTS bytes are not treated as success after an abnormal close.

## Testing

Development follows red-green TDD at each boundary:

- Swift unit tests for permission-state mapping, PCM conversion, capture lifecycle,
  loopback authentication, and provider fixture parsing;
- Java unit tests for Bridge discovery, capture event mapping, speech requests,
  error propagation, WAV rejection, and platform selection;
- integration tests with a fake local Bridge and recorded DashScope event fixtures;
- fresh Gradle common tests plus Fabric and NeoForge builds;
- a locally signed Bridge launch test confirming macOS displays the microphone
  permission prompt and TCC records the app;
- an end-to-end official-launcher test: speak in Minecraft, receive non-empty text,
  generate a reply, receive non-empty TTS WAV, and audibly play it in game;
- inspect logs to prove the selected backend, capture byte count, provider result,
  decoded duration, and playback start without exposing credentials.

## Acceptance Criteria

The work is complete only when all of the following are observed on this Mac:

1. First capture causes the normal macOS microphone permission prompt for Numen
   Bridge, and granting it creates a TCC record.
2. Minecraft never opens the microphone directly when Bridge mode is active.
3. Spoken Mandarin produces a non-empty final transcript in the Numen input flow.
4. DashScope TTS returns a non-empty valid WAV and the reply is audible through the
   Minecraft sound engine.
5. Permission denial, invalid provider key, Bridge absence, and empty audio each
   produce different visible errors rather than silent failure.
6. Existing non-macOS and direct-provider tests continue to pass.
7. The Apple Silicon app and matching Fabric mod JAR can be built reproducibly from
   the repository, with signing/notarization status documented separately.

## Deferred Scope

- Intel macOS binary and universal packaging;
- modifying CC Switch itself;
- virtual microphone/audio-driver installation;
- LAN or remote Bridge access;
- a full LLM routing UI duplicating CC Switch;
- automatic Developer ID signing without repository secrets.
