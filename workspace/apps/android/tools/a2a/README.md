# A2A tools

## Client mode in the demo app

To hear and see the A2A result in the demo app itself, open the sample console's `A2A` tab (or send
`--es a2a_client on` with the `DEBUG_SEND_TEXT` broadcast). The sample then acts as its own client through the
packaged direct adapter: input box, overlay, adb and ASR text go over HTTP/SSE to `127.0.0.1:8766/a2a`, and only
`channel=speech` is played. A request carrying `metadata.debugMirror=true` is mirrored read-only (shown and spoken;
`/answer` and `/cancel` stay with its sender); real A2A clients never send the marker and never change the app's UI.

## Reference integration runners

These runners exercise the real `:bothy-a2a-client-direct` JVM test artifact
against independently running HTTP servers. They inject the integration test
source with a Gradle init script, so no Android Gradle module is modified.

## Official Python HelloWorld

```bash
python workspace/apps/android/tools/a2a/run_helloworld_smoke.py
```

The runner clones the pinned upstream `a2aproject/a2a-samples` commit into a
temporary directory, installs the sample's locked Python requirements in a
temporary virtual environment, starts its official Python HelloWorld server,
then verifies Agent Card discovery plus synchronous and streaming messaging.

## Deterministic fault server

```bash
python workspace/apps/android/tools/a2a/run_fault_smoke.py
```

The fault runner starts the project-owned deterministic server and proves the
cancel-race outcome: a blocked message call is cancelled locally, the server
then attempts a late business response, and the cancelled client accepts no
result afterward. Server-side disconnect/write observations remain diagnostic.

Both commands print exactly one final JSON result object. A failed assertion,
server startup failure, or Gradle failure produces `"status":"failed"` and a
non-zero process exit code. Use `--result-file PATH` to persist the same JSON
for CI artifact collection.

Run both required integrations with:

```bash
python workspace/apps/android/tools/a2a/run_reference_integrations.py
```

## Phase 0 Android gate

After building all three A2A release AARs and the minified sample APK, run:

```bash
python workspace/apps/android/tools/a2a/phase0_audit.py
```

The audit fails closed on missing Release/R8 artifacts, facade ABI leakage,
`bothy-runtime` A2A coupling, missing official dependency/license evidence, or
an APK compressed-size increase above the approved 1.5 MiB budget. The default
direct-only baseline is the measured `6,280,671` byte Phase 0 APK; override it
only when a new direct-only Release+R8 baseline has been recorded in trace.
