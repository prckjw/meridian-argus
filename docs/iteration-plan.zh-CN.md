# 下一轮迭代计划

本文档记录 MeridianArgus 下一轮迭代（建议按 2 周为一个周期）的具体范围，区别于 [`architecture.md`](architecture.md) 里更长线的整体路线图——那份文档回答"这个项目最终要长成什么样"，这份文档回答"接下来两周具体做什么"。

---

## 上一轮的收尾情况

- ✅ 五个 Provider 适配器（OpenAI / Azure OpenAI / Bedrock / Vertex AI / Anthropic）全部落地，各自有单元测试
- ✅ Micrometer 导出器可选依赖装配的 bug 已修复（`MeridianArgusMicrometerAutoConfiguration` 独立拆分）
- ✅ Demo 模块补了端到端冒烟测试
- ⚠️ **还没有人在真实网络环境下跑通过一次完整的 `mvn -B verify`**——这是本轮迭代必须清零的技术债，优先级最高

---

## P0：先决条件（本轮迭代开始前必须做完）

### 1. 跑通一次真实的 `mvn -B verify`
这个仓库里所有代码目前都是"静态审查通过但从未真机编译"的状态（开发环境没有 Maven Central 访问权限）。在动手做任何新功能之前，先确认：

```bash
git clone https://github.com/prckjw/meridian-argus.git
cd meridian-argus
mvn -B verify
```

如果有编译错误或测试失败，作为迭代的第一个任务修掉，再谈别的。**这是阻塞项，不是可选项**——继续在一个从没编译过的代码库上叠加新功能，风险会越滚越大。

---

## P1：本轮迭代范围（高价值、成本可控）

### 2. 发布第一个真实版本到 Maven Central（或至少 GitHub Packages）
现在项目只有 `0.1.0-SNAPSHOT`，任何人想用都得 `git clone` + 本地 `mvn install`，这跟"发布一个开源 SDK"的目标还有距离。

- 最小目标：把 `0.1.0` 正式发布到 Maven Central（`release.yml` 这个 workflow 已经搭好了骨架，需要配置 Sonatype 账号 + GPG 签名密钥）
- 如果 Maven Central 的审核/签名流程一时搞不定，退而求其次先发到 GitHub Packages，让 README 里的安装说明是真的能直接 `<dependency>` 引用，而不是只能源码构建

### 3. 补 CI 的代码覆盖率报告 —— ✅ 已完成
目前测试覆盖率是靠人工数文件、数行数评估的，没有量化指标。建议：
- ✅ 根 `pom.xml` 加了 `jacoco-maven-plugin`（放在 `<build><plugins>` 而不只是 `pluginManagement`，这样所有子模块自动继承执行，不用每个模块单独开）
- ✅ CI 里 `mvn -B verify` 跑完后，用 `codecov-action` 把所有模块的 `jacoco.xml` 合并上传（Codecov 自己支持多文件合并，不需要额外搭一个聚合模块）
- ✅ README 中英文都加了徽章
- ⚠️ **需要人工操作**：徽章目前指向的是标准 Codecov URL 格式，要仓库 owner 去 [codecov.io](https://codecov.io) 用 GitHub 账号登录、找到这个仓库启用一次，并在 GitHub 仓库的 Secrets 里加一个 `CODECOV_TOKEN`，徽章才会显示真实数字（在这之前会显示"unknown"）

### 4. 本地 SQLite Exporter（`docs/architecture.md` 里 v0.2 遗留项）—— ✅ 已完成

不是所有团队都跑 Prometheus，很多小团队/个人项目更想要一个"零依赖、开箱即用"的存储方案。

- ✅ 新建 `meridian-argus-metrics-sqlite` 模块，用 SQLite（`org.xerial:sqlite-jdbc`，无需额外服务）落盘每条 `LLMCallRecord` + 计价结果
- ✅ 提供了 `UsageQuery` 查询辅助类（按 provider/model 分组的用量与花费、总花费、调用数），不需要用户自己写 SQL
- ✅ Spring Boot Starter 里补了 `MeridianArgusSqliteAutoConfiguration`，严格照搬 Micrometer 那次 bug 修复的模式（独立 `@AutoConfiguration` 类 + 字符串形式 `@ConditionalOnClass`），默认关闭，需要显式 `meridian-argus.metrics.sqlite.enabled=true` 才会装配
- ✅ 中英文使用说明都补了对应章节

这个功能补完之后，"不跑 Prometheus 也能用"这个故事就完整了，也是路线图里明确写着要做的下一项。

---

## P2：视 P1 完成情况决定是否本轮启动

### 5. 告警评估器 + Webhook 通知（`AlertThreshold` 落地）—— ✅ 已完成
`AlertThreshold` 这个数据类已经存在但完全没被使用（这次迭代刚给它补了基础测试）。真正有价值的是接上：
- ✅ 新建 `meridian-argus-alerting` 模块，`RollingWindowAlertEvaluator` 用纯内存滑动窗口实现，支持同时配置多条独立阈值
- ✅ `SlackWebhookNotifier`/`TeamsWebhookNotifier`，用 JDK 内置 `HttpClient`，没有额外引入 HTTP 库依赖；Teams 那边特意核实了微软这两年把旧版 Connector 下线、改成 Workflows + Adaptive Card 的变化，按新格式实现
- ✅ 只在状态跳变（触发/恢复）时通知一次，避免持续超支期间被同一个告警刷屏
- ✅ Spring Boot Starter 集成，支持用户自定义 `AlertNotifier` Bean 自动汇入，跟配置生成的 Slack/Teams 通知器合并使用
- ✅ 中英文使用说明都补了完整章节

### 6. 项目元数据小修 —— ✅ 已完成
- ✅ `artifactId` 从 `meridian-argus-example-spring-boot-openai-demo` 改成 `meridian-argus-examples-spring-boot-openai-demo`，跟文件夹名 `meridian-argus-examples`（复数）保持一致
- ✅ 根 `pom.xml` 里 `<developers>`、`<scm>` 这两个 Maven Central 发布必需的字段之前丢失了（不确定是哪次改动漏掉的），已经补回去；`<url>`、`<licenses>` 本来就在

---

## P3：明确往后放（本轮不做，但记录在案避免遗忘）

- ~~**Web Dashboard**（`meridian-argus-web-dashboard`）：工作量较大（前端 + 后端都要），且 Grafana Dashboard 已经能覆盖大部分可视化需求，优先级往后放~~ ✅ 已完成——用零依赖的单文件静态 HTML + 原生 JS 实现（不引入 npm/webpack 构建链），后端读取 `meridian-argus-metrics-sqlite` 写入的同一个 SQLite 文件，只读、不写入。依赖上特意选了 `spring-webmvc` 而不是 `spring-boot-starter-webmvc`，避免给宿主应用重复带一个内嵌服务器
- ~~**OpenTelemetry 导出**：等 Micrometer 路径稳定跑一段时间、收集到真实反馈后再做，避免同时维护两套导出机制~~ ✅ 已完成——这项是在你明确要求"直接开工"之后提前做的，没有真的等到 Micrometer 路径收集完反馈。好在两套导出机制（`meridian-argus-metrics-micrometer` 和 `meridian-argus-otel`）可以同时启用、互不冲突，所以提前做也不影响 Micrometer 那条路径本身。属性用的是 `meridian-argus.metrics.otel.enabled`（跟 micrometer/sqlite 同级），默认 `true`（因为它本身不产生任何副作用，只有真的存在 `OpenTelemetry` Bean 时才会激活）
- **Prompt/Response 内容采集**：涉及数据脱敏、合规，需要更谨慎的设计讨论（比如默认关闭、脱敏规则怎么配置），不适合仓促上马

---

## 建议的验收标准（Definition of Done）

本轮迭代结束时，希望能做到：

- [ ] `mvn -B verify` 在全新 clone 的仓库上一次跑通，无编译错误、无测试失败 —— **仍未完成，需要你本地验证**（开发环境访问不了 Maven Central）
- [ ] Maven Central（或 GitHub Packages）上能搜到 `com.prckjw.meridianargus:meridian-argus-spring-boot-starter:0.1.0` —— **仍未完成，需要真实 Sonatype 账号 + GPG 签名密钥**
- [x] CI 输出覆盖率报告，README 有徽章
- [x] SQLite Exporter 有对应的模块、测试，以及 `usage-guide.md`/`usage-guide.zh-CN.md` 里的使用说明
- [x] 中英文文档保持同步更新
- [x]（追加完成，原计划外）告警评估器 + Webhook 通知落地

**当前状态**：P1/P2 里所有能靠写代码完成的项都做完了。剩下的两项（P0 编译验证、Maven Central 发布）都需要人工操作——前者需要你在有 Maven Central 网络访问权限的环境里跑一次 `mvn -B verify` 并把结果反馈给我；后者需要你去注册 Sonatype/Central Publisher 账号、生成 GPG 签名密钥，这些我没法替你完成。

---

如果人手/时间有限，最低限度也要完成 **P0（跑通编译）** —— 在这之前做的任何新功能都建立在一个未经验证的地基上。
