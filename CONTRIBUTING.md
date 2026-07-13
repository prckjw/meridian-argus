# Contributing to MeridianArgus

Thanks for considering a contribution. A few ways to help, roughly in order of impact-to-effort:

## 1. Pricing table updates

[`pricing-config/default-pricing.yaml`](pricing-config/default-pricing.yaml) is the single source of truth, copied into `meridian-argus-core`'s resources at build time. Provider prices change frequently and this file will drift — PRs that update a price with a link to the provider's official pricing page in the PR description are always welcome and don't require any Java knowledge.

## 2. New provider adapters

Each adapter module (see `meridian-argus-provider-openai` for the reference implementation) follows the same shape:

- A `*CallWrapper` class taking a `Supplier<R>` for the SDK call and a `Function<R, TokenUsage>` for usage extraction
- No compile-time dependency on the provider's actual SDK — this keeps the adapter compiling regardless of which SDK version the consumer has on their classpath
- A corresponding entry (or several) in the default pricing table

If you'd like to add Bedrock, Vertex AI, or Anthropic support, open an issue first so we can agree on the shape before you invest the time.

## 3. Bug reports

Please include: MeridianArgus version, the provider/adapter involved, a minimal reproduction, and what you expected vs. what happened.

## 4. Development setup

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
