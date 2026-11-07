# Changelog

All notable changes to the `dev.jamjet:jamjet-runtime-*` and `jamjet-agent*` artifacts.

## 0.4.1 (unreleased)

### Fixed

- **Worker:** the Java tool worker echoes `lease_fence` on `/work-items/{id}/fail` and
  `idempotency_key` on `/work-items/{id}/complete` (#14). Without the fence a failed Java tool
  left its workflow stuck in `scheduled`. Without the key a replay fired the tool again. 0.4.0
  on Maven Central does not have this fix.
- **Core:** event logs written by the current Rust runtime now load. Added the kinds Java was
  missing: `node_parked`, `agent_step_admitted`, `approval_expired`, `approval_escalated`,
  `audit_seal_failed`, `run_stalled` and `segment_boundary`. Before, any of them failed the
  whole log.
- **Core:** an event `type` this build does not know reads as `EventKind.Unknown` instead of
  failing the log, as in the Rust runtime. Folds treat it as possibly a denial: the scheduler
  dispatches nothing for that run and records `RunStalled` once.
- **Core:** fields the Rust runtime added are read instead of dropped:
  `WorkflowStarted.initiatedBy`, `NodeCompleted.idempotencyKey`, and
  `ApprovalReceived.principal`, `approvedDigest`, `requestSequence`. The old record
  constructors remain.

### Changed

- **Server:** `jamjet-runtime-server` binds `127.0.0.1` by default. Before, it listened on
  every interface. Set `JAMJET_BIND` (the same variable as the Rust runtime) to change it. A
  non-loopback bind without authentication logs a warning.
- **Server:** optional authentication. Set `JAMJET_API_TOKEN` to require
  `Authorization: Bearer <token>` on every route except `GET /health`. You can also pass your
  own `AuthHook` to `JamjetServer.start` or `createApp`. A hook that throws refuses the request.
  With no token and no hook, the server stays open as before.

## 0.4.0

- The `dev.jamjet.agent.Agent` builder, the `@Tool` annotation, the durable `java_tool`
  worker and `jamjet-agent-spring-boot-starter` (#9, #10, #11).
