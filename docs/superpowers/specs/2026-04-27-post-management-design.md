# 岗位管理系统设计

**目标:** 实现一个全局的岗位管理系统，支持岗位职能标签管理，并具备与业务权限（Permission）的关联能力，以支撑基于岗位的数据权限控制。

**架构:**
- **实体:** `SysPost` 存储岗位基础信息。
- **关联:** `sys_post_permission` 中间表实现岗位与业务权限的多对多关联，支持岗位级的数据权限范围管控。
- **全局性:** 岗位设计为全局对象，与部门（Dept）保持松耦合关系，可在不同部门间灵活复用。
- **安全性:** 应用层鉴权集成至现有的 RBAC 体系，通过 `@PreAuthorize` 控制岗位相关操作权限。

**关键组件:**
1. **领域模型:**
   - `SysPost`: `postId` (主键), `postCode` (岗位编码), `postName` (岗位名称), `postSort` (显示顺序), `status` (状态)。
   - `SysPostPermission`: `postId`, `permissionId`。
2. **持久层:** 
   - `SysPostMapper`, `SysPostPermissionMapper` (基于 MyBatis Plus)。
3. **业务层:** 
   - `ISysPostService` 提供岗位管理、权限绑定逻辑。
   - 增强 `PermissionService` 以适配岗位级的权限校验场景。
4. **API:**
   - `GET /system/post/list`: 分页查询岗位列表。
   - `POST /system/post`: 创建岗位。
   - `PUT /system/post`: 更新岗位信息。
   - `DELETE /system/post/{postId}`: 删除岗位。
   - `POST /system/post/{postId}/permissions`: 岗位授权接口。

**权限标识:**
- `system:post:list`
- `system:post:add`
- `system:post:edit`
- `system:post:remove`

---
**规格自检:**
1. **全局性:** 岗位与部门解耦，实现全局复用。
2. **权限扩展:** 通过中间表支持岗位关联权限，支撑数据权限过滤。
3. **一致性:** 接口安全控制及模块架构与部门管理保持高度一致。
