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

### 3. 补 CI 的代码覆盖率报告
目前测试覆盖率是靠人工数文件、数行数评估的，没有量化指标。建议：
- CI 里加 JaCoCo 插件，跑完 `mvn verify` 输出覆盖率报告
- README 加一个覆盖率徽章（Codecov 或类似服务）
- 这个改动本身成本很低（加一个 Maven 插件 + 一行 CI 配置），但能让"测试覆盖"这件事从"我们说做了"变成"有数字可查"

### 4. 本地 SQLite Exporter（`docs/architecture.md` 里 v0.2 遗留项）—— ✅ 已完成

不是所有团队都跑 Prometheus，很多小团队/个人项目更想要一个"零依赖、开箱即用"的存储方案。

- ✅ 新建 `meridian-argus-metrics-sqlite` 模块，用 SQLite（`org.xerial:sqlite-jdbc`，无需额外服务）落盘每条 `LLMCallRecord` + 计价结果
- ✅ 提供了 `UsageQuery` 查询辅助类（按 provider/model 分组的用量与花费、总花费、调用数），不需要用户自己写 SQL
- ✅ Spring Boot Starter 里补了 `MeridianArgusSqliteAutoConfiguration`，严格照搬 Micrometer 那次 bug 修复的模式（独立 `@AutoConfiguration` 类 + 字符串形式 `@ConditionalOnClass`），默认关闭，需要显式 `meridian-argus.metrics.sqlite.enabled=true` 才会装配
- ✅ 中英文使用说明都补了对应章节

这个功能补完之后，"不跑 Prometheus 也能用"这个故事就完整了，也是路线图里明确写着要做的下一项。

---

## P2：视 P1 完成情况决定是否本轮启动

### 5. 告警评估器 + Webhook 通知（`AlertThreshold` 落地）
`AlertThreshold` 这个数据类已经存在但完全没被使用（这次迭代刚给它补了基础测试）。真正有价值的是接上：
- 一个滚动窗口评估器（比如"过去 1 小时花费超过 $50 就告警"），可以先用简单的内存滑动窗口实现，不需要一上来就搞持久化状态
- 一个 Slack/Teams Webhook Notifier，触发阈值时发消息
- 这个模块依赖前面的存储方案（SQLite 或至少内存累计器）才能算出"过去 N 小时花了多少"，所以放在 SQLite Exporter 之后比较合理

### 6. 项目元数据小修（顺手做，不单独占用太多时间）
- `meridian-argus-example-spring-boot-openai-demo` 这个 artifactId 和文件夹名 `meridian-argus-examples`（复数）不一致，容易造成一点点困惑，可以顺手统一
- 检查 `pom.xml` 里的 `<url>`、`<scm>` 等 Maven Central 发布必需的元数据字段是否补全（发布到 Central 要求比较严格，这个正好跟第 2 项一起做）

---

## P3：明确往后放（本轮不做，但记录在案避免遗忘）

- **Web Dashboard**（`meridian-argus-web-dashboard`）：工作量较大（前端 + 后端都要），且 Grafana Dashboard 已经能覆盖大部分可视化需求，优先级往后放
- **OpenTelemetry 导出**：等 Micrometer 路径稳定跑一段时间、收集到真实反馈后再做，避免同时维护两套导出机制
- **Prompt/Response 内容采集**：涉及数据脱敏、合规，需要更谨慎的设计讨论（比如默认关闭、脱敏规则怎么配置），不适合仓促上马

---

## 建议的验收标准（Definition of Done）

本轮迭代结束时，希望能做到：

- [ ] `mvn -B verify` 在全新 clone 的仓库上一次跑通，无编译错误、无测试失败
- [ ] Maven Central（或 GitHub Packages）上能搜到 `com.prckjw.meridianargus:meridian-argus-spring-boot-starter:0.1.0`
- [ ] CI 输出覆盖率报告，README 有徽章
- [ ] SQLite Exporter 有对应的模块、测试，以及 `usage-guide.md`/`usage-guide.zh-CN.md` 里的使用说明
- [ ] 中英文文档保持同步更新（这是个容易被忽略的事项，每次功能改动最后都要检查一遍双语文档）

---

如果人手/时间有限，最低限度也要完成 **P0（跑通编译）** —— 在这之前做的任何新功能都建立在一个未经验证的地基上。
