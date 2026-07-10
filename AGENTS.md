# Kestra Faktory Plugin

## What

- Provides plugin components under `io.kestra.plugin.faktory`.
- Includes `AbstractFaktoryConnection` (shared connection base) and `Push` (job submission task).

## Why

- Teams running Faktory/Sidekiq-backed apps have no first-class way to enqueue a background job from an orchestrator; they shell out to the `faktory` CLI, hand-roll a raw TCP client, or keep scheduling logic embedded in the Rails app.
- `Push` lets a Kestra flow submit jobs to an existing Faktory server so a team can migrate scheduling off Sidekiq/cron and onto Kestra incrementally, one job type at a time, without touching existing Faktory workers.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `faktory`

The plugin implements the Faktory Work Protocol (FWP) directly over `java.net.Socket` / `javax.net.ssl.SSLSocket` - Faktory is a line-based TCP protocol, not HTTP, so no third-party client or HTTP stack is used.

### Key Plugin Classes

- `io.kestra.plugin.faktory.AbstractFaktoryConnection` - abstract base owning the socket lifecycle (TCP/TLS connect, `HI`/`HELLO` handshake, password auth via iterated SHA256, clean close). Shared by every task.
- `io.kestra.plugin.faktory.Push` - enqueues a job (`PUSH <json>`), verifies the `+OK` reply, and returns the job's `jid`. Fire-and-forget: no long-running remote resource, so no `kill()` override.

### Project Structure

```
plugin-faktory/
├── src/main/java/io/kestra/plugin/faktory/
│   ├── AbstractFaktoryConnection.java
│   ├── Push.java
│   └── package-info.java
├── src/test/java/io/kestra/plugin/faktory/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Consumer-side job fetching (`FETCH`/`ACK`/`FAIL` via a `Trigger`) is out of scope for `Push` and tracked separately.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
