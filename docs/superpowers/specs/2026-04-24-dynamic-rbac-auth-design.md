# 动态 RBAC 权限拦截设计文档

## 1. 目标
实现基于数据库的动态权限管理，通过 Nacos 配置中心将权限映射动态同步至网关，实现细粒度的接口鉴权。

## 2. 架构设计
### 2.1 数据库结构升级 (system-module)
- **sys_permission 表**: 增加 `method` 字段 (varchar 10) 以区分 HTTP 方法。
- **实体映射**: 更新 `SysPermission` 实体类。

### 2.2 权限同步机制 (System Module)
- **定时同步任务**: 增加 `PermissionSyncTask`，每分钟或手动触发时，从数据库获取最新权限映射。
- **Nacos 推送**: 使用 Nacos API 将权限 JSON 序列化并推送到指定 `DataID` (`gateway-permissions.json`)。

### 2.3 动态鉴权 (cloud-infra-gateway)
- **Nacos Listener**: 网关监听 `gateway-permissions.json` 变更，更新本地内存缓存。
- **动态拦截器**: `GlobalAuthFilter` 中增加鉴权匹配逻辑：
    1. 根据 Path + Method 匹配缓存中的 Perms。
    2. 获取当前用户权限列表（从 JWT 或 Redis）。
    3. 校验用户是否具备该 Perms。

## 3. 数据流
1. 权限变更 -> DB 更新 -> System Module (定时任务) -> Nacos (配置推送)
2. 网关 (监听 Nacos) -> 本地内存更新
3. 请求 -> 网关 (拦截) -> 比对缓存 -> 鉴权

## 4. 验证
- 编写集成测试，测试权限更新后网关是否能及时拦截未授权请求。
