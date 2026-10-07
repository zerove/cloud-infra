# API 概览

本文档提供了项目 API 表面的高层映射。详细的 Swagger/OpenAPI 文档可在各服务的 `/swagger-ui.html` 端点查看。

## 1. 网关 (端口: 9000)
所有客户端请求的统一入口。
- **路由 `/auth/**`** $\rightarrow$ `cloud-infra-auth`
- **路由 `/system/**`** $\rightarrow$ `cloud-infra-modules:system-module`
- **路由 `/file/**`** $\rightarrow$ `cloud-infra-modules:file-service`
- **路由 `/search/**`** $\rightarrow$ `cloud-infra-modules:search-service`
- **路由 `/notification/**`** $\rightarrow$ `cloud-infra-modules:notification-service`

## 2. 认证服务 (端口: 8081)
处理身份识别与访问管理。
- `POST /auth/login`: 用户登录并返回 JWT。
- `POST /auth/captchaImage`: 生成验证码图片（Base64）。
- `POST /auth/refresh`: 刷新 Token（请求头携带 `Refresh-Token`）。
- `POST /auth/register`: 用户注册（幂等）。

## 3. 系统模块 (端口: 8082)
RBAC 与系统配置的管理核心。
- **用户管理**: `/system/user` (增删改查, 状态控制, 岗位分配)
- **角色管理**: `/system/role` (权限分配, 角色-用户关联)
- **部门管理**: `/system/dept` (组织架构层级)
- **菜单/权限**: `/system/menu` 和 `/system/permission` (RBAC 基础定义)
- **系统配置**: `/system/config` (应用动态 K-V 配置)

## 4. 文件服务 (端口: 8083)
对象存储抽象层。
- `/file/upload`: 文件上传。
- `/file/download`: 文件下载。
- `/file/sys/file`: 文件元数据管理。

## 5. 搜索服务 (端口: 8085)
基于 Elasticsearch 的搜索实现。
- `/search/query`: 全文检索索引实体。
- `/search/sync`: 触发指定数据的重新索引。

## 6. 通知服务 (端口: 8084)
异步消息交付。
- `/notification/send`: 发送通知 (邮件, 短信等)。
- `/notification/history`: 查询用户通知历史。

## 7. 管理端口 (统一 9301, ADR-008)
所有服务的管理端点独立于业务端口，统一为 `:9301/management/...`。
- 健康检查: `/management/health`
- 指标: `/management/prometheus`
- 本地 compose 部署时映射到宿主机: gateway=9301, auth=9302, system=9303, file=9304, notification=9305, search=9306
