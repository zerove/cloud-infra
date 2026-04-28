# 部门与岗位管理设计规范

## 1. 概述
本设计规范旨在增强 `system-module` 模块中部门管理 (SysDept) 和岗位管理 (SysPost) 的业务逻辑，使其符合生产级系统的健壮性、可维护性及 API 一致性标准。

## 2. 部门管理 (SysDept) 设计

### 2.1 业务校验约束
*   **层级深度限制**：引入常量 `SYSTEM_DEPT_MAX_DEPTH = 5`。
    *   新增/修改时，通过 `ancestors` 字段计算当前深度，超过限制抛出 `DEPT_LEVEL_EXCEED` 异常。
*   **删除校验**：
    *   **下级检查**：调用 `selectCountByParentId`，若 `> 0` 则抛出 `DEPT_HAS_CHILD`。
    *   **用户关联检查**：调用 `SysUserMapper` 检查是否存在关联用户，若 `> 0` 则抛出 `DEPT_IN_USE`。

### 2.2 存储设计
*   **路径维护**：利用 `ancestors` 字段（格式：`0,1,101`）实现快速层级遍历。
*   **索引优化**：为 `parentId` 和 `ancestors` 字段添加索引。

## 3. 岗位管理 (SysPost) 设计

### 3.1 业务校验约束
*   **批量删除**：采用原子性校验。
    *   在 Service 层循环检查选中的岗位是否被用户关联。
    *   一旦检测到任何岗位被占用，抛出 `POST_IN_USE` 异常，触发事务回滚，确保操作的原子性。

## 4. 通用标准化规范

### 4.1 异常处理
*   在 `SystemExceptionCode` 中定义：
    *   `DEPT_LEVEL_EXCEED`: "部门层级最多支持 5 级"
    *   `DEPT_HAS_CHILD`: "存在下级部门，禁止删除"
    *   `DEPT_IN_USE`: "部门下存在关联用户，禁止删除"
    *   `POST_IN_USE`: "岗位存在关联用户，禁止删除"

### 4.2 接口响应
*   统一返回 `Result<T>` 封装结构，避免手动构建 `Map` 返回值。

---
## 5. 开发实施准备
1. 预执行：更新 `SysDept` 及 `SysPost` 的 `Service` 实现类。
2. 配置：更新 `GlobalExceptionHandler` 捕获新增异常。
3. 测试：编写集成测试用例，验证原子性回滚和层级限制。
