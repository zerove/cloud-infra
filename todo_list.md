# 部门与岗位管理实施计划

**Tasks:**
1. Task 1: 定义业务异常枚举 (已完成)
2. Task 2: 实现部门管理业务逻辑增强 (已完成)
3. Task 3: 实现岗位管理批量删除原子性校验
4. Task 4: 接口层标准化与权限对齐 (部门与岗位)

**Next Action:** I will execute Task 3 and Task 4 sequentially using the `subagent-driven-development` process.

**Task 3 Description:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPostServiceImpl.java`
- Implement: `deletePostByIds(List<Long> postIds)` with atomic validation using `userMapper.countByPostId(postId)`.
- Note: Need to ensure `countByPostId` is available in `SysUserMapper`.

**Task 4 Description:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysDeptController.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysPostController.java`
- Implement: Standardize APIs with `Result` wrapper and `@PreAuthorize`.
