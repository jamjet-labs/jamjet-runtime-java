# JamJet Cloud SDK Demo (Java)

A Java app using the JamJet Cloud SDK end to end: it emits **spans** (observability:
model, tokens, cost) to JamJet Cloud and **AgentBoundary Action Receipts** (audit) for
tool calls. Two flavours:

- **Spring Boot** (`CloudDemoApplication`): `jamjet-cloud-spring-boot-starter` auto-wires
  span observability; an `ActionReceiptAdvisor` + a `FileActionReceiptEmitter` add receipts
  for `@Tool` calls. Spans are zero-config; receipts are one advisor + emitter bean.
- **Plain Java** (`PlainJavaCloudDemo`): the raw `JamjetCloud` / `Span` / `ActionReceipt` API.

## Build & test (hermetic, no secrets)

From the repo root:
```bash
mvn -pl examples/jamjet-cloud-sdk-demo -am test
```
Tests stub JamJet Cloud with WireMock and use a deterministic model, so they need no API keys.

## Live mode (see it in the dashboard)

```bash
export JJ_API_KEY=...        # your JamJet Cloud key
export OPENAI_API_KEY=...    # for the Spring demo's real model

# Spring demo
mvn -pl examples/jamjet-cloud-sdk-demo spring-boot:run

# Plain-Java demo
mvn -pl examples/jamjet-cloud-sdk-demo -am package -DskipTests
java -cp examples/jamjet-cloud-sdk-demo/target/classes:... dev.jamjet.example.cloud.PlainJavaCloudDemo
```
Then: the **span** appears in the JamJet Cloud dashboard for project `cloud-sdk-demo`, and
the **receipt** is written to `~/.jamjet/audit/cloud-sdk-demo.jsonl`.

## What is and isn't shown

Spans are the cloud-visible artifact today. Receipts are emitted locally (file/log) -- the
SDK's cloud-posting receipt emitter is planned, not yet shipped. The Java Cloud SDK does
**not** yet include local policy enforcement, PII redaction, an approval UX, or cache
injection; those exist in the TypeScript/Python SDKs and the `jamjet-policy` tooling.

## Build note

This example is a reactor module so it builds against the in-repo SDK. Once
`jamjet-cloud-sdk` is published to Maven Central it can be split out as a standalone project.
