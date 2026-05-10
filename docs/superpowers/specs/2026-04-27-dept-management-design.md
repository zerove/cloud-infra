# 部门管理系统设计

**目标:** 实现一个支持懒加载树形结构的部门管理系统，并维护用户与部门的多对多关联关系。

**架构:**
- **实体:** `SysDept` 通过 `parentId` 和 `ancestors`（祖籍路径）存储层级数据，以实现高效的树形遍历。
- **关联:** `sys_user_dept` 中间表处理用户与部门的多对多映射。
- **懒加载:** `SysDeptController` 提供支持 `parentId` 参数的接口，以按需加载子节点，支持树形结构的按需展开。
- **安全性:** 使用与现有 RBAC 系统一致的 `@PreAuthorize` 注解进行细粒度权限控制。

**关键组件:**
1. **领域模型:**
   - `SysDept`: `deptId` (主键), `parentId`, `ancestors`, `deptName`, `orderNum`, `status`。
   - `SysUserDept`: `userId`, `deptId`。
2. **持久层:** 基于 MyBatis Plus 的 `SysDeptMapper` 和 `SysUserDeptMapper`。
3. **业务层:** `ISysDeptService` 实现懒加载逻辑（通过 `parentId` 过滤查询）。
4. **API:**
   - `GET /system/dept/list?parentId={parentId}`
   - `POST /system/dept`
   - `PUT /system/dept`
   - `DELETE /system/dept/{deptId}`

**权限标识:**
- `system:dept:list`
- `system:dept:add`
- `system:dept:edit`
- `system:dept:remove`

---
**规格自检:**
1. **懒加载:** API 设计支持通过 `parentId` 进行过滤。
2. **多对多:** 通过 `SysUserDept` 表实现。
3. **一致性:** 遵循现有 `system-module` 的开发模式（控制器 -> 业务层 -> 数据访问层）。
