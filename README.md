# MeridianArgus

**Unified observability and cost governance for multi-provider LLM calls in Java.**

English | [简体中文](README.zh-CN.md)

One consistent view of token usage, latency, error rate, and estimated cost — across Azure OpenAI, OpenAI, AWS Bedrock, Google Vertex AI, and Anthropic — without wiring up a separate dashboard for each provider your team happens to use.

[![CI](https://github.com/prckjw/meridian-argus/actions/workflows/ci.yml/badge.svg)](https://github.com/prckjw/meridian-argus/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/)

---

## Why

Most teams building on LLMs today are multi-provider whether they planned to be or not — Azure OpenAI for the enterprise contract, a direct OpenAI key for prototyping, maybe Bedrock because that's where the rest of the AWS workload lives. Each SDK reports usage differently, none of them tell you what you're actually spending until the invoice arrives, and there's no Java-native equivalent of tools like Langfuse or Helicone that plugs into a Spring Boot app in five minutes.

MeridianArgus is that missing piece: a small, provider-agnostic core plus thin adapters, wired into your app with one Spring Boot starter.

## What it does

- **Normalizes** token usage, latency, and success/failure across providers into one record shape (`LLMCallRecord`)
- **Prices** every call against a configurable, community-maintained pricing table (USD per 1k tokens)
- **Exports** to Micrometer/Prometheus out of the box, with a ready-made Grafana dashboard
- **Adds zero business-logic coupling** — wrap the SDK call you're already making, nothing else changes

## Quick start

Add the starter and the provider adapter(s) you need:

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

Wrap the call you're already making:

```java
@RestController
public class ChatController {

    private final OpenAICallWrapper wrapper;

    public ChatController(LLMObservabilityRegistry registry) {
        this.wrapper = new OpenAICallWrapper(registry, "gpt-4o");
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

That's it — no annotations, no separate agent process. Metrics show up at `/actuator/prometheus` as soon as `micrometer-registry-prometheus` is on the classpath.

See [`meridian-argus-examples/spring-boot-openai-demo`](meridian-argus-examples/spring-boot-openai-demo) for a runnable end-to-end example (no API key required — it simulates the SDK call so you can see the full pipeline immediately).

## Architecture

```
              Your application code
                       │
        wraps a call with a provider adapter
                       │
     ┌────────────┬────────────┬────────────┬────────────┬────────────┐
 OpenAICall   AzureOpenAI   Bedrock      VertexAI    Anthropic
 Wrapper      CallWrapper   CallWrapper  CallWrapper  CallWrapper
     │            │            │            │            │
     └────────────┴────────────┴────────────┴────────────┘
                       │  publishes LLMCallRecord
                       ▼
            LLMObservabilityRegistry (fan-out)
                       │
      ┌────────────────┼─────────────────────┐
      ▼                ▼                      ▼
PricingEngine   MicrometerMetricsExporter   (your own exporter)
      │                │
 (USD estimate)   Prometheus / Grafana
```

Full design notes: [`docs/architecture.md`](docs/architecture.md).

## Provider support matrix

| Provider | Status | Module |
|---|---|---|
| OpenAI | ✅ Available | `meridian-argus-provider-openai` |
| Azure OpenAI | ✅ Available | `meridian-argus-provider-azure-openai` |
| AWS Bedrock | ✅ Available | `meridian-argus-provider-bedrock` |
| Google Vertex AI | ✅ Available | `meridian-argus-provider-vertex` |
| Anthropic | ✅ Available | `meridian-argus-provider-anthropic` |

All five have matching entries in [`pricing-config/default-pricing.yaml`](pricing-config/default-pricing.yaml), so cost estimates work out of the box for the default model list. Bedrock and Anthropic both serve Claude models — use whichever adapter matches how you're actually accessing the model (direct API vs. hosted on AWS), since pricing differs between the two.

## Modules

| Module | Purpose |
|---|---|
| `meridian-argus-core` | Provider-agnostic model, interceptor SPI, pricing engine — no framework dependency |
| `meridian-argus-provider-*` | Thin wrappers around each provider's official SDK |
| `meridian-argus-metrics-micrometer` | Exports records as Micrometer meters |
| `meridian-argus-spring-boot-starter` | Auto-configuration wiring everything above into Spring beans |
| `meridian-argus-examples/*` | Runnable demo apps |

## Design principles

- **No SDK lock-in at compile time.** Provider adapters take a `Supplier` for the call and a `Function` for usage extraction, so they compile against whatever SDK version you already depend on, rather than pinning a transitive version that might conflict with yours.
- **Never break the caller.** A failing exporter is caught and logged, never propagated — observability code must not be able to take down the business request it's observing.
- **Unknown pricing is explicit, not silently zero.** If a provider/model isn't in the pricing table, `CostEstimate.isPriceKnown()` returns `false` rather than reporting a misleading $0.00.
- **Local-first.** No data leaves your infrastructure unless you configure an exporter that sends it somewhere. No telemetry is phoned home by this project.

## Status

Early (`0.1.0-SNAPSHOT`). Core pipeline, all five provider adapters (OpenAI, Azure OpenAI, Bedrock, Vertex AI, Anthropic), and Micrometer export are implemented. The local SQLite exporter and the optional web dashboard are on the roadmap — see [`docs/architecture.md`](docs/architecture.md) for the full plan. None of the SDK-specific adapter code has been run against live provider SDKs yet (no network access in the environment this was built in) — if you hit a compile mismatch against the SDK version you use, please open an issue or PR. Issues and PRs welcome, especially pricing-table updates.

## Contributing

Pricing changes often — updating [`pricing-config/default-pricing.yaml`](pricing-config/default-pricing.yaml) with a source link is one of the easiest and most valuable contributions. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full guide.

## License

[Apache License 2.0](LICENSE)
