# How to use the Faktory plugin

This plugin lets Kestra flows submit background jobs to a [Faktory](https://contribsys.com/faktory/) server, the language-agnostic job server most commonly paired with Sidekiq / Ruby on Rails workers. It talks to Faktory directly over the Faktory Work Protocol (FWP) - a line-based TCP protocol, not HTTP - so no third-party client library is bundled.

## Authentication

Every task connects using:

- `host` - hostname or IP address of the Faktory server, defaults to `localhost`.
- `port` - TCP port the server listens on, defaults to `7419`.
- `password` - required only if the server is password-protected. Store it as a [secret](https://kestra.io/docs/concepts/secret) and reference it with `{{ secret('FAKTORY_PASSWORD') }}` - never hardcode it in a flow.
- `tls` - set to `true` to connect over TLS (`tcp+tls://`), defaults to `false`. The connection verifies the server's certificate against the JVM's trust store, so a server presenting a self-signed certificate will fail to connect unless that certificate is added to the trust store.

On connect, the server greets the client with its protocol version and, if password-protected, a salt and iteration count. The plugin replies with the SHA256-iterated password hash automatically; the plain password itself is never sent over the wire or logged.

Connection properties are shared across tasks, so consider setting them once via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) if you have several Faktory tasks in the same namespace.

## Tasks

### Push

Enqueues (pushes) a single job to a Faktory queue and returns its `jid` (job id) as output, so downstream tasks can reference it. It is fire-and-forget: the task closes the connection right after the server acknowledges the job, it does not wait for a worker to process it.

Required:
- `jobType` - the Faktory `jobtype` identifying which worker handles the job.

Optional:
- `args` - JSON-native arguments passed to the worker, defaults to an empty list.
- `queue` - the queue to push to, defaults to `default`.
- `retries` - how many times Faktory retries the job before moving it to the dead queue (`-1` to `1000`), defaults to `25`. A negative value discards the job on failure instead of retrying it or moving it to the dead queue.
- `reserveFor` - how long a worker may hold the job before Faktory reschedules it, minimum `PT60S`, defaults to `PT30M`.
- `at` - schedules the job for a future instant instead of making it immediately available.
- `jobId` - a custom `jid`; if omitted, a random UUID is generated and returned as the output.
- `custom` - arbitrary metadata attached to the job payload, available to the worker at fetch time.

```yaml
id: faktory_push
namespace: company.team

inputs:
  - id: email
    type: STRING

tasks:
  - id: enqueue_welcome_email
    type: io.kestra.plugin.faktory.Push
    host: "{{ secret('FAKTORY_HOST') }}"
    password: "{{ secret('FAKTORY_PASSWORD') }}"
    jobType: SendWelcomeEmail
    queue: mailers
    args:
      - "{{ inputs.email }}"
```

## Triggers

None yet. Consuming jobs from a Faktory queue (`FETCH`/`ACK`/`FAIL`) is tracked as a separate, future addition.
