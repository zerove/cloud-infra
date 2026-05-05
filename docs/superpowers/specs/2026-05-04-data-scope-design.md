# DataScope Implementation Design

## 1. Scope Logic Definitions
- **ALL**: No filtering (pass through).
- **DEPT_ONLY**: Add `AND dept_id = #{userDeptId}`.
- **SELF**: Add `AND user_id = #{userId}`.

## 2. MyBatis Interceptor Logic
- Enhance `DataScopeInterceptor` to dynamically append SQL based on `DataScopeEnum`.
- Need to access `LoginUser` from `SecurityUtils` to get `userId` and `deptId`.
- Use `StringJoiner` or `StringBuilder` to build SQL snippets.

## 3. Security Integration
- `SecurityUtils` needs to provide current user info:
  - `getUserId()`
  - `getDeptId()`

## 4. Next Steps
- Verify `SecurityUtils` capabilities.
- Create Implementation Plan.
