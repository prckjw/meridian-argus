# Contributing to MeridianArgus

Thanks for considering a contribution. A few ways to help, roughly in order of impact-to-effort:

## 1. Pricing table updates

[`pricing-config/default-pricing.yaml`](pricing-config/default-pricing.yaml) is the single source of truth, copied into `meridian-argus-core`'s resources at build time. Provider prices change frequently and this file will drift — PRs that update a price with a link to the provider's official pricing page in the PR description are always welcome and don't require any Java knowledge.

## 2. New provider adapters

Each adapter module (see `meridian-argus-provider-openai` for the reference implementation) follows the same shape:

- A `*CallWrapper` class taking a `Supplier<R>` for the SDK call and a `Function<R, TokenUsage>` for usage extraction
- No compile-time dependency on the provider's actual SDK — this keeps the adapter compiling regardless of which SDK version the consumer has on their classpath
- A corresponding entry (or several) in the default pricing table

OpenAI, Azure OpenAI, Bedrock, Vertex AI, and Anthropic are all covered. If you'd like to add another provider (or split Claude-on-Bedrock/Vertex pricing further), open an issue first so we can agree on the shape before you invest the time.

## 3. New exporters (and the optional-dependency gotcha)

If your exporter lives in its own module with optional dependencies (the way `meridian-argus-metrics-micrometer` is an `<optional>true</optional>` dependency of the starter), **do not** reference its types directly from a `@Bean` method inside the always-active `MeridianArgusAutoConfiguration`. A class-literal `@ConditionalOnClass(SomeType.class)` only gates on `SomeType` being present — it does nothing to protect a *different* type referenced in the same method's signature. If a consumer has one optional dependency but not the other, Spring will still try to process that `@Bean` method and blow up with `NoClassDefFoundError` at runtime.

The correct pattern (see `MeridianArgusMicrometerAutoConfiguration` for a worked example):

- Put the optional-dependency-consuming `@Bean` method in its **own** `@AutoConfiguration(after = MeridianArgusAutoConfiguration.class)` class
- Register it in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Gate the whole class with `@ConditionalOnClass(name = {"fully.qualified.OptionalType1", "fully.qualified.OptionalType2"})` using **string class names**, not class literals — this defers the check to ASM metadata reading instead of requiring the annotation's own class-literal argument to be resolvable
- Add a test with `.withClassLoader(new FilteredClassLoader(OptionalType.class))` asserting the context still starts cleanly when the optional dependency is absent (this is exactly the scenario the string-based `@ConditionalOnClass` is protecting)

## 4. Bug reports

Please include: MeridianArgus version, the provider/adapter involved, a minimal reproduction, and what you expected vs. what happened.

## 5. Development setup

```bash
mvn -B verify
```

Requires JDK 21+. The build is a standard multi-module Maven project — no special tooling needed.

## Code style

- No external formatter is enforced yet; match the style of the surrounding code
- Public classes and non-trivial methods should have Javadoc explaining *why*, not just *what*
- New behavior needs a test; bug fixes should include a regression test where practical

## Commit / PR conventions

- Keep PRs focused — one logical change per PR
- Reference the issue number if one exists
- CI (`mvn verify`) must pass before merge
