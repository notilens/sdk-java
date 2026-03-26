# NotiLens Java/Kotlin SDK

Java SDK and CLI for [NotiLens](https://notilens.com) — task lifecycle notifications for AI agents, Spring Boot jobs, and any JVM application. Works natively in both Java and Kotlin.

## Installation

**Maven:**
```xml
<dependency>
  <groupId>com.notilens</groupId>
  <artifactId>notilens</artifactId>
  <version>0.3.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**
```kotlin
implementation("com.notilens:notilens:0.3.0")
```

**Gradle (Groovy):**
```groovy
implementation 'com.notilens:notilens:0.3.0'
```

## Quick Start

**Java:**
```java
import com.notilens.NotiLens;

NotiLens nl = NotiLens.init("my-agent");
String taskId = nl.taskStart();
nl.taskProgress("Processing...", taskId);
nl.taskComplete("Done!", taskId);
```

**Kotlin:**
```kotlin
import com.notilens.NotiLens

val nl = NotiLens.init("my-agent")
val taskId = nl.taskStart()
nl.taskProgress("Processing...", taskId)
nl.taskComplete("Done!", taskId)
```

## Credentials

Resolved in order:
1. `token` / `secret` args to `init()`
2. `NOTILENS_TOKEN` / `NOTILENS_SECRET` env vars
3. Saved CLI config (`notilens init --agent ...`)

```java
NotiLens nl = NotiLens.init("my-agent", "your-token", "your-secret");
```

## SDK Reference

### Task Lifecycle

```java
String taskId = nl.taskStart();                         // auto-generated ID
String taskId = nl.taskStart("my-task-123");            // custom ID

nl.taskProgress("Fetching data...", taskId);
nl.taskLoop("Processing item 42", taskId);
nl.taskRetry(taskId);
nl.taskStop(taskId);
nl.taskError("Quota exceeded", taskId);                 // non-fatal
nl.taskComplete("All done!", taskId);                   // terminal
nl.taskFail("Unrecoverable error", taskId);             // terminal
nl.taskTimeout("Timed out after 5m", taskId);           // terminal
nl.taskCancel("Cancelled by user", taskId);             // terminal
nl.taskTerminate("Force-killed", taskId);               // terminal
```

### Output & Input Events

```java
nl.outputGenerated("Report ready", taskId);
nl.outputFailed("Rendering failed", taskId);

nl.inputRequired("Approve deployment?", taskId);
nl.inputApproved("Approved", taskId);
nl.inputRejected("Rejected", taskId);
```

### Metrics

```java
nl.metric("tokens", 512);
nl.metric("tokens", 128);       // now 640

nl.resetMetrics("tokens");      // reset one key
nl.resetMetrics();              // reset all
```

### Generic Events

```java
nl.emit("custom.event", "Something happened");
nl.emit("custom.event", "With meta", Map.of("key", "value"));
```

## CLI

### Install

Download `notilens-cli.jar` from [GitHub Releases](https://github.com/notilens/sdk-java/releases) and add a wrapper:

```bash
echo '#!/bin/sh\nexec java -jar /usr/local/lib/notilens-cli.jar "$@"' > /usr/local/bin/notilens
chmod +x /usr/local/bin/notilens
```

### Commands

```bash
notilens init --agent my-agent --token TOKEN --secret SECRET
notilens task.start    --agent my-agent --task job-123
notilens task.complete "Done!" --agent my-agent --task job-123
notilens metric        tokens=512 --agent my-agent --task job-123
notilens version
```

## Requirements

- Java 11+
- One dependency: `jackson-databind`
