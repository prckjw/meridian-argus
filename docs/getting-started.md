# Getting Started

## 1. Run the demo (no API key required)

```bash
git clone https://github.com/prckjw/meridian-argus.git
cd meridian-argus
mvn -pl meridian-argus-examples/spring-boot-openai-demo -am org.springframework.boot:spring-boot-maven-plugin:run
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

**`Could not find artifact com.prckjw.meridianargus:...:jar:0.1.0-SNAPSHOT` / `Could not resolve dependencies` when running the demo module.**
This means you ran Maven from *inside* `meridian-argus-examples/spring-boot-openai-demo` (or opened just that folder as a standalone project in your IDE), instead of from the repository root. The demo depends on sibling modules (`meridian-argus-spring-boot-starter`, `meridian-argus-provider-openai`, etc.) that only exist as source until Maven builds them — they're `0.1.0-SNAPSHOT` and were never published anywhere, so Maven Central can never resolve them either.

Fix — run from the **repository root**, letting `-am` (also-make) build the sibling modules in the same reactor session:

```bash
cd meridian-argus   # repo root, not the demo subfolder
mvn -pl meridian-argus-examples/spring-boot-openai-demo -am org.springframework.boot:spring-boot-maven-plugin:run
```

Or install everything to your local `~/.m2` once, after which any submodule can be run standalone:

```bash
mvn install -DskipTests
cd meridian-argus-examples/spring-boot-openai-demo
mvn org.springframework.boot:spring-boot-maven-plugin:run   # now resolves fine
```

Same applies in an IDE: import the **root** `pom.xml` as the project, not the demo subfolder in isolation, so the IDE's dependency resolver sees all sibling modules.

**No `meridian_argus.*` metrics show up.**
Check that `micrometer-registry-prometheus` (or another Micrometer registry) is on the classpath — the exporter bean only activates if a `MeterRegistry` bean exists (`@ConditionalOnClass`/`@ConditionalOnMissingBean` in `MeridianArgusAutoConfiguration`).

**Cost always shows as 0.**
Check `CostEstimate.isPriceKnown()` — if your provider/model combination isn't in the pricing table, cost is reported as unknown rather than zero-but-wrong. Add an entry to your pricing YAML.
