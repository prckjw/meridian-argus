# Getting Started

## 1. Run the demo (no API key required)

```bash
git clone https://github.com/prckjw/meridian-argus.git
cd meridian-argus
mvn -pl meridian-argus-examples/spring-boot-openai-demo -am spring-boot:run
```

Then in another terminal:

```bash
curl "http://localhost:8080/ask?prompt=What+is+observability"
curl http://localhost:8080/actuator/prometheus | grep meridian_argus
```

You should see `meridian_argus_calls_total`, `meridian_argus_tokens_input_total`, `meridian_argus_tokens_output_total`, `meridian_argus_cost_usd_total`, and `meridian_argus_latency_seconds` — all tagged with `provider="openai"` and `model="gpt-4o"`.

## 2. Add to your own project

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
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

(Swap `provider-openai` for `provider-azure-openai` if that's your setup — the wrapper API is identical, only the constructor argument changes from model name to deployment name.)

## 3. Wrap your existing SDK call

Inject `LLMObservabilityRegistry` (auto-configured by the starter) wherever you currently call the provider SDK, construct a wrapper once, and call through it:

```java
private final OpenAICallWrapper wrapper;

public MyService(LLMObservabilityRegistry registry) {
    this.wrapper = new OpenAICallWrapper(registry, "gpt-4o");
}

public String summarize(String text) {
    ChatCompletion result = wrapper.call(
        () -> client.chat().completions().create(requestFor(text)),
        r -> TokenUsage.of(r.usage().promptTokens(), r.usage().completionTokens())
    );
    return result.choices().get(0).message().content();
}
```

## 4. Point Grafana at it

Import [`docs/grafana-dashboard.json`](grafana-dashboard.json) into Grafana, pointed at a Prometheus instance scraping your app's `/actuator/prometheus` endpoint.

## 5. Customize pricing

If you're using a custom Azure deployment name, a fine-tuned model, or a provider/model combination not in the default table, add your own YAML and point to it:

```yaml
meridian-argus:
  pricing-config-path: classpath:my-pricing.yaml
```

Same shape as [`pricing-config/default-pricing.yaml`](../pricing-config/default-pricing.yaml).

## Troubleshooting

**No `meridian_argus.*` metrics show up.**
Check that `micrometer-registry-prometheus` (or another Micrometer registry) is on the classpath — the exporter bean only activates if a `MeterRegistry` bean exists (`@ConditionalOnClass`/`@ConditionalOnMissingBean` in `MeridianArgusAutoConfiguration`).

**Cost always shows as 0.**
Check `CostEstimate.isPriceKnown()` — if your provider/model combination isn't in the pricing table, cost is reported as unknown rather than zero-but-wrong. Add an entry to your pricing YAML.
