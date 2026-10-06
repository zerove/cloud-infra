# 删除端点: 实现已就绪但未开放 —— 待产品契约决策

Status: blocked (需产品决策)
Priority: P2
Owner: 待定 (需产品侧确认)
Module: file-service
Depends: 无 (技术调研已完成)

## 结论先行

`IFileService.delete` 的实现**完整可用**, 但**没有任何 HTTP 端点暴露它**,
且全仓**只有测试代码调用它**。这不是「功能没写完」, 而是「写完了但没决定要不要开」。

阻塞点是**产品契约**, 不是技术可行性 —— 所以 AI 不擅自实现。

## 事实依据（2026-10-06 全部实测, 非推断）

### 1. 服务层实现完整

`cloud-infra-modules/file-service/.../FileServiceImpl.java:227`

```java
public boolean delete(Long fileId, Long userId) {
    FileMetadata metadata = metadataMapper.selectById(fileId);
    if (metadata == null) return false;

    // 写权限校验（含上传者本人或授权用户）
    if (!permissionService.hasAccess(userId, fileId, "WRITE")) {
        throw new BusinessException(ErrorCode.NO_PERMISSION, "You do not have permission to delete this file");
    }

    StorageStrategy strategy = storageFactory.resolveForRead(metadata.getFilePath());
    boolean deleted = strategy.delete(metadata.getFilePath());

    if (deleted) {
        // 释放配额：优先使用元数据中的真实上传者，回退到当前操作者
        Long ownerId = parseUploader(metadata.getUploader());
        if (ownerId == null) ownerId = userId;
        if (metadata.getFileSize() != null) {
            quotaService.release(ownerId, metadata.getFileSize());
        }
        versionMapper.delete(...);   // 版本清理
        return metadataMapper.deleteById(fileId) > 0;
    }
    return false;
}
```

已覆盖: 存在性检查 → 细粒度 WRITE 权限 → **真实存储删除** → 配额释放 → 版本清理 → 元数据删除。

### 2. 控制器 9 个端点中无删除

`FileController.java` 实测 9 个 `@(Get|Post|Put|Delete)Mapping`: presign-upload /
presign-download / presign-multipart-init / upload / update / download /
download version / history / search —— **均无 delete**。

### 3. 权限点体系已就绪, 唯独缺 delete

权威定义在迁移脚本 `V8__add_file_permission_seed.sql`:

| id | name | perms | method | path |
| --- | --- | --- | --- | --- |
| 20 | 文件上传 | `file:file:upload` | POST | /file/upload |
| 21 | 文件更新 | `file:file:update` | PUT | /file/update/{fileId} |
| 22 | 文件下载 | `file:file:download` | GET | /file/download/{fileId} |
| 23 | 文件历史 | `file:file:history` | GET | /file/history/{fileId} |
| 24 | 文件搜索 | `file:file:search` | GET | /file/search |

控制器 `hasPermi('file:file:*')` 与 `sys_permission` 表现存**完全对齐**
(实测差集为空) —— 即现有约定清晰, 加一个 delete 端点是顺着既有约定走。

数据库实测: `sys_permission` 共 38 条, 其中 `file:%` 恰 5 条(id 20-24), 无 delete。

### 4. 生产代码零调用

全仓 `delete(...)` 的调用方只有测试代码。

## 需要决策的问题

**Q1: 要不要开放删除端点?**
若要, 需新增 `DELETE /file/{fileId}` + `file:file:remove` 权限点(id 25) + 迁移脚本。

**Q2: 硬删除还是回收站?**

| 方案 | 代价 | 适用 |
| --- | --- | --- |
| 硬删除（现状实现） | 不可逆, 误删无法恢复 | 文件管理类产品惯例 |
| 回收站 | 需加 `deleted_at` 列 + 恢复端点 + 定时清理 | 有合规/审计要求的场景 |

⚠️ 这是**不可逆的产品契约决策**, 且实现侧目前是硬删除 —— 若上回收站,
需要改的是**数据模型 + 已写好的实现**, 不是加个端点那么简单。

**Q3: 配额释放时机对不对?**
现状是「存储删除成功后才释放配额」。若存储删除失败, 配额不释放 ——
这个行为是对的（文件还在, 额度还占着）。需确认是否与产品预期一致。

## 若决定开放, 需要改什么（预估）

1. `FileController` 新增端点 + `@PreAuthorize("@ss.hasPermi('file:file:remove')")`
2. 新迁移脚本插入 `(25, '文件删除', 'file:file:remove', '/file/{fileId}', 'DELETE')`
3. `ControllerAuthorizationTest` 守护（现有约定: 非 `/internal/` 端点必须有 `@PreAuthorize`）
4. 集成测试: 权限拒绝 / 配额释放 / 版本清理 / 存储删除失败时不释放配额

## 记录本 issue 的原因

这类「实现已就绪但未开放」的能力, 若只在对话里提一句就过去,
下一个人会重新调研一遍。把**已验证的事实**与**待决策的问题**分开写清楚,
使决策本身不需要重复取证。

## 取证过程中踩的两个坑（与本项目已有教训同型）

1. **查错了表, 得到一个假的「不存在」。** 第一次查 `select perms from sys_menu
   where perms like 'file:%'` 返回空, 我据此写了「权限点体系里没有 file 相关条目」。
   实际错误有两个: `sys_menu` 的主键列是 `id` 而非 `menu_id`（那次查询直接报错）;
   更重要的是**权限点根本不在 `sys_menu`**, 而在 `sys_permission` —— 正确查询立刻
   返回 5 条 `file:%` 记录。

   即「查不到」有两种解释, 必须区分: **对象查错了** vs **对象确实为空**。
   若不复核列名就会把探针失败读成业务结论（第五课）。

2. **我自己写的 echo 也是错的。** 查出 5 条记录后, 我在同一段脚本里写了
   `echo "  ↑ 空 = 权限点确实不存在"` —— 这行是**基于上一次错误结论**预设的,
   若不逐行核对输出, 它会与实际结果矛盾并被后续推理当真。

   教训: **判据旁不能放基于旧结论写的解释性文案**, 否则探针纠正了,
   文案还在说反话。正确做法是让文案随数据生成, 或干脆不写。
