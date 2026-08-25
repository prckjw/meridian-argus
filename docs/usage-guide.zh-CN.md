# MeridianArgus 使用说明与参数配置文档

[English](usage-guide.md) | 简体中文

本文档面向已经了解项目背景（见 [README.zh-CN.md](../README.zh-CN.md)）、准备实际接入 MeridianArgus 的开发者，涵盖：安装方式、接入步骤、每个 Provider 适配器的用法、全部可配置参数、价格表格式、Micrometer 指标清单、常见问题排查。

---

## 目录

1. [环境要求](#环境要求)
2. [安装](#安装)
3. [快速接入（Spring Boot）](#快速接入spring-boot)
4. [不使用 Spring Boot 的接入方式](#不使用-spring-boot-的接入方式)
5. [各 Provider 适配器用法](#各-provider-适配器用法)
6. [参数配置完整说明](#参数配置完整说明)
7. [价格表（Pricing Config）说明](#价格表pricing-config说明)
8. [Micrometer 导出指标清单](#micrometer-导出指标清单)
9. [Grafana Dashboard](#grafana-dashboard)
10. [自定义 Exporter](#自定义-exporter)
11. [常见问题](#常见问题)

---

## 环境要求

| 项目 | 版本要求 |
|---|---|
| JDK | 21+（Spring Boot 4.1 最低要求 Java 17，本项目统一编译目标定为 21） |
| Spring Boot | 4.1.0（`spring-boot.version` 由父 POM 统一管理） |
| 构建工具 | Maven（多模块项目） |
| 各 Provider 官方 SDK | 由使用方自行引入，本项目不强制绑定具体版本（见下文「版本策略」） |

> 说明：本项目的 Provider 适配器代码没有在联网环境下实际编译验证过（开发环境无 Maven Central 访问权限），建议接入后先在本地跑一次 `mvn -B verify`，遇到方法签名不匹配的问题欢迎提 Issue。

## 安装

### Maven

在你的项目 `pom.xml` 中引入 Starter 和需要的 Provider 适配器：

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- 按需引入一个或多个 Provider 适配器 -->
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-provider-openai</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 版本策略

Provider 适配器（`OpenAICallWrapper`、`AzureOpenAICallWrapper` 等）刻意设计成**不在编译期绑定具体 SDK 版本**——它们只接收一个用于发起调用的 `Supplier<R>` 和一个用于提取用量的 `Function<R, TokenUsage>`，泛型参数 `R` 就是你自己项目里已经引入的 SDK 返回类型。这意味着：

- 你可以用项目里现有的任意版本的 OpenAI/Azure OpenAI/Bedrock/Vertex AI/Anthropic 官方 SDK，不需要额外对齐版本
- MeridianArgus 本身**不会**给你的项目引入这些 SDK 的传递依赖，需要你自己在 `pom.xml` 里声明

## 快速接入（Spring Boot）

### 第一步：启用自动装配

```java
@SpringBootApplication
@EnableLLMObservability
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@EnableLLMObservability` 是可选的显式标记（自动装配本身通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 机制生效，只要 Starter 在 classpath 上就会自动启用）。加上这个注解主要是让入口类的意图更清晰。

### 第二步：包装你的 SDK 调用

以 OpenAI 为例：

```java
@RestController
public class ChatController {

    private final OpenAICallWrapper wrapper;
    private final OpenAIClient openAiClient; // 你项目里已有的官方 SDK 客户端

    public ChatController(LLMObservabilityRegistry registry, OpenAIClient openAiClient) {
        // 第二个参数是模型名，需要能对上价格表里的 key（见下文价格表说明）
        this.wrapper = new OpenAICallWrapper(registry, "gpt-4o");
        this.openAiClient = openAiClient;
    }

    @PostMapping("/chat")
    public String chat(@RequestBody String prompt) {
        ChatCompletion response = wrapper.call(
            // 第一个参数：实际发起调用的 Supplier
            () -> openAiClient.chat().completions().create(buildRequest(prompt)),
            // 第二个参数：从响应里提取 token 用量
            r -> TokenUsage.of(r.usage().promptTokens(), r.usage().completionTokens())
        );
        return response.choices().get(0).message().content();
    }
}
```

`LLMObservabilityRegistry` 是 Starter 自动注册的单例 Bean，直接从构造函数注入即可。`wrapper.call(...)` 会：

1. 记录调用开始时间
2. 执行 `sdkCall.get()`（也就是真正的 SDK 调用）
3. 无论成功还是抛出运行时异常，都会构造一条 `LLMCallRecord` 并发布到 registry
4. 成功时通过 `usageExtractor` 提取 token 用量；提取失败不会影响业务返回值，只是这条记录的用量会记为 0
5. 原样返回 SDK 的响应（或原样重新抛出异常），**不改变你原有的调用行为**

### 第三步：查看指标

如果 classpath 上有 `micrometer-registry-prometheus`，并且暴露了 `/actuator/prometheus` 端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus, health
```

访问 `http://localhost:8080/actuator/prometheus`，就能看到 `meridian_argus.*` 开头的指标（完整清单见下文）。

## 不使用 Spring Boot 的接入方式

MeridianArgus 的 `core` 模块本身不依赖任何框架，纯 Java 项目也能用：

```java
// 1. 创建 registry（应用生命周期内保持单例）
LLMObservabilityRegistry registry = new LLMObservabilityRegistry();

// 2. 加载价格表并创建计价引擎
PricingConfig pricingConfig = PricingConfig.loadDefault(); // 或 loadFromStream(自定义 yaml 输入流)
PricingEngine pricingEngine = new PricingEngine(pricingConfig);

// 3. 注册你需要的 exporter（比如 Micrometer）
registry.register(new MicrometerMetricsExporter(meterRegistry, pricingEngine));

// 4. 用 Provider 适配器包装调用，用法与 Spring Boot 场景完全一致
OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");
```

## 各 Provider 适配器用法

所有适配器的构造函数和 `call(...)` 方法签名完全一致，只是包名和构造参数语义略有差异：

| Provider | 类 | 构造参数 | 备注 |
|---|---|---|---|
| OpenAI | `OpenAICallWrapper` | `(registry, model)` | `model` 建议直接用官方模型名，如 `gpt-4o` |
| Azure OpenAI | `AzureOpenAICallWrapper` | `(registry, deploymentId)` | `deploymentId` 需要能匹配价格表 key（如果你的 Azure 部署名不是标准模型名，需要在自定义价格表里补充对应条目） |
| AWS Bedrock | `BedrockCallWrapper` | `(registry, modelId)` | 面向 Bedrock 的 **Converse API**；`modelId` 建议用价格表里的短名（如 `anthropic.claude-3-5-sonnet`），如果要用带版本号的完整 Bedrock model ID，需要在价格表里补充对应条目 |
| Google Vertex AI | `VertexAICallWrapper` | `(registry, modelName)` | 面向 `GenerativeModel#generateContent`（Gemini） |
| Anthropic | `AnthropicCallWrapper` | `(registry, model)` | 面向官方 `api.anthropic.com` 直连；如果你是通过 Bedrock/Vertex 调用 Claude，请用对应平台的适配器而不是这个，因为计价方式不同 |

所有构造函数都有一个重载版本，额外接收 `callerId` 参数（`String`），用于标记这次调用的发起方（比如用户 ID、服务名），会记录在 `LLMCallRecord` 里但**目前不会**作为 Micrometer 指标的 tag（避免高基数 tag 拖垮 Prometheus，如果你需要按 caller 维度统计，建议写自定义 exporter）。

## 参数配置完整说明

以下参数均绑定在 `meridian-argus.*` 前缀下，支持 `application.yml`、`application.properties`、环境变量（`MERIDIAN_ARGUS_*`）等 Spring Boot 标准配置源。

```yaml
meridian-argus:
  enabled: true                                    # 总开关
  pricing-config-path: classpath:custom-pricing.yaml  # 可选：自定义价格表路径
  metrics:
    micrometer:
      enabled: true                                # Micrometer 导出开关
    otel:
      enabled: true                                # OpenTelemetry 导出开关（默认开启，见下方说明）
    sqlite:
      enabled: false                                # SQLite 导出开关（默认关闭，需显式开启）
      path: meridian-argus.db                        # SQLite 数据库文件路径
  alerting:
    enabled: false                                  # 告警开关（默认关闭，需显式开启）
    thresholds:
      - name: hourly-budget
        max-cost-usd: 50.00
        window: PT1H                                # ISO-8601 时长格式
    slack:
      webhook-url: https://hooks.slack.com/services/...
    teams:
      webhook-url: https://xxxxx.webhook.office.com/...
```

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `meridian-argus.enabled` | boolean | `true` | 总开关。设为 `false` 时，Starter 不会创建任何 Bean（包括 registry），相当于完全禁用可观测性 |
| `meridian-argus.pricing-config-path` | string | 空（使用内置价格表） | 自定义价格表路径，支持 Spring 的 `Resource` 前缀写法：`classpath:xxx.yaml`、`file:/path/to/xxx.yaml`。不配置时使用内置的 `pricing-config/default-pricing.yaml`（打包在 `meridian-argus-core` 的 jar 里） |
| `meridian-argus.metrics.micrometer.enabled` | boolean | `true` | 是否自动注册 `MicrometerMetricsExporter`。需要同时满足：classpath 上有 `micrometer-core` 和本项目的 `meridian-argus-metrics-micrometer` 模块、容器里存在 `MeterRegistry` Bean（通常由 `spring-boot-starter-actuator` 提供）。设为 `false` 可以在保留依赖的情况下临时关闭这一路导出，比如本地开发时不想产生指标噪音 |
| `meridian-argus.metrics.otel.enabled` | boolean | `true` | 是否自动注册 `OpenTelemetryTraceExporter`。跟 Micrometer 一样**默认开启**——因为它本身不会主动做任何事，只有容器里真的存在 `OpenTelemetry` Bean（通常来自 `opentelemetry-spring-boot-starter` 或你自己手动配的 OTel SDK）时才会真正激活；这个模块不负责创建/配置 OTel SDK 本身，只是往已有的追踪链路上加 Span |
| `meridian-argus.metrics.sqlite.enabled` | boolean | **`false`** | 是否自动注册 `LocalStorageExporter`。跟 Micrometer 那项不一样，**默认关闭**——写本地文件是需要主动选择的副作用，不会因为 `meridian-argus-metrics-sqlite` 恰好在 classpath 上就自动生效，必须显式设为 `true` |
| `meridian-argus.metrics.sqlite.path` | string | `meridian-argus.db` | SQLite 数据库文件路径，相对或绝对路径均可，父目录不存在会自动创建 |
| `meridian-argus.alerting.enabled` | boolean | **`false`** | 是否自动注册 `RollingWindowAlertEvaluator`。同样**默认关闭**——会主动往外部 Webhook 发 HTTP 请求，不应该因为模块在 classpath 上就自动生效。设为 `true` 但没配置任何 `thresholds` 会在启动时直接失败（快速失败，而不是悄悄什么都不做） |
| `meridian-argus.alerting.thresholds[n].name` | string | — | 阈值名称，会出现在通知消息里，同时也是 `RollingWindowAlertEvaluator#currentSpend(name)` 的查询 key |
| `meridian-argus.alerting.thresholds[n].max-cost-usd` | BigDecimal | — | 触发阈值（美元） |
| `meridian-argus.alerting.thresholds[n].window` | Duration | — | 滚动窗口时长，支持 ISO-8601 格式（如 `PT1H` 表示 1 小时、`P1D` 表示 1 天），Spring Boot 原生支持这种绑定，不需要额外配置 |
| `meridian-argus.alerting.slack.webhook-url` | string | 空（不注册 Slack 通知） | Slack Incoming Webhook 地址 |
| `meridian-argus.alerting.teams.webhook-url` | string | 空（不注册 Teams 通知） | Microsoft Teams Incoming Webhook 地址（通过 Teams 的 Workflows 应用创建，不是已经下线的旧版 Connector） |
| `meridian-argus.dashboard.enabled` | boolean | **`false`** | 是否注册只读 Dashboard 的 `DashboardController`。同样**默认关闭**——会暴露一个 HTTP 端点，不应该因为模块在 classpath 上就自动生效。要求应用是 Servlet 型 Web 应用（不能是 WebFlux 或非 Web 应用）。**没有单独的 `dashboard.path` 配置项**——读取的就是 `meridian-argus.metrics.sqlite.path` 指向的同一个文件，因为它本质上是 SQLite 数据的只读视图 |

可以同时配置多个阈值（比如"每小时 $50"和"每天 $500"两条都要监控），每条都有独立的滚动窗口状态，互不影响。

### Bean 装配逻辑一览

| Bean | 装配条件 | 说明 |
|---|---|---|
| `LLMObservabilityRegistry` | `meridian-argus.enabled=true`（默认） | 全局单例，所有 Provider 适配器和 exporter 共享同一个实例 |
| `PricingConfig` | 同上 | 优先读取 `pricing-config-path`，未配置则用内置价格表 |
| `PricingEngine` | 同上 | 包装 `PricingConfig`，提供成本计算能力 |
| `MicrometerMetricsExporter` | `meridian-argus.enabled=true` **且** `meridian-argus.metrics.micrometer.enabled=true`（默认）**且** classpath 上有 `MeterRegistry` 和 `MicrometerMetricsExporter` 类 | 会自动 `register` 到 `LLMObservabilityRegistry` |
| `OpenTelemetryTraceExporter` | `meridian-argus.enabled=true` **且** `meridian-argus.metrics.otel.enabled=true`（默认）**且** classpath 上有 `OpenTelemetry`/`OpenTelemetryTraceExporter` 类 **且** 容器里存在 `OpenTelemetry` Bean | 会自动 `register` 到 `LLMObservabilityRegistry`；跟 Micrometer 一样，真正决定"激不激活"的是有没有 `OpenTelemetry` Bean，而不是这个开关本身 |
| `LocalStorageExporter` | `meridian-argus.enabled=true` **且** `meridian-argus.metrics.sqlite.enabled=true`（**默认 false，需要显式开启**）**且** classpath 上有 `LocalStorageExporter` 和 `org.sqlite.JDBC` 类 | 会自动 `register` 到 `LLMObservabilityRegistry`；应用关闭时自动调用 `close()` |
| `RollingWindowAlertEvaluator` | `meridian-argus.enabled=true` **且** `meridian-argus.alerting.enabled=true`（**默认 false，需要显式开启**）**且** classpath 上有 `RollingWindowAlertEvaluator` 类 **且** 至少配置了一个 threshold | 会自动 `register` 到 `LLMObservabilityRegistry`；同时会自动收集容器里所有自定义的 `AlertNotifier` Bean，和配置里生成的 Slack/Teams 通知器合并使用 |
| `DashboardController` | `meridian-argus.enabled=true` **且** `meridian-argus.dashboard.enabled=true`（**默认 false，需要显式开启**）**且** classpath 上有 `DashboardController`/`UsageQuery` 类 **且** 是 Servlet 型 Web 应用 | 不注册到 `LLMObservabilityRegistry`（这是只读查询，不是 exporter）；作为普通 Spring MVC Bean 提供 `/meridian-argus/dashboard` 页面和对应 JSON API |

以上所有 Bean 都用 `@ConditionalOnMissingBean` 修饰——如果你自己定义了同类型的 Bean（比如想自定义 `PricingEngine` 的构造方式），会优先使用你自己的定义，Starter 不会覆盖。

### 编程方式覆盖配置

如果 YAML 配置不够灵活（比如价格表需要动态生成），可以直接自己定义 Bean 覆盖自动装配：

```java
@Configuration
public class CustomPricingConfig {

    @Bean
    public PricingConfig meridianArgusPricingConfig() {
        // 完全自定义的加载逻辑，Starter 的自动装配会因为 @ConditionalOnMissingBean 而让位
        return PricingConfig.loadFromStream(myCustomYamlInputStream());
    }
}
```

## 价格表（Pricing Config）说明

价格表是一份 YAML 文件，结构如下（单价单位：**美元 / 每千 token**）：

```yaml
providers:
  <provider-name>:
    <model-name>:
      input: 0.0025    # 输入（prompt）token 单价
      output: 0.01     # 输出（completion）token 单价
```

- `provider-name` 必须跟适配器里的 `PROVIDER_NAME` 常量一致（`openai`、`azure-openai`、`bedrock`、`vertex-ai`、`anthropic`）
- `model-name` 必须跟你在构造 `*CallWrapper` 时传入的模型名/deployment 名/modelId 完全一致（区分大小写）
- 找不到对应条目时，`CostEstimate.isPriceKnown()` 会返回 `false`，Micrometer exporter 会跳过 `meridian_argus.cost.usd` 这个指标的上报（**不会**上报一个误导性的 0）

内置价格表位置：[`pricing-config/default-pricing.yaml`](../pricing-config/default-pricing.yaml)（同步打包在 `meridian-argus-core` 的 `src/main/resources/default-pricing.yaml`，两处需要保持一致，PR 时请同时更新）。

### 自定义 / 追加价格表

如果内置价格表里没有你用的模型，两种方式：

1. **完全替换**：写一份完整的 YAML，通过 `meridian-argus.pricing-config-path` 指向它
2. **在内置基础上追加**：目前 `PricingConfig` 没有提供"合并"能力，需要自己复制一份内置价格表内容再追加缺失的模型条目

价格数据是社区共同维护的，欢迎直接给 `pricing-config/default-pricing.yaml` 提 PR（见 [CONTRIBUTING.md](../CONTRIBUTING.md)）。

## Micrometer 导出指标清单

所有指标都带有 `provider` 和 `model` 两个 tag。

| 指标名 | 类型 | 额外 tag | 说明 |
|---|---|---|---|
| `meridian_argus.calls` | Counter | `success`（`true`/`false`） | 调用次数，可用于计算调用量和错误率 |
| `meridian_argus.tokens.input` | Counter | — | 累计输入 token 数 |
| `meridian_argus.tokens.output` | Counter | — | 累计输出 token 数 |
| `meridian_argus.cost.usd` | Counter | — | 累计预估成本（美元）。仅在价格表能查到对应模型时上报 |
| `meridian_argus.latency` | Timer（含 percentile histogram） | — | 调用延迟，已开启 `publishPercentileHistogram()`，可以直接用 `histogram_quantile()` 查询 p95/p99 |

Prometheus 查询示例：

```promql
# 按 provider/model 统计每分钟调用量
sum(rate(meridian_argus_calls_total[1m])) by (provider, model)

# 错误率
sum(rate(meridian_argus_calls_total{success="false"}[5m])) by (provider, model)
/ sum(rate(meridian_argus_calls_total[5m])) by (provider, model)

# 每小时预估花费
sum(rate(meridian_argus_cost_usd_total[1h])) by (provider, model) * 3600

# p95 延迟
histogram_quantile(0.95, sum(rate(meridian_argus_latency_seconds_bucket[5m])) by (le, provider, model))
```

> 注意：Micrometer 的 Prometheus 命名规则会把 `.` 换成 `_`，Counter 会自动加 `_total` 后缀，所以 `meridian_argus.calls`（代码里的名字）对应到 Prometheus 里是 `meridian_argus_calls_total`。

## Grafana Dashboard

项目自带一份现成的 Dashboard JSON：[`docs/grafana-dashboard.json`](grafana-dashboard.json)，包含调用量、错误率、预估花费、p95 延迟、Token 吞吐量五个面板，直接在 Grafana 里 Import 即可使用（需要先把 Prometheus 配置成对应的数据源）。

## OpenTelemetry 链路追踪导出

`meridian-argus-otel` 把每次 LLM 调用导出成一条 OpenTelemetry Span，用的是 OpenTelemetry 官方的 GenAI 语义约定（`gen_ai.*` 系列属性），可以直接接入你现有的分布式追踪体系（Jaeger、Tempo、Datadog APM 等），跟请求链路上的其他 Span 放在一起看。

### 启用方式（Spring Boot）

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-otel</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**这个模块不需要你额外配置任何东西**——只要你的应用里已经有一个 `OpenTelemetry` 类型的 Bean（不管是通过 `opentelemetry-spring-boot-starter`、`micrometer-tracing-bridge-otel`，还是你自己手动配的 SDK），这个 Exporter 就会自动接上去。跟 Micrometer 一样，`meridian-argus.metrics.otel.enabled` 默认是 `true`，因为它本身不会主动做任何事——没有 `OpenTelemetry` Bean 就不会激活，不需要你手动关掉。

```yaml
meridian-argus:
  metrics:
    otel:
      enabled: false   # 如果你想临时关掉这一路导出（但保留依赖），可以显式设为 false
```

**这个模块不负责创建或配置 OpenTelemetry SDK 本身**——采样策略、导出目的地（OTLP endpoint）、Resource 属性这些都是你自己的 SDK 配置负责的事，`meridian-argus-otel` 只是往你已经搭好的追踪链路上加 Span。

### 每个 Span 包含的属性

| 属性 | 说明 |
|---|---|
| `gen_ai.provider.name` | Provider 名称。`bedrock` 会被映射成 OTel 官方文档明确给出的 `aws.bedrock`；`openai` 本身就跟规范一致；`azure-openai`、`vertex-ai`、`anthropic` 目前是直接透传我们自己的命名，**没有找到权威来源核实这三个的官方枚举值**，如果你的后端对 provider 名称做了特殊渲染逻辑，遇到显示不对的情况可以对照 [属性注册表](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/registry/attributes/gen-ai.md) 检查 |
| `gen_ai.operation.name` | 固定为 `chat` |
| `gen_ai.request.model` | 模型名 |
| `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` | Token 用量 |
| `error.type` | 调用失败时的异常类型（成功调用不会有这个属性） |
| `meridian_argus.cost_known` / `meridian_argus.cost_usd` | 预估成本。价格未知时**完全不会设置** `cost_usd` 这个属性，而不是给一个误导性的 0 |
| `meridian_argus.caller_id` | 如果 Provider 适配器构造时传了 `callerId`，会带出来 |

Span 名称是 `chat <model>`（比如 `chat gpt-4o`），覆盖的时间范围是调用的真实起止时间（`LLMCallRecord` 里记录的 `startedAt` + `latency`），而不是这个 Exporter 实际执行的时刻——如果 `LLMObservabilityRegistry` 上还注册了其他 Exporter 排在它前面，执行时刻可能会有轻微滞后，但 Span 的时间戳不受这个影响。

### ⚠️ 语义约定还在演进中

OpenTelemetry 的 GenAI 语义约定目前还处于 **Development（未稳定）** 阶段，属性名之前就变过一次——`gen_ai.system` 在 2025 年 8 月的 v1.37.0 版本里被重命名成了现在的 `gen_ai.provider.name`。如果你发现产出的 Span 跟你后端预期的不一致，先去看一下 [GenAI 语义约定仓库](https://github.com/open-telemetry/semantic-conventions-genai) 有没有更新，不要默认是这个模块的 bug。

### 明确不做的事

这个模块**不采集 Prompt/Response 的实际文本内容**——官方规范里明确建议把这类内容放在 Span *事件*（events）里而不是属性（attributes）上（事件可以在 Collector 层面被过滤/丢弃，属性会被无条件索引且有大小限制）。要支持这个需要单独的设计（默认关闭、脱敏规则等），不适合直接塞进这个 Exporter，属于路线图里单独列出的后续项。

## 本地 SQLite Exporter

不是所有团队都跑 Prometheus。`meridian-argus-metrics-sqlite` 提供一个零基础设施的替代方案：把每条调用记录 + 计价结果直接写进一个单文件 SQLite 数据库，不需要额外起服务。

### 启用方式（Spring Boot）

引入模块：

```xml
<dependency>
    <groupId>com.prckjw.meridianargus</groupId>
    <artifactId>meridian-argus-metrics-sqlite</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

在配置里显式开启（**这个跟 Micrometer 不一样，默认是关闭的**——往磁盘写文件是一个需要主动选择的副作用，不应该因为依赖恰好在 classpath 上就自动生效）：

```yaml
meridian-argus:
  metrics:
    sqlite:
      enabled: true
      path: meridian-argus.db   # 相对或绝对路径都可以，父目录会自动创建，默认就是这个文件名
```

装配完成后，`LocalStorageExporter` 会作为 Bean 自动注册到 `LLMObservabilityRegistry`，应用关闭时也会自动调用它的 `close()` 方法（Spring 的 `destroyMethod` 机制），不需要手动管理连接生命周期。

它和 Micrometer Exporter 可以同时启用，互不冲突（`LLMObservabilityRegistry` 本来就是往所有注册的 Exporter 扇出）。

### 数据表结构

```sql
CREATE TABLE llm_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    started_at TEXT NOT NULL,      -- ISO-8601，Instant#toString()
    latency_ms INTEGER NOT NULL,
    success INTEGER NOT NULL,       -- 0/1
    error_type TEXT,
    caller_id TEXT,
    input_tokens INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,
    price_known INTEGER NOT NULL,   -- 0/1，价格表里查不到时为 0
    input_cost_usd TEXT,
    output_cost_usd TEXT,
    total_cost_usd TEXT
);
```

价格未知时 `total_cost_usd` 等字段是 `NULL`，不是 `0`——跟项目里其他地方"未知不等于零"的约定保持一致。可以直接用 `sqlite3` 命令行工具或者任何 SQLite 客户端打开这个文件查询。

### 查询辅助类

不想自己写 SQL 的话，`UsageQuery` 封装了几个常见问题：

```java
UsageQuery query = new UsageQuery(Path.of("meridian-argus.db"));

// 按 provider/model 分组的调用量、错误数、token 用量、预估花费
List<ProviderModelCostSummary> lastHour = query.costByProviderModel(Duration.ofHours(1));

// 全部 provider/model 汇总的总花费
BigDecimal totalLast24h = query.totalCost(Duration.ofHours(24));

// 调用总数（含失败的）
long callsLastWeek = query.callCount(Duration.ofDays(7));
```

`UsageQuery` 每次调用都会开一个短连接，定位是"偶尔查一下"的场景（比如定时报表、小工具），不是高频热路径查询；如果你需要更高频率的查询，建议直接拿 SQLite 文件自己管理连接池。

它跟正在写入的 `LocalStorageExporter` 可以安全地同时使用——数据库开启时用了 `PRAGMA journal_mode=WAL`，读不会被写阻塞。

## 告警：滚动窗口阈值 + Webhook 通知

`meridian-argus-alerting` 提供了一个纯内存的滚动窗口告警评估器，配合 Slack/Teams Webhook 通知器，用来回答"最近 N 小时/天花超过 $X 了吗"这类问题。

### 启用方式（Spring Boot）

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

跟 SQLite Exporter 一样，这个也是**默认关闭**的——会主动往外部发 HTTP 请求，不应该因为依赖在 classpath 上就自动生效。可以同时配置多条阈值，每条独立计算，互不影响。

### 通知语义：只在状态跳变时通知一次

评估器不是"每次调用都检查一遍、超了就发一条消息"，而是只在**状态发生跳变**的那一刻通知：
- 滚动窗口花费第一次超过阈值 → 发一条 `TRIGGERED` 通知
- 之后哪怕又调用了 100 次、持续超支，也不会再重复通知
- 直到窗口内的旧记录过期、花费降回阈值以下 → 发一条 `RECOVERED` 通知

这是有意为之的设计——避免一次持续的成本尖峰导致某个人的手机被同一个告警刷屏。如果你需要"持续超支期间每隔一段时间提醒一次"这种效果，可以在自己实现的 `AlertNotifier` 里自己记录上次通知时间来做。

### 自定义 Notifier

除了内置的 Slack/Teams，你可以直接定义自己的 `AlertNotifier` Bean（比如接 PagerDuty、发邮件、写自己的日志系统），Starter 会自动收集容器里所有 `AlertNotifier` 类型的 Bean，和配置生成的 Slack/Teams 通知器合并使用，不需要手动接线：

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

### 不使用 Spring Boot 的手动接入

```java
AlertThreshold hourly = new AlertThreshold("hourly-budget", new BigDecimal("50.00"), Duration.ofHours(1));

List<AlertNotifier> notifiers = List.of(
    new SlackWebhookNotifier(URI.create("https://hooks.slack.com/services/xxx/yyy/zzz"))
);

RollingWindowAlertEvaluator evaluator = new RollingWindowAlertEvaluator(pricingEngine, List.of(hourly), notifiers);
registry.register(evaluator);

// 随时查询某个阈值当前的滚动窗口花费
BigDecimal current = evaluator.currentSpend("hourly-budget");
```

### 已知限制

- 纯内存实现，应用重启后窗口状态会清零（不影响正确性，只是重启后需要重新累积到阈值才会再次触发）
- 没有条目数量上限，只按时间窗口剪裁——如果窗口设置得很长（比如 30 天）且调用量很大，内存占用会持续增长，目前没有做额外的容量控制
- Teams 的 Webhook 格式这几年变动比较大（微软已经把旧版 Office 365 Connector 下线，改成了 Workflows + Adaptive Card），如果发送失败，建议对照 [微软官方文档](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook) 确认你的 Webhook 是通过 Workflows 应用创建的

## 只读 Web Dashboard

`meridian-argus-web-dashboard` 提供一个零依赖的只读 Dashboard 页面，给不想搭 Grafana 的团队用。它读取的是 `meridian-argus-metrics-sqlite` 写入的同一个 SQLite 文件，纯展示，不写入任何数据。

### 启用方式（Spring Boot）

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
      enabled: true              # Dashboard 要有数据可看，通常需要同时开启 SQLite Exporter
      path: meridian-argus.db
  dashboard:
    enabled: true
```

启动应用后访问 `http://localhost:<port>/meridian-argus/dashboard`，就能看到一个包含总花费、总调用数、错误数、按 Provider/Model 拆分的表格的页面，页面自带一个下拉框可以切换"最近1小时/24小时/7天/30天"的统计窗口，每 30 秒自动刷新一次。

**这个模块没有单独的 `dashboard.path` 配置项**——读取的就是 `meridian-argus.metrics.sqlite.path` 指向的同一个文件。理论上你也可以只开 Dashboard、不开 SQLite Exporter 本身（比如这个文件是另一个进程在写），但正常场景下这两个配置项应该一起打开。

### 技术实现说明

- **零前端构建依赖**：整个页面就是打包在 jar 里的一份静态 HTML 文件（内联 CSS + 原生 JS），没有 npm/webpack 构建步骤，也不依赖任何 CDN 资源，完全离线也能正常渲染
- **只读**：Controller 只查询数据，不接入 `LLMObservabilityRegistry`（跟其他 Exporter 不一样，它不是一个"记录接收方"）
- **JSON API**：如果你想自己拿数据做别的展示，可以直接调 `GET /meridian-argus/dashboard/api/summary?windowHours=24` 拿到结构化数据，不需要解析 HTML

### 依赖说明

这个模块依赖的是 `spring-webmvc`（注解和 MVC 分发相关的类），而不是 `spring-boot-starter-webmvc`——因为你的应用本来就应该已经有自己的内嵌 Web 服务器了，这个模块不会再额外带一个进来。也因此，Dashboard 要求宿主应用是**Servlet 型**的 Spring MVC 应用（不支持 WebFlux 响应式应用，也不支持完全没有 Web 层的应用），这一点通过 `@ConditionalOnWebApplication(type = SERVLET)` 保证——不满足条件时会安静地跳过装配，不会报错。

## 自定义 Exporter

如果 Micrometer/Prometheus 不是你的技术栈，可以自己实现导出器：

```java
public class MyCustomExporter implements LLMCallInterceptor {
    @Override
    public void onCallCompleted(LLMCallRecord record) {
        // 自己的逻辑：写数据库、发 webhook、上报到 Datadog/CloudWatch 等
    }
}
```

如果需要用到成本计算（`CostEstimate`），建议继承 `AbstractMetricsExporter`（在 `meridian-argus-core` 里），它已经封装好了「先算成本再回调 `export(record, cost)`」的模板逻辑：

```java
public class MyCustomExporter extends AbstractMetricsExporter {
    public MyCustomExporter(PricingEngine pricingEngine) {
        super(pricingEngine);
    }

    @Override
    public void export(LLMCallRecord record, CostEstimate cost) {
        // record 是原始调用记录，cost 是已经算好的预估成本
    }
}
```

写好之后，手动注册到 registry 即可（Spring 环境下自己定义一个 `@Bean` 并注入 `LLMObservabilityRegistry` 后调用 `register(...)`）。

## 常见问题

**Q: 跑 demo 模块时报 `Could not find artifact com.prckjw.meridianargus:...:jar:0.1.0-SNAPSHOT` / `Could not resolve dependencies`？**
A: 这是最常见的多模块 Maven 坑——说明你是在 `meridian-argus-examples/spring-boot-openai-demo` 子目录里单独执行的 `mvn`（或者 IDE 里只把这个子文件夹当独立项目打开了），而不是从仓库根目录发起构建。demo 模块依赖的 `meridian-argus-spring-boot-starter`、`meridian-argus-provider-openai` 这些兄弟模块都是 `0.1.0-SNAPSHOT`，只以源码形式存在，从没发布到任何仓库，Maven Central 自然也解析不出来。

正确做法是从**仓库根目录**发起构建，用 `-am`（also-make）让 Maven 在同一个 reactor 里把依赖的兄弟模块一起编译：

```bash
cd meridian-argus   # 根目录，不是 demo 子目录
mvn -pl meridian-argus-examples/spring-boot-openai-demo -am org.springframework.boot:spring-boot-maven-plugin:run
```

或者先在根目录跑一次 `mvn install -DskipTests` 把全部模块装进本地 `~/.m2` 仓库，之后就可以在任意子模块目录里单独跑 `mvn org.springframework.boot:spring-boot-maven-plugin:run` 了。IDE 场景同理：要把**根目录**的 `pom.xml` 作为整个项目导入，而不是单独打开 demo 子文件夹。

**Q: 为什么 `/actuator/prometheus` 里看不到 `meridian_argus.*` 指标？**
依次检查：
1. classpath 上是否有 `micrometer-registry-prometheus`
2. `management.endpoints.web.exposure.include` 是否包含 `prometheus`
3. `meridian-argus.enabled` 和 `meridian-argus.metrics.micrometer.enabled` 是否都是 `true`（默认都是）
4. 是否真的通过 `*CallWrapper.call(...)` 发起了调用——直接调用原始 SDK 不经过 wrapper 是不会被记录的

**Q: 为什么某次调用没有成本数据？**
说明这个 `provider`/`model` 组合在价格表里找不到对应条目。检查模型名拼写是否跟价格表 key 完全一致（区分大小写），或者补充自定义价格表。

**Q: Azure 的 deployment 名跟标准模型名不一样怎么办？**
在自定义价格表里，把 `azure-openai` 下的 model key 改成你实际的 deployment 名即可，价格数值可以照抄对应模型的官方单价。

**Q: 一个应用可以同时接入多个 Provider 吗？**
可以，也是设计初衷。每个 Provider 用各自的 `*CallWrapper`，共享同一个 `LLMObservabilityRegistry` 即可，指标会自动按 `provider` tag 区分。

**Q: `callerId` 有什么用？**
目前只会保留在 `LLMCallRecord` 对象上（供你自己的自定义 exporter 使用），**不会**作为 Micrometer 指标的 tag，避免高基数 tag（比如按用户 ID）导致 Prometheus 存储膨胀。如果你确实需要按用户/租户维度统计，建议基于 `LLMCallInterceptor` 自己写一个导出到日志/数据库的 exporter，而不是塞进 Prometheus tag。
