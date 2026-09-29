# 质量门存量清理: SpotBugs 剩余业务模块

Status: open
Priority: P2
Owner: AI Agent
Module: cloud-infra-auth / file-service / notification-service / search-service / system-module
Depends: 无

## 背景 (2026-09-29)

排查 M7-02 时发现 `master` 上 CI 质量门本就失败。本轮已清零:

- **Checkstyle**: 全 21 模块 0 违规 (原先 35+ 处)
- **SpotBugs**: common / api / codegen-core / codegen-maven-plugin /
  framework-{security,tracing,redis,grpc,web,mybatis} / gateway / starter 全部 0

剩余存量集中在五个业务模块, 均为机械性问题, 单独成 issue 以便独立评审。

## 现状盘点

`mvn -o compile spotbugs:check` 当前报告:

### cloud-infra-auth (7)

| 文件 | 模式 | 性质 |
|---|---|---|
| `config/SecurityConfig.java` | `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION` | 误报 (同 starter, Spring Security 固有签名) |
| `domain/LoginUser.java` | `SE_NO_SERIALVERSIONID` | 可加 `serialVersionUID` 或移除 `Serializable` |
| `domain/SecurityUser.java` | `EQ_DOESNT_OVERRIDE_EQUALS` | **需判断**: 实现 `UserDetails` 却未覆写 `equals`/`hashCode`, 若放入集合或做缓存键会出错 |
| `domain/SecurityUser.java` | `SE_NO_SERIALVERSIONID` | 同 LoginUser |
| `service/SysLoginService.java:74` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | 误报 (下一行即 `fullUser == null` 判空) |
| `service/SysLoginService.java:48` | `URF_UNREAD_FIELD` passwordEncoder | **真实冗余**: 密码校验已委托 `authenticationManager`, 该字段只写不读 |

### file-service (6+)

`FileSecurityConfig` throws 子句 (误报)、`FilePermissionService.levelRank`
大小写未加 Locale、`FileServiceImpl` 两处裸 `RuntimeException`、
`MinioStorageStrategy.delete/exists` 过宽 `catch (Exception)`。

### notification-service / search-service / system-module

待 `mvn -o compile spotbugs:check` 逐模块推进时确认。

## 验收标准

1. `mvn -o compile spotbugs:check` 全绿
2. `mvn -o checkstyle:check` 保持全绿
3. `mvn -o test` 保持 0 失败
4. 每条豁免都必须在 `spotbugs-exclude.xml` 内附理由注释, 不允许裸排除
5. `SecurityUser` 的 `equals`/`hashCode` 需给出明确结论 (补实现或说明不需要),
   不允许静默忽略

## 不在本 issue 范围

- 性能压测、运行时验收
- M7-04 CDC / M7-05 模板市场 / M7-06 编辑器插件

## 关联

- 本轮提交: `e51ea3d` / `8ba2571` / `c1e1437`
- `spotbugs-exclude.xml` / `checkstyle.xml`
