# MeridianArgus

**面向 Java 多服务商 LLM 调用的统一可观测性与成本治理 SDK。**

[English](README.md) | 简体中文

用一套统一的视角查看 token 用量、延迟、错误率和预估成本——覆盖 Azure OpenAI、OpenAI、AWS Bedrock、Google Vertex AI、Anthropic——不需要为团队用到的每个服务商单独搭一套监控面板。

[![CI](https://github.com/prckjw/meridian-argus/actions/workflows/ci.yml/badge.svg)](https://github.com/prckjw/meridian-argus/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/prckjw/meridian-argus/branch/main/graph/badge.svg)](https://codecov.io/gh/prckjw/meridian-argus)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/)

---

## 为什么做这个

现在大部分做 LLM 应用的团队，不管是不是有意为之，实际上都是多服务商并用的状态——企业合同用 Azure OpenAI，原型验证用官方 OpenAI Key，可能因为其他业务都在 AWS 上索性也用 Bedrock。每家 SDK 上报用量的方式都不一样，谁都不会在账单到来之前告诉你到底花了多少钱，而且 Java 生态里也没有一个像 Langfuse 或 Helicone 那样能在五分钟内接入 Spring Boot 应用的原生工具。

MeridianArgus 补的就是这一块空白：一个与具体服务商无关的轻量核心 + 各家的薄适配层，通过一个 Spring Boot Starter 接入你的应用。

## 它做了什么

- **归一化**：把各服务商不同形态的 token 用量、延迟、成功/失败状态统一成一种记录结构（`LLMCallRecord`）
- **计价**：基于一份可配置、社区共同维护的价格表（每千 token 的美元单价），对每次调用估算成本
- **导出**：开箱即用地对接 Micrometer/Prometheus，并附带一份现成的 Grafana Dashboard
- **零业务耦合**：只是包装你已经在用的 SDK 调用，业务代码几乎不需要改动

## 快速开始

引入 Starter 和你需要的服务商适配器：

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

把你已有的调用包一层：

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

就这么简单——不需要加注解，也不需要跑一个独立的 agent 进程。只要 classpath 上有 `micrometer-registry-prometheus`，指标就会自动出现在 `/actuator/prometheus`。

可运行的端到端示例见 [`meridian-argus-examples/spring-boot-openai-demo`](meridian-argus-examples/spring-boot-openai-demo)（不需要真实 API Key——它模拟了 SDK 调用，让你能立刻看到完整的数据链路）。

## 架构

```
              你的应用代码
                       │
        用 provider 适配器包装一次调用
                       │
     ┌────────────┬────────────┬────────────┬────────────┬────────────┐
 OpenAICall   AzureOpenAI   Bedrock      VertexAI    Anthropic
 Wrapper      CallWrapper   CallWrapper  CallWrapper  CallWrapper
     │            │            │            │            │
     └────────────┴────────────┴────────────┴────────────┘
                       │  发布 LLMCallRecord
                       ▼
            LLMObservabilityRegistry（扇出分发）
                       │
      ┌────────────────┼─────────────────────┐
      ▼                ▼                      ▼
PricingEngine   MicrometerMetricsExporter   （你自己的 exporter）
      │                │
 （美元预估）      Prometheus / Grafana
```

完整设计说明见 [`docs/architecture.md`](docs/architecture.md)（英文）。完整使用说明及参数配置参考见 [`docs/usage-guide.zh-CN.md`](docs/usage-guide.zh-CN.md)。

## Provider 支持矩阵

| Provider | 状态 | 模块 |
|---|---|---|
| OpenAI | ✅ 已支持 | `meridian-argus-provider-openai` |
| Azure OpenAI | ✅ 已支持 | `meridian-argus-provider-azure-openai` |
| AWS Bedrock | ✅ 已支持 | `meridian-argus-provider-bedrock` |
| Google Vertex AI | ✅ 已支持 | `meridian-argus-provider-vertex` |
| Anthropic | ✅ 已支持 | `meridian-argus-provider-anthropic` |

这五家在 [`pricing-config/default-pricing.yaml`](pricing-config/default-pricing.yaml) 中都有对应的价格条目，默认模型列表开箱即可估算成本。Bedrock 和 Anthropic 都能调用 Claude 模型——请根据你实际的接入方式（直连官方 API，还是走 AWS 托管）选择对应的适配器，因为两者计价方式不同。

## 模块说明

| 模块 | 作用 |
|---|---|
| `meridian-argus-core` | 与具体服务商无关的模型定义、拦截器 SPI、计价引擎——不依赖任何框架 |
| `meridian-argus-provider-*` | 各服务商官方 SDK 的薄封装层 |
| `meridian-argus-metrics-micrometer` | 把记录导出为 Micrometer 指标 |
| `meridian-argus-metrics-sqlite` | 零基础设施的本地存储：把记录写入单文件 SQLite 数据库，并提供"最近 N 小时花了多少钱"这类常见问题的查询辅助类 |
| `meridian-argus-alerting` | 滚动窗口成本阈值评估，附带 Slack 和 Microsoft Teams 的 Webhook 通知器 |
| `meridian-argus-web-dashboard` | 可选的只读 Dashboard（零依赖静态 HTML 页面 + JSON API），数据来源是 SQLite 数据库 |
| `meridian-argus-otel` | 用 OpenTelemetry GenAI 语义约定，把每次调用导出成一条 Span，接入应用已有的链路追踪体系 |
| `meridian-argus-spring-boot-starter` | 自动装配，把上述所有组件接成 Spring Bean |
| `meridian-argus-examples/*` | 可直接运行的示例应用 |

## 设计原则

- **编译期不锁定具体 SDK 版本。** Provider 适配器只接收一个调用用的 `Supplier` 和一个用量提取用的 `Function`，因此可以针对你项目里已有的任意 SDK 版本编译，不会引入可能冲突的传递依赖版本。
- **绝不影响业务调用。** Exporter 出错会被捕获并记录日志，绝不会向外抛出——可观测性代码不应该拖垮它本该监控的业务请求。
- **价格未知就明确说明，而不是默默按 0 处理。** 如果某个服务商/模型不在价格表里，`CostEstimate.isPriceKnown()` 会返回 `false`，而不是给出一个误导性的 $0.00。
- **本地优先。** 除非你主动配置了某个会外发数据的 exporter，否则数据不会离开你的基础设施。项目本身不会向任何地方回传遥测数据。

## 项目状态

早期阶段（`0.1.0-SNAPSHOT`）。核心流程、全部五个 Provider 适配器（OpenAI、Azure OpenAI、Bedrock、Vertex AI、Anthropic）以及 Micrometer 导出均已实现。本地 SQLite 导出器和可选的 Web Dashboard 在路线图上——完整计划见 [`docs/architecture.md`](docs/architecture.md)（英文），下一轮具体要做什么见 [`docs/iteration-plan.zh-CN.md`](docs/iteration-plan.zh-CN.md)。目前这些针对具体 SDK 的适配器代码还没有在真实网络环境下跑通编译验证（开发环境无法访问 Maven Central）——如果你在使用的 SDK 版本上遇到编译不匹配的问题，欢迎提 Issue 或 PR。也非常欢迎大家帮忙维护价格表。

## 参与贡献

服务商价格经常变动——更新 [`pricing-config/default-pricing.yaml`](pricing-config/default-pricing.yaml) 并附上数据来源链接，是最简单也最有价值的贡献方式之一。完整指南见 [`CONTRIBUTING.md`](CONTRIBUTING.md)（英文）。

## 许可证

[Apache License 2.0](LICENSE)
