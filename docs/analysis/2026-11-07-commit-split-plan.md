# 提交拆分计划（2026-11-07）

> **状态：仅计划，未执行任何 `git add` / `git commit`。**
> 工作区当前 206 项改动（126 modified / 40 deleted / 40 untracked），HEAD `e954b752`。
> 直接 `git add -A` 会得到一个无法 bisect、无法回滚、无法评审的巨型提交 —— 这正是要避免的。

---

## 一、拆分原则

1. **一个提交只做一件事**，且能独立通过编译/测试。
2. **删除项单独成提交**：删除 `manifests/`、根 `Dockerfile*` 与"改业务代码"混在一起时，
   一旦要回滚只能整批回滚。
3. **安全边界优先**：先提交能独立验证的安全修复，再提交功能/配置。
4. **每个提交前的门禁**：至少跑受影响模块的 `mvn -o -pl <module> test` + 相关 `deploy/tests/*`。
5. **不夹带**：格式化、注释、重命名与逻辑改动分开（否则 review 无法聚焦）。

---

## 二、建议提交序列（10 个提交）

### 提交 1：安全 — 内部调用验签单一真源 + 公开弱密钥拒绝

- 范围：
  - `cloud-infra-common/.../core/crypto/InternalCallCrypto.java`
  - `cloud-infra-common/.../core/crypto/InternalCallVerifier.java`（新）
  - `cloud-infra-common/.../util/JwtUtils.java`
  - `cloud-infra-common/src/test/.../crypto/InternalCallVerifierTest.java`（新）
  - `cloud-infra-common/src/test/.../crypto/InternalCallWeakSecretTest.java`（新）
  - `cloud-infra-common/src/test/.../util/JwtUtilsWeakSecretTest.java`
  - `deploy/tests/test_internal_call_verifier_wiring.py`（新）
- 验证：`mvn -o -pl cloud-infra-common test`（97 tests）
- 说明：包含 `.env.example` 公开弱值纳入黑名单（`11474eaa...`），提交信息需写明"存量部署会 fail-fast"。

### 提交 2：安全 — 部署模板密钥留空 + up.sh 统一随机化

- 范围：`.env.example`、`deploy/up.sh`、`deploy/tests/test_env_example_secrets.py`（新）
- 验证：`python3 deploy/tests/test_env_example_secrets.py`（A/B/C 三形态 + 两项负向对照）
- 说明：与提交 1 分开，因为一个是**服务端拒绝**，一个是**部署侧消除**，可独立回滚。

### 提交 3：配置 — gRPC 生效前缀修正（`cloudinfra.grpc.port`）

- 范围：
  - `cloud-infra-modules/system-module/src/main/resources/application.yml`
  - `deploy/tests/check_grpc_port_placeholder.py`
  - `cloud-infra-framework/framework-grpc/src/test/.../GrpcServerLifecycleIntegrationTest.java`
- 验证：门禁三态（裸值/`spring.grpc`/`cloudinfra.grpc.server` 均红）+ `system-module` 118 tests
- 说明：提交信息里写清"删除的 14 个键从未被绑定"，避免下一个人以为是功能回退。

### 提交 4：性能 — tracing sampling 基线对齐 0.2

- 范围：`system / notification / search / file` 四个 `application.yml` 的 `probability`
- 验证：YAML 解析 + `grep` 核对；无测试断言该值
- 说明：与 `deploy/config/*`、`deploy/k8s/**` 已一致，属"回退层对齐部署层"。

### 提交 5：性能 — compose GC 回退 G1GC

- 范围：`deploy/compose.yaml`（仅 6 处 `JAVA_OPTS` 的 GC 相关片段）
- 验证：`check_compose_dangling_deps.py`、`check_jvm_opts_keys_match_image.py`、`test_dockerfile_jvm_flags.py`
- 说明：**不要**把 compose 里其它未提交改动（NACOS_AUTH_TOKEN、mem_limit、注释）一起塞进来；
  用 `git add -p` 只挑 `JAVA_OPTS` 行。

### 提交 6：框架 — Redis 删除语义 + 多级缓存/锁修正

- 范围：`cloud-infra-framework/framework-redis/**`、`framework-web` 异常处理、`framework-mybatis` 相关
- 验证：`mvn -o -pl cloud-infra-framework/... test`
- 说明：含 `delete(Collection)` 的 `Long` 语义修复与 4 项回归测试。

### 提交 7：多租户 — 租户声明与过滤器

- 范围：`common/.../tenant/TenantClaims.java`（新）、`TenantContextFilter`、各 Mapper `@TenantFilter`、
  `deploy/config` 中 tenant 开关
- 验证：`TenantIsolationEndToEndIT`、`GatewayTenantPropagationIT`、`TenantClaimsTest`
- 说明：这是跨模块改动，放在框架提交之后可减少冲突。

### 提交 8：业务 — system / notification / search / file 功能与迁移

- 范围：`cloud-infra-modules/**` 主代码 + 测试 + `db/migration/V24~V26`
- 验证：`mvn -o verify -pl <module> -DskipUnitTests=true -DskipITs=false`（逐模块）
- 说明：迁移脚本必须在同一提交内，否则代码先上线会因为缺表/缺列失败。

### 提交 9：删除 — 旧部署清单与根 Dockerfile

- 范围：`manifests/**`（16 项删除）、`deploy/helmfile/**`、根 `Dockerfile` / `Dockerfile.native`
- 验证：可执行代码/门禁里对这两个路径的**引用**应为 0（注释里的历史记录不算）；
  `test_k8s_values_alignment.py` 与 `test_k8s_probe_paths.py` 应绿。
  ⚠️ 已实测：`test_k8s_probe_paths.py` / `test_es_version_alignment.py` 曾仍声明该路径并静默降级，2026-11-07 已改为"声明的扫描根/配置必须存在"。
- 说明：**需要你单独确认**——这是"删掉一整套部署入口"，属不可逆决策（历史仍在 git，可恢复）。

### 提交 10：文档与门禁接线

- 范围：`docs/**`、`README/ARCHITECTURE/CONTEXT/UBIQUITOUS_LANGUAGE`、`deploy/tests/*` 其余脚本
- 验证：`python3 deploy/tests/test_gate_wiring.py`（64 脚本 / 0 未接线）

---

## 三、执行方式（建议）

```bash
# 每个提交: 先按路径暂存 → 逐行审查 → 只提交本组
git add <paths>
git diff --cached --stat
git diff --cached            # 必须人眼过一遍, 确认没有夹带
git commit -m "<type>(<scope>): <subject>"

# 每提交后跑该组声明的验证
mvn -o -pl <module> test
python3 deploy/tests/<gate>.py
```

**不建议**使用的做法：
- `git add -A` / `git commit -am`（会把 206 项揉成一个提交）
- `git checkout -- <file>` 清理"看起来无关"的改动（本轮已踩过：会抹掉别人的在途改动）

---

## 四、提交前必须处理的仓库级问题

1. **分支分叉**：本地 `master` 与 `origin/master` 为两条历史线（697 ahead / 514 behind，
   merge-base `81e9bf0c`，2026-08-04），历史因 `git filter-repo` 清除泄露 dev JWT 而重写。
   提交前需决定：新建分支 `fix/2026-11-security-and-config` 推送，还是 force-push `master`。
2. **密钥轮换**：远端历史仍保有明文，force-push 后仍须轮换所有曾暴露的凭据（JWT / internal call / DB）。
3. **CI 计费**：最近 E2E 为 `BILLING_BLOCKED`（steps=0），对当前 HEAD 无任何有效 CI 结论；
   推送前先确认 `./deploy/tests/classify_gh_run.sh --workflow e2e.yml` 的判定不再是计费阻塞。

---

## 五、本轮已完成的验证（可作为提交信息中的证据）

| 验证 | 结果 |
|---|---|
| 全量 IT（最终源码状态） | `BUILD SUCCESS` — 106 IT / 0 失败 / 0 错误 |
| 全量单测 | `BUILD SUCCESS` — 594 tests / 0 失败 / 0 错误 / 1 跳过 |
| 门禁接线 | 64 个脚本 / 应被 CI 调用 38 / **未被引用 0** |
| 关键负向对照 | `.env.example` 随机化、gRPC 三态、grpc 死键绑定 —— 全部"改坏变红、还原变绿" |
