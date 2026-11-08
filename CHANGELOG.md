# Changelog

All notable changes to the `dev.jamjet:jamjet-runtime-*` and `jamjet-agent*` artifacts.

## 0.5.0 (unreleased)

0.5.0 rather than 0.4.1: the server's default bind changes, `@Checkpoint` ids change, and
some event records gain components.

### Fixed

- **Worker:** the Java tool worker echoes `lease_fence` on `/work-items/{id}/fail` and
  `idempotency_key` on `/work-items/{id}/complete` (#14). Without the fence, a failed Java tool
  left its workflow stuck in `scheduled`. Without the key, a replay fired the tool again. 0.4.0
  on Maven Central does not have this fix.
- **Instrument (#16):** `@Checkpoint` under the `-javaagent` threw `IllegalAccessError` for
  every class outside `dev.jamjet.runtime.instrument`. It now works from any package.
  - A replayed call is matched by checkpoint name and position. If its arguments differ
    from the recorded call, or the position has no record while later ones do, it throws
    `CheckpointNondeterminismException`. That is an `Error`, so a `catch (Exception)` retry
    loop does not swallow it, and the context then refuses every later checkpoint call. It
    neither returns another call's result nor runs the side effect again.
  - A call that threw an `Exception` is recorded and replays as the same failure. A failure
    restored from storage is rebuilt as exactly its original class, or else thrown as
    `CheckpointReplayException`, never as a superclass.
  - An `Error` or an interrupt is recorded as an indeterminate marker. Replay throws it again
    where the first run went past it, and runs the call again where the run stopped.
  - Arguments are fingerprinted without calling their methods: records and value objects by
    their fields, JDK types from a fixed allow-list. Anything else (a client, a session, a
    lambda, another JDK type) is refused with `CheckpointKeyException`.
- **Core (#17):** event logs written by the current Rust runtime now load. The kinds Java was
  missing are added: `node_parked`, `agent_step_admitted`, `approval_expired`,
  `approval_escalated`, `audit_seal_failed`, `run_stalled` and `segment_boundary`. Before, any
  one of them failed the whole log.
- **Core (#17):** an event `type` this build does not know reads as `EventKind.Unknown`
  instead of failing the log, as in the Rust runtime; `Unknown` keeps the original type and
  fields for inspection. So does an unknown `queue_type` or approval `decision` inside a known
  kind (`QueueType.UNKNOWN`, `ApprovalDecision.UNKNOWN`). For a run whose log holds either:
  - the scheduler dispatches nothing and records `RunStalled` once;
  - workers of this build do not run its queued items. They return each one to pending
    without charging an attempt, append nothing, and skip that execution from then on, so a
    build that reads the whole log (or a Rust worker sharing the queue) can still run them;
  - an expired lease of such a run appends no `NodeFailed` or `RetryScheduled`;
  - `StateMaterializer` reports `indeterminate = true`.
- **Core (#17):** fields the Rust runtime added are now read instead of dropped:
  `WorkflowStarted.initiatedBy`, `NodeCompleted.idempotencyKey`, and
  `ApprovalReceived.principal`, `approvedDigest` and `requestSequence`.

### Added

- **Agent (#18):** approval deadlines on `PolicySetIr` (`approvalTimeoutSecs`,
  `escalateAfterSecs`), validated as in the Rust runtime.
  - `JamjetEngineClient` gains `listApprovals`, `approve` (echoes the reviewed
    `action_digest`), `reject`, `sendExternalEvent`, `requiresAuthentication`,
    `auditLogText` and `didDocumentText`.
  - New `RuntimeAuditVerifier`: verifies the runtime's Ed25519-signed audit log offline,
    tested against the Rust/Python vector.
- **Agent starter (#18):** an opt-in approval endpoint
  (`jamjet.agent.approvals.endpoint.enabled`).
  - It serves only authenticated users, and acts at the engine with each user's configured
    token (`approvers[n].user` / `approvers[n].token`). It never forwards the caller's
    bearer token.
  - A decision needs the `X-JamJet-Approval` header.
  - It refuses to relay decisions to an engine that runs without authentication.

### Changed

- **Server (#17):** `jamjet-runtime-server` binds `127.0.0.1` by default; before, it listened
  on every interface. Set `JAMJET_BIND` (the same variable as the Rust runtime) to change it.
  An app from `createApp` started by hand binds the configured host too. A non-loopback bind
  without authentication logs a warning.
- **Server (#17):** optional authentication. `JAMJET_API_TOKEN` requires
  `Authorization: Bearer <token>` on every route except `GET /health`; or pass an `AuthHook`.
  A hook that throws refuses the request. With no token and no hook the server stays open,
  as before.
- **Source compatibility:** `WorkflowStarted`, `NodeCompleted`, `ApprovalReceived`,
  `StateMaterializer.MaterializedState` and `ServerConfig` gained components. Their old
  constructors remain. Code that deconstructs them with record patterns must add the new
  components.

### Migration

- `@Checkpoint` ids are now `name#n@fingerprint`. Records saved by 0.4.0 under the old ids
  (`Class.method` or the bare `@Checkpoint` value) are not read as `@Checkpoint` records. In
  0.4.0 the agent path failed for every class outside the instrument package, so such records
  should not exist. Ids passed to `DurabilityContext.replayOrExecute` are unchanged.
- To persist a run, save each `CheckpointRecord`'s `id`, `failed` and `indeterminate` flags,
  and either `value` or `failureType` and `failureMessage`. Load them with
  `DurabilityContext.restore`.

## 0.4.0

- The `dev.jamjet.agent.Agent` builder, the `@Tool` annotation, the durable `java_tool`
  worker and `jamjet-agent-spring-boot-starter` (#9, #10, #11).
