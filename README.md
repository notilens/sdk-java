# NotiLens Java/Kotlin SDK

Java SDK and CLI for [NotiLens](https://notilens.com) — task lifecycle notifications for AI agents, Spring Boot jobs, and any JVM application. Works natively in both Java and Kotlin.

## Installation

**Maven:**
```xml
<dependency>
  <groupId>com.notilens</groupId>
  <artifactId>notilens</artifactId>
  <version>0.4.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**
```kotlin
implementation("com.notilens:notilens:0.4.0")
```

**Gradle (Groovy):**
```groovy
implementation 'com.notilens:notilens:0.4.0'
```

## Quick Start

**Java:**
```java
import com.notilens.NotiLens;

NotiLens nl = NotiLens.init("my-agent");
NotiLens.Run run = nl.task("report");
run.start();
run.progress("Processing...");
run.complete("Done!");
```

**Kotlin:**
```kotlin
import com.notilens.NotiLens

val nl = NotiLens.init("my-agent")
val run = nl.task("report")
run.start()
run.progress("Processing...")
run.complete("Done!")
```

## Credentials

Resolved in order:
1. `token` / `secret` args to `init()`
2. `NOTILENS_TOKEN` / `NOTILENS_SECRET` env vars
3. Saved CLI config (`notilens init --agent ...`)

```java
NotiLens nl = NotiLens.init("my-agent", "your-token", "your-secret");

// With state_ttl (optional — orphaned state TTL in seconds, default: 86400)
NotiLens nl = NotiLens.init("my-agent", "your-token", "your-secret", 86400);
```

## SDK Reference

### Task Lifecycle

`nl.task(label)` creates a `Run` — an isolated execution context. Multiple concurrent runs of the same label never conflict.

```java
NotiLens.Run run = nl.task("email");  // create a run for the "email" task
run.queue();                          // optional — pre-start signal

run.start();                          // begin the run

run.progress("Fetching data...");
run.loop("Processing item 42");
run.retry();
run.pause("Waiting for rate limit");
run.resume("Resuming work");
run.wait("Waiting for tool response");
run.stop();
run.error("Quota exceeded");          // non-fatal, run continues

// Terminal — pick one
run.complete("All done!");
run.fail("Unrecoverable error");
run.timeout("Timed out after 5m");
run.cancel("Cancelled by user");
run.terminate("Force-killed");
```

### Output & Input Events

```java
run.outputGenerated("Report ready");
run.outputFailed("Rendering failed");

run.inputRequired("Approve deployment?");
run.inputApproved("Approved");
run.inputRejected("Rejected");
```

### Metrics

Numeric values accumulate; strings are replaced.

```java
run.metric("tokens", 512);
run.metric("tokens", 128);      // now 640
run.metric("cost", 0.003);

run.resetMetrics("tokens");     // reset one key
run.resetMetrics();             // reset all
```

Agent-level metrics (included in every send):

```java
nl.metric("total_cost", 0.01);
nl.resetMetrics();
```

### Automatic Timing

NotiLens automatically tracks task timing. These fields are included in every notification's `meta` payload when non-zero:

| Field | Description |
|-------|-------------|
| `total_duration_ms` | Wall-clock time since `start` |
| `queue_ms` | Time between `queue` and `start` |
| `pause_ms` | Cumulative time spent paused |
| `wait_ms` | Cumulative time spent waiting |
| `active_ms` | Active time (`total − pause − wait`) |

### Generic Events

```java
nl.track("custom.event", "Something happened");
nl.track("custom.event", "With meta", Map.of("key", "value"));

run.track("custom.event", "Run-level event");
run.track("custom.event", "With meta", Map.of("key", "value"));
```

### Full Example

**Java:**
```java
import com.notilens.NotiLens;

NotiLens nl = NotiLens.init("summarizer", "TOKEN", "SECRET");
NotiLens.Run run = nl.task("report");
run.start();

try {
    String result = llm.complete(prompt);
    run.metric("tokens", result.usage().totalTokens());
    run.outputGenerated("Summary ready");
    run.complete("All done!");
} catch (Exception e) {
    run.fail(e.getMessage());
}
```

**Kotlin:**
```kotlin
import com.notilens.NotiLens

val nl = NotiLens.init("summarizer", "TOKEN", "SECRET")
val run = nl.task("report")
run.start()

runCatching {
    val result = llm.complete(prompt)
    run.metric("tokens", result.usage.totalTokens)
    run.outputGenerated("Summary ready")
    run.complete("All done!")
}.onFailure { e ->
    run.fail(e.message ?: "Unknown error")
}
```

## CLI

### Install

Download `notilens-cli.jar` from [GitHub Releases](https://github.com/notilens/sdk-java/releases) and add a wrapper:

```bash
echo '#!/bin/sh\nexec java -jar /usr/local/lib/notilens-cli.jar "$@"' > /usr/local/bin/notilens
chmod +x /usr/local/bin/notilens
```

### Configure

```bash
notilens init --agent my-agent --token TOKEN --secret SECRET
notilens agents
notilens remove-agent my-agent
```

### Commands

`--task` is a semantic label (e.g. `email`, `report`). Each `task.start` creates an isolated run internally — concurrent executions of the same label never conflict.

```bash
notilens queue                      --agent my-agent --task email
notilens start                      --agent my-agent --task email
notilens progress  "Fetching data"  --agent my-agent --task email
notilens loop      "Item 5/100"     --agent my-agent --task email
notilens retry                      --agent my-agent --task email
notilens pause     "Rate limited"   --agent my-agent --task email
notilens resume    "Resuming"       --agent my-agent --task email
notilens wait      "Awaiting tool"  --agent my-agent --task email
notilens stop                       --agent my-agent --task email
notilens error     "Quota hit"      --agent my-agent --task email
notilens fail      "Fatal error"    --agent my-agent --task email
notilens timeout   "Timed out"      --agent my-agent --task email
notilens cancel    "Cancelled"      --agent my-agent --task email
notilens terminate "Force stop"     --agent my-agent --task email
notilens complete  "Done!"          --agent my-agent --task email

notilens output.generate "Report ready"  --agent my-agent --task email
notilens output.fail     "Render failed" --agent my-agent --task email
notilens input.required  "Approve?"      --agent my-agent --task email
notilens input.approve   "Approved"      --agent my-agent --task email
notilens input.reject    "Rejected"      --agent my-agent --task email

notilens metric       tokens=512 cost=0.003 --agent my-agent --task email
notilens metric.reset tokens               --agent my-agent --task email
notilens metric.reset                      --agent my-agent --task email

notilens track my.event "Something happened" --agent my-agent
notilens version
```

`task.start` prints the internal `run_id` to stdout.

## Requirements

- Java 17+
- One dependency: `jackson-databind`
