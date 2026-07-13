# Architecture

## Goals

1. Adding observability to an existing LLM integration should require wrapping the call, not rewriting it.
2. The core library should never depend on any specific provider's SDK, so it never forces a version conflict on the consumer's classpath.
3. Cost estimates should be honest about what they don't know, rather than silently reporting $0.00 for unrecognized models.
4. Nothing leaves the consumer's process unless they explicitly configure an exporter that sends it somewhere.

## Data flow

```
1. Application code calls wrapper.call(sdkCallSupplier, usageExtractor)
2. Wrapper times the call, invokes the supplier, catches success/failure
3. Wrapper builds an LLMCallRecord (provider, model, tokens, latency, success)
4. Wrapper publishes the record to LLMObservabilityRegistry
5. Registry fans out to every registered LLMCallInterceptor
6. Each MetricsExporter (e.g. MicrometerMetricsExporter) prices the call via
   PricingEngine and exports it (e.g. as Micrometer counters/timers)
```

## Why adapters don't depend on the actual provider SDKs

Early designs considered having `meridian-argus-provider-openai` depend directly on `com.openai:openai-java`, deserializing the SDK's response types internally. This was rejected because:

- It pins a specific SDK version, which will eventually conflict with whatever version the consuming application actually uses
- Provider SDKs change their response shapes across versions; a hard dependency means every SDK bump is a potential breaking change for this library
- The generic `Supplier<R>` / `Function<R, TokenUsage>` pattern needs zero maintenance when a provider ships a new SDK version — the consumer's usage-extraction lambda is the only thing that might need updating, and only if the field names actually changed

The trade-off is that integration is one extra lambda instead of being fully automatic (no bytecode instrumentation, no method interception). This is a deliberate simplicity-over-magic choice.

## Module boundaries

- **core**: zero framework dependencies beyond SLF4J and SnakeYAML. Anyone should be able to use `PricingEngine` and `LLMObservabilityRegistry` in a plain Java app, no Spring required.
- **provider-*****: depends only on core. Adds no transitive dependency on the actual provider SDK.
- **metrics-micrometer**: depends on core + Micrometer. Kept separate so consumers who want a different exporter (e.g. straight to a webhook, or OpenTelemetry) aren't forced to pull in Micrometer.
- **spring-boot-starter**: the only module that knows about Spring. Wires the above into beans via `@ConditionalOnClass`/`@ConditionalOnProperty` so unused pieces (e.g. Micrometer export when no `MeterRegistry` bean exists) never activate.

## Roadmap

- ~~**v0.2**: Bedrock, Vertex AI, and Anthropic adapters~~ done — shipped in `meridian-argus-provider-bedrock`, `meridian-argus-provider-vertex`, `meridian-argus-provider-anthropic`
- **v0.2**: Local SQLite exporter (`LocalStorageExporter`) for teams that don't run Prometheus
- **v0.3**: Rolling-window alert evaluator built on `AlertThreshold`, with a Slack/Teams webhook notifier
- **v0.3**: Optional lightweight web dashboard (`meridian-argus-web-dashboard`) for teams that want a UI without standing up Grafana
- **Later**: OpenTelemetry trace export as an alternative to/alongside Micrometer
- **Later**: Prompt/response content capture with configurable redaction, opt-in only

## Non-goals

- This project does not proxy or intercept network traffic — it only records what the consumer's own code tells it via the wrapper call
- This project does not store or transmit prompt/response content by default
- This project is not an LLM gateway or router; it observes calls, it doesn't route or retry them (though nothing stops you from combining it with one)
