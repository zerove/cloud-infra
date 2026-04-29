# Implementation Task: Define Business Exception Codes

## Goal
Add new business exception codes to `SystemExceptionCode.java` as defined in the implementation plan.

## Requirements
Modify `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/exception/SystemExceptionCode.java` to add the following codes:
- DEPT_LEVEL_EXCEED("DEPT_001", "部门层级最多支持 5 级")
- DEPT_HAS_CHILD("DEPT_002", "存在下级部门，禁止删除")
- DEPT_IN_USE("DEPT_003", "部门下存在关联用户，禁止删除")
- POST_IN_USE("POST_001", "岗位存在关联用户，禁止删除")

## Context
- The file uses a standard enum pattern with (String code, String msg) constructor.
- Please maintain the existing style (Lombok, spacing, etc.).

## Process
1. Modify the file with the new enum constants.
2. Verify the modification.
3. Commit with message: "feat: add business exception codes for Dept/Post"
4. Perform self-review (Check for syntax, imports, and correct naming).
