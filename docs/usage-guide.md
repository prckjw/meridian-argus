# MeridianArgus Usage Guide & Configuration Reference

English | [简体中文](usage-guide.zh-CN.md)

This document assumes you're already familiar with the project (see the [README](../README.md)) and are ready to integrate. It covers: installation, integration steps, per-provider adapter usage, every configurable parameter, the pricing config format, the Micrometer metrics catalog, and troubleshooting.

---

## Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Quick Start (Spring Boot)](#quick-start-spring-boot)
4. [Using MeridianArgus Without Spring Boot](#using-meridianargus-without-spring-boot)
5. [Provider Adapter Reference](#provider-adapter-reference)
6. [Configuration Reference](#configuration-reference)
7. [Pricing Config Format](#pricing-config-format)
8. [Micrometer Metrics Catalog](#micrometer-metrics-catalog)
9. [Grafana Dashboard](#grafana-dashboard)
10. [Writing a Custom Exporter](#writing-a-custom-exporter)
11. [FAQ / Troubleshooting](#faq--troubleshooting)

---

## Requirements

| Item | Requirement |
|---|---|
| JDK | 21+ (Spring Boot 4.1 requires Java 17 minimum; this project targets 21 across all modules) |
| Spring Boot | 4.1.0 (managed centrally via `spring-boot.version` in the parent POM) |
| Build tool | Maven (multi-module project) |
| Provider SDKs | Brought in by you, the consumer — see "Versioning strategy" below |

> None of the provider adapter code has been compiled against a live Maven Central in the environment this was built in (no network access to repo.maven.apache.org from the sandbox). Run `mvn -B verify` locally after integrating, and please open an issue if a method signature doesn't match the SDK version you're using.

## Installation

### Maven

Add the starter and whichever provider adapter(s) you need:

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-provider-openai</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Versioning strategy

Provider adapters (`OpenAICallWrapper`, `AzureOpenAICallWrapper`, etc.) are deliberately **not pinned to a specific SDK version at compile time** — they only take a `Supplier<R>` (the call itself) and a `Function<R, TokenUsage>` (usage extraction), where `R` is whatever response type your own SDK dependency returns. In practice this means:

- You can use whatever version of the OpenAI/Azure OpenAI/Bedrock/Vertex AI/Anthropic SDK your project already depends on — no need to align versions with MeridianArgus.
- MeridianArgus does **not** pull in any of these SDKs transitively — you declare them yourself.

## Quick Start (Spring Boot)

### Step 1: Enable auto-configuration

```java
@SpringBootApplication
@EnableLLMObservability
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@EnableLLMObservability` is an optional, explicit marker — auto-configuration actually kicks in via the `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` mechanism as soon as the starter is on the classpath. Adding the annotation just documents intent clearly on the entry point.

### Step 2: Wrap your SDK calls

Using OpenAI as an example:

```java
@RestController
public class ChatController {

    private final OpenAICallWrapper wrapper;
    private final OpenAIClient openAiClient; // your existing SDK client

    public ChatController(LLMObservabilityRegistry registry, OpenAIClient openAiClient) {
        // second constructor argument is the model name — it should match a
        // key in your pricing table (see Pricing Config Format below)
        this.wrapper = new OpenAICallWrapper(registry, "gpt-4o");
        this.openAiClient = openAiClient;
    }

    @PostMapping("/chat")
    public String chat(@RequestBody String prompt) {
        ChatCompletion response = wrapper.call(
            () -> openAiClient.chat().completions().create(buildRequest(prompt)),
            r -> TokenUsage.of(r.usage().promptTokens(), r.usage().completionTokens())
        );
        return response.choices().get(0).message().content();
    }
}
```

`LLMObservabilityRegistry` is auto-registered as a singleton bean by the starter — inject it directly. `wrapper.call(...)`:

1. Records the start time
2. Executes `sdkCall.get()` — the actual SDK call
3. Whether it succeeds or throws a runtime exception, publishes an `LLMCallRecord` to the registry
4. On success, extracts token usage via `usageExtractor`; if extraction itself fails, that failure is swallowed (recorded as zero usage) and does not affect your business return value
5. Returns the SDK's response unchanged (or rethrows the original exception unchanged) — **your call semantics are not altered**

### Step 3: View the metrics

If `micrometer-registry-prometheus` is on the classpath and the endpoint is exposed:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus, health
```

Visit `http://localhost:8080/actuator/prometheus` — you'll see `meridian_argus.*` metrics (full catalog below).

## Using MeridianArgus Without Spring Boot

`meridian-argus-core` has no framework dependency, so plain Java projects can use it directly:

```java
// 1. Create a registry (keep it a singleton for the app's lifetime)
LLMObservabilityRegistry registry = new LLMObservabilityRegistry();

// 2. Load pricing and build the pricing engine
PricingConfig pricingConfig = PricingConfig.loadDefault(); // or loadFromStream(yourYamlInputStream)
PricingEngine pricingEngine = new PricingEngine(pricingConfig);

// 3. Register whatever exporter(s) you need
registry.register(new MicrometerMetricsExporter(meterRegistry, pricingEngine));

// 4. Wrap calls exactly as in the Spring Boot example
OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");
```

## Provider Adapter Reference

Every adapter has the same shape — only the package and the semantics of the constructor's model parameter differ:

| Provider | Class | Constructor args | Notes |
|---|---|---|---|
| OpenAI | `OpenAICallWrapper` | `(registry, model)` | Use the official model name, e.g. `gpt-4o` |
| Azure OpenAI | `AzureOpenAICallWrapper` | `(registry, deploymentId)` | `deploymentId` should match a pricing table key. If your Azure deployment name isn't a standard model name, add a matching entry to a custom pricing config |
| AWS Bedrock | `BedrockCallWrapper` | `(registry, modelId)` | Targets the **Converse API**; use the short form matching the pricing table (e.g. `anthropic.claude-3-5-sonnet`), or add an entry for the fully-versioned Bedrock model ID if you use that instead |
| Google Vertex AI | `VertexAICallWrapper` | `(registry, modelName)` | Targets `GenerativeModel#generateContent` (Gemini) |
| Anthropic | `AnthropicCallWrapper` | `(registry, model)` | For direct `api.anthropic.com` access. If you serve Claude via Bedrock or Vertex, use that platform's adapter instead — pricing differs |

Every constructor has an overload that also accepts a `callerId` (`String`) to tag which part of your system made the call. It's stored on `LLMCallRecord` but is **not** added as a Micrometer tag by default (to avoid high-cardinality tags blowing up Prometheus storage) — write a custom exporter if you need per-caller breakdowns.

## Configuration Reference

All properties live under the `meridian-argus.*` prefix and work with any standard Spring Boot config source (`application.yml`, `application.properties`, environment variables as `MERIDIAN_ARGUS_*`, etc.).

```yaml
meridian-argus:
  enabled: true                                       # master switch
  pricing-config-path: classpath:custom-pricing.yaml   # optional: override the pricing table
  metrics:
    micrometer:
      enabled: true                                    # toggle the Micrometer exporter
    otel:
      enabled: true                                     # toggle the OpenTelemetry exporter (on by default, see below)
    sqlite:
      enabled: false                                    # toggle the SQLite exporter (opt-in, see below)
      path: meridian-argus.db                            # SQLite database file path
  alerting:
    enabled: false                                      # toggle alerting (opt-in, see below)
    thresholds:
      - name: hourly-budget
        max-cost-usd: 50.00
        window: PT1H                                    # ISO-8601 duration
    slack:
      webhook-url: https://hooks.slack.com/services/...
    teams:
      webhook-url: https://xxxxx.webhook.office.com/...
```

| Property | Type | Default | Description |
|---|---|---|---|
| `meridian-argus.enabled` | boolean | `true` | Master switch. When `false`, the starter creates no beans at all (including the registry) — observability is fully disabled |
| `meridian-argus.pricing-config-path` | string | empty (bundled table) | Overrides the pricing table. Accepts Spring `Resource` prefixes: `classpath:xxx.yaml`, `file:/path/to/xxx.yaml`. When unset, the bundled `pricing-config/default-pricing.yaml` (shipped inside the `meridian-argus-core` jar) is used |
| `meridian-argus.metrics.micrometer.enabled` | boolean | `true` | Whether to auto-register `MicrometerMetricsExporter`. Requires `micrometer-core`, this project's `meridian-argus-metrics-micrometer` module, and a `MeterRegistry` bean (typically from `spring-boot-starter-actuator`) all to be present. Set to `false` to disable this export path without removing the dependency — handy for local dev to reduce metric noise |
| `meridian-argus.metrics.otel.enabled` | boolean | `true` | Whether to auto-register `OpenTelemetryTraceExporter`. **On by default**, same reasoning as Micrometer — it does nothing on its own; it only actually activates when an `OpenTelemetry` bean already exists in the context (typically from `opentelemetry-spring-boot-starter` or a manual SDK setup). This module never creates or configures an OpenTelemetry SDK itself, it only adds spans to a tracing pipeline you've already wired up |
| `meridian-argus.metrics.sqlite.enabled` | boolean | **`false`** | Whether to auto-register `LocalStorageExporter`. Unlike Micrometer, **opt-in by default** — writing to a local file is a deliberate side effect that shouldn't activate just because `meridian-argus-metrics-sqlite` happens to be on the classpath; you must set this to `true` explicitly |
| `meridian-argus.metrics.sqlite.path` | string | `meridian-argus.db` | Path to the SQLite database file, relative or absolute. Parent directories are created automatically if missing |
| `meridian-argus.alerting.enabled` | boolean | **`false`** | Whether to auto-register `RollingWindowAlertEvaluator`. Also **opt-in** — it actively sends HTTP requests to an external webhook, which shouldn't happen just because the module is present. Setting this to `true` with no `thresholds` configured fails fast at startup rather than silently doing nothing |
| `meridian-argus.alerting.thresholds[n].name` | string | — | Threshold name, shown in notification messages and used as the lookup key for `RollingWindowAlertEvaluator#currentSpend(name)` |
| `meridian-argus.alerting.thresholds[n].max-cost-usd` | BigDecimal | — | Trigger threshold in USD |
| `meridian-argus.alerting.thresholds[n].window` | Duration | — | Rolling window length. Accepts plain ISO-8601 duration strings (`PT1H` = 1 hour, `P1D` = 1 day) — Spring Boot binds these natively, no extra configuration needed |
| `meridian-argus.alerting.slack.webhook-url` | string | empty (no Slack notifier registered) | Slack Incoming Webhook URL |
| `meridian-argus.alerting.teams.webhook-url` | string | empty (no Teams notifier registered) | Microsoft Teams Incoming Webhook URL (created via the Teams Workflows app, not the retired legacy Connectors) |
| `meridian-argus.dashboard.enabled` | boolean | **`false`** | Whether to register the read-only dashboard's `DashboardController`. Also **opt-in** — it exposes an HTTP endpoint, which shouldn't happen just because the module is present. Requires a servlet-based web application (not WebFlux, not a non-web app). **There is no separate `dashboard.path` property** — it reads from the same file `meridian-argus.metrics.sqlite.path` points at, since it's a read-only view over SQLite data |

Multiple thresholds can be configured at once (e.g. "$50/hour" and "$500/day" simultaneously) — each tracks its own independent rolling window.

### Bean wiring at a glance

| Bean | Wired when | Notes |
|---|---|---|
| `LLMObservabilityRegistry` | `meridian-argus.enabled=true` (default) | Global singleton, shared by every provider adapter and exporter |
| `PricingConfig` | same | Loads from `pricing-config-path` if set, otherwise the bundled table |
| `PricingEngine` | same | Wraps `PricingConfig`, provides cost calculation |
| `MicrometerMetricsExporter` | `meridian-argus.enabled=true` **and** `meridian-argus.metrics.micrometer.enabled=true` (default) **and** `MeterRegistry` + `MicrometerMetricsExporter` classes present on classpath | Automatically `register`ed with `LLMObservabilityRegistry` |
| `OpenTelemetryTraceExporter` | `meridian-argus.enabled=true` **and** `meridian-argus.metrics.otel.enabled=true` (default) **and** `OpenTelemetry`/`OpenTelemetryTraceExporter` classes present on classpath **and** an `OpenTelemetry` bean present in the context | Automatically `register`ed with `LLMObservabilityRegistry`; like Micrometer, whether it actually activates hinges on the `OpenTelemetry` bean being present, not this flag alone |
| `LocalStorageExporter` | `meridian-argus.enabled=true` **and** `meridian-argus.metrics.sqlite.enabled=true` (**default false, opt-in**) **and** `LocalStorageExporter` + `org.sqlite.JDBC` classes present on classpath | Automatically `register`ed with `LLMObservabilityRegistry`; `close()` is called automatically on application shutdown |
| `RollingWindowAlertEvaluator` | `meridian-argus.enabled=true` **and** `meridian-argus.alerting.enabled=true` (**default false, opt-in**) **and** `RollingWindowAlertEvaluator` class present on classpath **and** at least one threshold configured | Automatically `register`ed with `LLMObservabilityRegistry`; also automatically collects every `AlertNotifier` bean in the context and combines them with the Slack/Teams notifiers built from configuration |
| `DashboardController` | `meridian-argus.enabled=true` **and** `meridian-argus.dashboard.enabled=true` (**default false, opt-in**) **and** `DashboardController`/`UsageQuery` classes present on classpath **and** running as a servlet web application | Not registered with `LLMObservabilityRegistry` (it's a read-only query, not an exporter); wired as a plain Spring MVC bean serving `/meridian-argus/dashboard` and its JSON API |

Every bean above is `@ConditionalOnMissingBean` — define your own bean of the same type (e.g. a custom `PricingEngine`) and the starter backs off automatically.

### Overriding programmatically

If YAML isn't flexible enough (e.g. a dynamically-generated pricing table), just define your own bean:

```java
@Configuration
public class CustomPricingConfig {

    @Bean
    public PricingConfig meridianArgusPricingConfig() {
        // Fully custom loading logic — the starter's auto-configuration
        // backs off because of @ConditionalOnMissingBean.
        return PricingConfig.loadFromStream(myCustomYamlInputStream());
    }
}
```

## Pricing Config Format

A YAML file, USD per 1,000 tokens:

```yaml
providers:
  <provider-name>:
    <model-name>:
      input: 0.0025    # per-1K input (prompt) tokens
      output: 0.01     # per-1K output (completion) tokens
```

- `provider-name` must match the adapter's `PROVIDER_NAME` constant (`openai`, `azure-openai`, `bedrock`, `vertex-ai`, `anthropic`)
- `model-name` must exactly match (case-sensitive) whatever model/deployment/model-ID string you passed into the `*CallWrapper` constructor
- If no matching entry is found, `CostEstimate.isPriceKnown()` returns `false` and the Micrometer exporter skips emitting `meridian_argus.cost.usd` for that record — it never reports a misleading `$0.00`

Bundled table: [`pricing-config/default-pricing.yaml`](../pricing-config/default-pricing.yaml) (also mirrored at `meridian-argus-core/src/main/resources/default-pricing.yaml` so it ships in the jar — keep both in sync, PRs should update both).

### Custom or supplemental pricing

If a model you use isn't in the bundled table, two options:

1. **Full replacement** — write a complete YAML file and point `meridian-argus.pricing-config-path` at it
2. **Extend the bundled table** — `PricingConfig` doesn't currently support merging multiple sources, so copy the bundled table's contents and add the missing entries

Pricing data is community-maintained — PRs to `pricing-config/default-pricing.yaml` are very welcome (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## Micrometer Metrics Catalog

Every metric is tagged with `provider` and `model`.

| Metric | Type | Extra tags | Description |
|---|---|---|---|
| `meridian_argus.calls` | Counter | `success` (`true`/`false`) | Call count — use to compute volume and error rate |
| `meridian_argus.tokens.input` | Counter | — | Cumulative input tokens |
| `meridian_argus.tokens.output` | Counter | — | Cumulative output tokens |
| `meridian_argus.cost.usd` | Counter | — | Cumulative estimated cost (USD). Only emitted when the pricing table has a matching entry |
| `meridian_argus.latency` | Timer (with percentile histogram) | — | Call latency; `publishPercentileHistogram()` is enabled so `histogram_quantile()` queries work directly |

Example PromQL:

```promql
# Calls per minute by provider/model
sum(rate(meridian_argus_calls_total[1m])) by (provider, model)

# Error rate
sum(rate(meridian_argus_calls_total{success="false"}[5m])) by (provider, model)
/ sum(rate(meridian_argus_calls_total[5m])) by (provider, model)

# Estimated spend per hour
sum(rate(meridian_argus_cost_usd_total[1h])) by (provider, model) * 3600

# p95 latency
histogram_quantile(0.95, sum(rate(meridian_argus_latency_seconds_bucket[5m])) by (le, provider, model))
```

> Micrometer's Prometheus naming convention replaces `.` with `_` and appends `_total` to counters, so `meridian_argus.calls` (the name in code) becomes `meridian_argus_calls_total` in Prometheus.

## Grafana Dashboard

A ready-made dashboard ships at [`docs/grafana-dashboard.json`](grafana-dashboard.json), with panels for call volume, error rate, estimated spend, p95 latency, and token throughput. Import it directly into Grafana (point it at your Prometheus data source).

## OpenTelemetry Trace Export

`meridian-argus-otel` exports every LLM call as an OpenTelemetry span, using the official GenAI semantic conventions (`gen_ai.*` attributes) — so it slots directly into whatever distributed tracing backend you already use (Jaeger, Tempo, Datadog APM, etc.), alongside the rest of the request's spans.

### Enabling it (Spring Boot)

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-otel</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**No extra configuration required** — as long as your application already has an `OpenTelemetry` bean (from `opentelemetry-spring-boot-starter`, `micrometer-tracing-bridge-otel`, or your own manual SDK setup), this exporter wires up automatically. `meridian-argus.metrics.otel.enabled` defaults to `true`, same as Micrometer, precisely because it doesn't do anything on its own — no `OpenTelemetry` bean means no activation, so there's nothing to turn off by default.

```yaml
meridian-argus:
  metrics:
    otel:
      enabled: false   # set explicitly if you want to disable this path while keeping the dependency
```

**This module never creates or configures an OpenTelemetry SDK itself** — sampling, the OTLP export destination, resource attributes: all of that is your own SDK configuration's responsibility. `meridian-argus-otel` only adds spans to a pipeline you've already set up.

### Span attributes

| Attribute | Notes |
|---|---|
| `gen_ai.provider.name` | Provider name. `bedrock` is mapped to the registry's confirmed `aws.bedrock`; `openai` already matches the spec directly. `azure-openai`, `vertex-ai`, and `anthropic` currently pass through our own naming unchanged — **not verified against an authoritative source for their exact registry value**. If your backend does provider-specific rendering and something looks off, check the [attribute registry](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/registry/attributes/gen-ai.md) |
| `gen_ai.operation.name` | Always `chat` |
| `gen_ai.request.model` | Model name |
| `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` | Token usage |
| `error.type` | The exception type on a failed call (absent on success) |
| `meridian_argus.cost_known` / `meridian_argus.cost_usd` | Estimated cost. `cost_usd` is **entirely omitted**, not zeroed, when the price is unknown |
| `meridian_argus.caller_id` | Present if the provider adapter was constructed with a `callerId` |

The span name is `chat <model>` (e.g. `chat gpt-4o`), and it covers the call's actual start/end time (from `LLMCallRecord`'s `startedAt` + `latency`) rather than whenever this exporter happens to run — which might lag slightly behind the real call if other interceptors are registered ahead of it on `LLMObservabilityRegistry`, but the span's own timestamps aren't affected by that.

### ⚠️ The semantic conventions are still evolving

OpenTelemetry's GenAI semantic conventions are currently in **Development (unstable)** status. The attribute names have already changed once — `gen_ai.system` was renamed to the current `gen_ai.provider.name` in v1.37.0 (August 2025). If the spans this module produces don't match what your backend expects, check the [GenAI semantic conventions repository](https://github.com/open-telemetry/semantic-conventions-genai) for updates before assuming this module is broken.

### Explicitly out of scope

This module **does not capture prompt/response text content**. The spec explicitly recommends storing that kind of content in span *events*, not attributes (events can be filtered or dropped at the collector level; attributes are always indexed and size-limited). Supporting that needs its own design pass (opt-in by default, redaction rules) rather than being bolted onto this exporter — it's tracked as a separate roadmap item.

## Local SQLite Exporter

Not every team runs Prometheus. `meridian-argus-metrics-sqlite` is a zero-infrastructure alternative: every call record and its computed cost is written straight to a single-file SQLite database, no server process required.

### Enabling it (Spring Boot)

Add the module:

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-metrics-sqlite</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Turn it on explicitly (**unlike Micrometer, this is opt-in**  — writing to disk is a deliberate side effect that shouldn't activate just because the dependency happens to be on the classpath):

```yaml
meridian-argus:
  metrics:
    sqlite:
      enabled: true
      path: meridian-argus.db   # relative or absolute; parent dirs are created automatically; this is also the default filename
```

Once wired up, `LocalStorageExporter` is registered as a bean and automatically hooked into `LLMObservabilityRegistry`. Its `close()` method is called automatically on application shutdown via Spring's bean destroy-method mechanism — no manual connection lifecycle management needed.

It can run alongside the Micrometer exporter with no conflict — `LLMObservabilityRegistry` already fans out to every registered exporter.

### Schema

```sql
CREATE TABLE llm_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    started_at TEXT NOT NULL,      -- ISO-8601, Instant#toString()
    latency_ms INTEGER NOT NULL,
    success INTEGER NOT NULL,       -- 0/1
    error_type TEXT,
    caller_id TEXT,
    input_tokens INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,
    price_known INTEGER NOT NULL,   -- 0/1, 0 when the model isn't in the pricing table
    input_cost_usd TEXT,
    output_cost_usd TEXT,
    total_cost_usd TEXT
);
```

Cost columns are `NULL`, not `0`, when the price is unknown — consistent with the "unknown isn't zero" convention used everywhere else in this project. Open the file directly with the `sqlite3` CLI or any SQLite client if you want to query it yourself.

### Query helper

If you don't want to write raw SQL, `UsageQuery` covers the common questions:

```java
UsageQuery query = new UsageQuery(Path.of("meridian-argus.db"));

// Per-provider/model call count, error count, token usage, and estimated cost
List<ProviderModelCostSummary> lastHour = query.costByProviderModel(Duration.ofHours(1));

// Total cost across every provider/model
BigDecimal totalLast24h = query.totalCost(Duration.ofHours(24));

// Total call count (including failures)
long callsLastWeek = query.callCount(Duration.ofDays(7));
```

`UsageQuery` opens a short-lived connection per call — it's meant for occasional questions (a scheduled report, a small CLI tool), not a high-frequency hot path. For higher query volume, manage your own connection pool against the SQLite file directly.

It's safe to use concurrently with an active `LocalStorageExporter` writing to the same file — the database is opened with `PRAGMA journal_mode=WAL`, so reads aren't blocked by an in-flight write.

## Alerting: Rolling-Window Thresholds + Webhook Notifiers

`meridian-argus-alerting` provides an in-memory rolling-window evaluator, paired with Slack/Teams webhook notifiers, to answer "did we spend more than $X in the last N hours/days?"

### Enabling it (Spring Boot)

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-alerting</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
meridian-argus:
  alerting:
    enabled: true
    thresholds:
      - name: hourly-budget
        max-cost-usd: 50.00
        window: PT1H
      - name: daily-budget
        max-cost-usd: 500.00
        window: P1D
    slack:
      webhook-url: https://hooks.slack.com/services/xxx/yyy/zzz
    teams:
      webhook-url: https://xxxxx.webhook.office.com/xxxxxxxxx
```

Like the SQLite exporter, this is **opt-in** by default — it actively sends HTTP requests to an external webhook, which shouldn't happen just because the dependency is present. You can configure multiple thresholds at once; each tracks its own independent window.

### Notification semantics: one notification per state transition

The evaluator doesn't check-and-notify on every single call. It only notifies on a **state transition**:
- The rolling-window spend first crosses above the threshold → one `TRIGGERED` notification
- Even if 100 more calls happen while still over the limit → no repeat notifications
- Once old entries age out of the window and spend drops back under the threshold → one `RECOVERED` notification

This is deliberate — it avoids paging someone once per LLM call during a sustained cost spike. If you want periodic reminders while a threshold stays breached, implement that yourself in a custom `AlertNotifier` (e.g. track the last-notified time).

### Custom notifiers

Beyond the built-in Slack/Teams notifiers, define your own `AlertNotifier` bean (PagerDuty, email, your own logging pipeline, whatever) — the starter automatically collects every `AlertNotifier` bean in the context and combines them with the Slack/Teams notifiers built from configuration, no manual wiring needed:

```java
@Bean
public AlertNotifier pagerDutyNotifier() {
    return event -> {
        if (event.state() == AlertEvent.State.TRIGGERED) {
            pagerDutyClient.triggerIncident("MeridianArgus: " + event.threshold().getName());
        }
    };
}
```

### Manual wiring without Spring Boot

```java
AlertThreshold hourly = new AlertThreshold("hourly-budget", new BigDecimal("50.00"), Duration.ofHours(1));

List<AlertNotifier> notifiers = List.of(
    new SlackWebhookNotifier(URI.create("https://hooks.slack.com/services/xxx/yyy/zzz"))
);

RollingWindowAlertEvaluator evaluator = new RollingWindowAlertEvaluator(pricingEngine, List.of(hourly), notifiers);
registry.register(evaluator);

// Query a threshold's current rolling-window spend at any time
BigDecimal current = evaluator.currentSpend("hourly-budget");
```

### Known limitations

- Purely in-memory — window state resets on application restart (doesn't affect correctness, just means spend has to re-accumulate to the threshold again before re-triggering)
- No entry-count cap, only time-based pruning — a very long window (e.g. 30 days) combined with high call volume will grow memory usage over time with no additional capacity control currently in place
- Microsoft Teams' webhook format has changed materially in recent years (the legacy Office 365 Connectors were retired in favor of Workflows + Adaptive Cards) — if delivery fails, check [Microsoft's current documentation](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook) to confirm your webhook was created via the Workflows app

## Read-Only Web Dashboard

`meridian-argus-web-dashboard` provides a zero-dependency read-only dashboard page for teams that don't want to stand up Grafana. It reads from the same SQLite file `meridian-argus-metrics-sqlite` writes to — purely a view, it never writes anything.

### Enabling it (Spring Boot)

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-web-dashboard</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
meridian-argus:
  metrics:
    sqlite:
      enabled: true              # the dashboard needs data to show, so this is usually on too
      path: meridian-argus.db
  dashboard:
    enabled: true
```

Once the app is running, visit `http://localhost:<port>/meridian-argus/dashboard` for a page showing total cost, total calls, error count, and a per-provider/model breakdown table. A dropdown switches between the last 1 hour / 24 hours / 7 days / 30 days, and the page auto-refreshes every 30 seconds.

**There's no separate `dashboard.path` property** — it reads from whatever `meridian-argus.metrics.sqlite.path` points at. You technically could enable the dashboard without the SQLite exporter itself (e.g. if a separate process writes to that file), but normally you'll want both properties on together.

### Implementation notes

- **No frontend build step**: the entire page is a single static HTML file bundled inside the jar (inline CSS + vanilla JS) — no npm/webpack, no CDN dependency, renders correctly fully offline
- **Read-only**: the controller only queries data and is not wired into `LLMObservabilityRegistry` — unlike every other component in this project, it's not a "record receiver"
- **JSON API**: if you want the raw data for something else, call `GET /meridian-argus/dashboard/api/summary?windowHours=24` directly rather than parsing the HTML

### On the dependency choice

This module depends on `spring-webmvc` (the annotation/dispatch types) rather than `spring-boot-starter-webmvc` — your application should already have its own embedded server, and this module doesn't bring a second one. As a consequence, the dashboard requires a **servlet-based** Spring MVC application (not WebFlux, not a non-web app), enforced via `@ConditionalOnWebApplication(type = SERVLET)` — when that condition isn't met, the auto-configuration quietly skips rather than failing.

## Writing a Custom Exporter

If Micrometer/Prometheus isn't your stack, implement your own:

```java
public class MyCustomExporter implements LLMCallInterceptor {
    @Override
    public void onCallCompleted(LLMCallRecord record) {
        // your logic: write to a DB, fire a webhook, push to Datadog/CloudWatch, etc.
    }
}
```

If you need cost data (`CostEstimate`), extend `AbstractMetricsExporter` (in `meridian-argus-core`) — it already handles "compute cost, then call `export(record, cost)`":

```java
public class MyCustomExporter extends AbstractMetricsExporter {
    public MyCustomExporter(PricingEngine pricingEngine) {
        super(pricingEngine);
    }

    @Override
    public void export(LLMCallRecord record, CostEstimate cost) {
        // record is the raw call record; cost is the already-computed estimate
    }
}
```

Then register it with the registry — in Spring, define a `@Bean` that injects `LLMObservabilityRegistry` and calls `register(...)`.

## FAQ / Troubleshooting

**Q: I get `Could not find artifact com.prckjw.meridianargus:...:jar:0.1.0-SNAPSHOT` / `Could not resolve dependencies` when running the demo module.**
A: This is the classic multi-module Maven gotcha — it means you ran `mvn` from *inside* `meridian-argus-examples/spring-boot-openai-demo` (or opened just that folder as a standalone project in your IDE), instead of from the repository root. The demo depends on sibling modules (`meridian-argus-spring-boot-starter`, `meridian-argus-provider-openai`, etc.) that are `0.1.0-SNAPSHOT` and exist only as source — they've never been published anywhere, so Maven Central can never resolve them either.

Run from the **repository root** instead, letting `-am` (also-make) build the sibling modules in the same reactor session:

```bash
cd meridian-argus   # repo root, not the demo subfolder
mvn -pl meridian-argus-examples/spring-boot-openai-demo -am org.springframework.boot:spring-boot-maven-plugin:run
```

Or install everything to your local `~/.m2` once (`mvn install -DskipTests` from the root), after which any submodule can be run standalone. Same applies in an IDE: import the **root** `pom.xml` as the project, not the demo subfolder in isolation.

**Q: Why don't I see `meridian_argus.*` metrics at `/actuator/prometheus`?**
Check, in order:
1. Is `micrometer-registry-prometheus` on the classpath?
2. Does `management.endpoints.web.exposure.include` include `prometheus`?
3. Are `meridian-argus.enabled` and `meridian-argus.metrics.micrometer.enabled` both `true` (both default to `true`)?
4. Did the call actually go through `*CallWrapper.call(...)`? Calling the raw SDK directly, bypassing the wrapper, is never recorded.

**Q: Why does a call have no cost data?**
The `provider`/`model` pair isn't in the pricing table. Check that the model name exactly matches a pricing table key (case-sensitive), or add a custom pricing entry.

**Q: My Azure deployment name doesn't match a standard model name — what now?**
In a custom pricing table, set the model key under `azure-openai` to your actual deployment name; copy the price numbers from the equivalent official model.

**Q: Can one application use multiple providers at once?**
Yes — that's the intended use case. Use a separate `*CallWrapper` per provider, all sharing the same `LLMObservabilityRegistry`; metrics are automatically separated by the `provider` tag.

**Q: What's `callerId` actually used for?**
It's only kept on the `LLMCallRecord` object (for your own custom exporters to use) and is **not** added as a Micrometer tag, to avoid high-cardinality tags (e.g. per-user) blowing up Prometheus storage. If you need per-user/per-tenant breakdowns, write a custom `LLMCallInterceptor` that exports to logs or a database instead of stuffing it into a Prometheus tag.
