# 下一轮迭代计划（第三轮）

前两轮计划已归档到 [`archive/`](archive/)。本文档只回答一个问题：接下来两周具体做什么。更长线的路线图见 [`architecture.md`](architecture.md)。

---

## 上一轮的收尾情况

- ✅ `meridian-argus-bom`、`scripts/verify-structure.sh`（已接入 CI，**CI 上结构检查通过**）
- ✅ 五个可选模块同时开启的集成测试（已写，未验证）
- ❌ **CI 的 `Build and test` 步骤失败**（push 到 `34a21f9` 后 23 秒即失败；历史上每次 CI 都是红的）。这是代码第一次被真实编译，所以不意外，但必须先清零
- ⚠️ Codecov 上传同样失败（预期内：还没配置 `CODECOV_TOKEN`）

---

## P0：阻塞项，其他一切都排在它后面

### 1. 让 `mvn -B verify` 变绿
沙箱拿不到 Actions 日志（403），也访问不了 Maven Central，所以需要你把失败日志给我：

```bash
gh run view <run-id> --log-failed          # 或在 GitHub Actions 页面复制 Build and test 步骤的报错
# 或本地：
mvn -B verify 2>&1 | tail -150
```

我按报错逐个修，每修一批就推一次，直到 CI 全绿。**验收：main 上最新一次 CI 为绿色。**

### 2. CI 小修
- `actions/setup-java@v4` 已弃用 → 升到 v5；`checkout`、`upload-artifact`、`codecov-action` 的 Node 20 警告同理，统一升级
- Codecov 步骤在没有 token 时不应让整个 job 变红：加 `fail_ci_if_error: false`，或在无 token 时跳过

---

## P1：P0 变绿之后

### 3. 发布 `0.1.0`
- Sonatype Central Publisher 账号 + GPG 密钥（需要你操作），在仓库 Secrets 里配置
- 跑通 `release.yml`；若 Central 一时搞不定，先发 GitHub Packages，让 README 的 `<dependency>` 真的能用
- 发布前核对：`<developers>`、`<scm>`、`<licenses>`、Javadoc/sources jar、BOM 也要一起发布

### 4. 写 `docs/release-readiness.md`
发布前检查清单：版本号策略（SNAPSHOT → 0.1.0）、变更日志、`CHANGELOG.md`、BOM 与根 `dependencyManagement` 同步、发布后冒烟（新建空项目只引 BOM + starter 能跑起来）。

### 5. BOM 同步校验
BOM 与根 `dependencyManagement` 是手工双写。在 `verify-structure.sh` 里加第 5 项：比较两处的 artifactId 集合，不一致就失败，新增模块漏改一处时 CI 直接报警。

---

## P2：可选

- **Javadoc 站点**：用 GitHub Pages 发布，README 里加链接
- **端到端示例**：demo 模块里加一个同时开 SQLite + Dashboard + 告警的 profile，作为文档里的"全功能"参考
- **Micrometer/OTel 属性名核对**：OTel GenAI 语义约定里目前只验证过 `bedrock → aws.bedrock`，其余服务商名称是直接透传的，逐个对照规范核实

## P3：往后放

- **Prompt/Response 内容采集**：涉及脱敏与合规，必须先做设计讨论（默认关闭、脱敏规则如何配置、是否落盘），不要仓促上马

---

## 验收标准

- [ ] main 上 CI 绿色（编译、测试、结构检查全部通过）
- [ ] `0.1.0` 发布到 Maven Central 或 GitHub Packages
- [ ] 空项目只引 BOM + starter 即可启动（发布后冒烟）
- [ ] BOM 同步校验进入 CI
- [ ] 中英文文档同步

**需要你做的：** 把 CI 失败日志发给我（P0）；之后注册 Sonatype、生成 GPG 密钥、配置 `CODECOV_TOKEN`（P1）。
