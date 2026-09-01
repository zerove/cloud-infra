# M5-05: codegen 模板双格式整合 (.tpl → .ftl)

Status: open
Priority: P1
Owner: TBD
Module: cloud-infra-codegen
Depends: M5-04

## 背景

`cloud-infra-codegen` 有两套模板:
- `cloud-infra-codegen-core/src/main/resources/templates/*.ftl` — 10 个 (FreeMarker)
- `cloud-infra-codegen-maven-plugin/src/main/resources/templates/*.tpl` — 5 个 (Maven plugin 简化版)

**维护成本翻倍**:
- 改一处模板要在两套里都改
- 模板市场不可能 (用户下载哪个?)
- 文档要解释为什么有两套

## 任务

### 选项 A: 统一用 .ftl (推荐)

`maven-plugin` 子模块**复用** core 的 .ftl 模板,通过 classpath 加载:
```java
// GenerateCodeMojo.java
TemplateEngine engine = new TemplateEngine(getClass().getClassLoader(), "/templates");
```

### 选项 B: 只保留 maven-plugin 模板

core 库改为可选,只保留 maven-plugin(API 也通过 plugin 调用)。

### 选项 C: 拆 codegen-modules 公开市场

未来 codegen 模板市场的基础,本次只整合。

## 验收

- [ ] 选定方案 A/B/C
- [ ] 改完测试通过 (codegen-core 24 tests + 模板生成 dry-run)
- [ ] 删去其中一套模板
- [ ] README 更新 (单模板说明)

## 参考

- `cloud-infra-codegen/README.md` 模板变量说明
- 现有模板位置: core/.ftl + maven-plugin/.tpl
- 短期: 不阻塞 codegen 推广,作为技术债处理
