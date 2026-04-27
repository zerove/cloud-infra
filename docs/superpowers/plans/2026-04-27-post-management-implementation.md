# 岗位管理实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现全局岗位管理及岗位-权限关联功能。

**Architecture:** 采用 MyBatis-Plus 实现岗位 CRUD，通过关联表 `sys_post_permission` 实现岗位级数据权限控制。

**Tech Stack:** Spring Boot 4.0.6, MyBatis Plus, JDK 25.

---

### Task 1: 创建岗位实体与持久层

**Files:**
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/SysPost.java`
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/SysPostPermission.java`
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/mapper/SysPostMapper.java`
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/mapper/SysPostPermissionMapper.java`

- [ ] **Step 1: 创建 SysPost 实体**

```java
package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;

@Data
@TableName("sys_post")
public class SysPost implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long postId;
    private String postCode;
    private String postName;
    private Integer postSort;
    private String status;
}
```

- [ ] **Step 2: 创建 SysPostPermission 关联实体**

```java
package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_post_permission")
public class SysPostPermission {
    private Long postId;
    private Long permissionId;
}
```

- [ ] **Step 3: 创建 Mapper 接口**

```java
package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.system.domain.SysPost;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysPostMapper extends BaseMapper<SysPost> {}
```

```java
package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.system.domain.SysPostPermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysPostPermissionMapper extends BaseMapper<SysPostPermission> {}
```

- [ ] **Step 4: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/SysPost.java
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/SysPostPermission.java
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/mapper/SysPostMapper.java
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/mapper/SysPostPermissionMapper.java
git commit -m "feat: create SysPost entity, mapping, and mappers"
```

### Task 2: 实现岗位业务层

**Files:**
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysPostService.java`
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPostServiceImpl.java`

- [ ] **Step 1: 创建 ISysPostService 接口**

```java
package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysPost;
import java.util.List;

public interface ISysPostService extends IService<SysPost> {
    boolean updatePostPermissions(Long postId, List<Long> permissionIds);
}
```

- [ ] **Step 2: 实现 SysPostServiceImpl**

```java
package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.domain.SysPostPermission;
import com.cloudinfra.system.mapper.SysPostMapper;
import com.cloudinfra.system.mapper.SysPostPermissionMapper;
import com.cloudinfra.system.service.ISysPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SysPostServiceImpl extends ServiceImpl<SysPostMapper, SysPost> implements ISysPostService {
    private final SysPostPermissionMapper postPermissionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updatePostPermissions(Long postId, List<Long> permissionIds) {
        postPermissionMapper.delete(new LambdaQueryWrapper<SysPostPermission>().eq(SysPostPermission::getPostId, postId));
        if (permissionIds != null && !permissionIds.isEmpty()) {
            for (Long pId : permissionIds) {
                SysPostPermission pp = new SysPostPermission();
                pp.setPostId(postId);
                pp.setPermissionId(pId);
                postPermissionMapper.insert(pp);
            }
        }
        return true;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysPostService.java
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPostServiceImpl.java
git commit -m "feat: implement SysPost service logic"
```

### Task 3: 暴露岗位管理 API

**Files:**
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysPostController.java`

- [ ] **Step 1: 创建 SysPostController**

```java
package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.service.ISysPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/system/post")
@RequiredArgsConstructor
public class SysPostController {
    private final ISysPostService postService;

    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermission('system:post:list')")
    public Result<List<SysPost>> list() {
        return Result.success(postService.list());
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('system:post:add')")
    public Result<Boolean> add(@RequestBody SysPost post) {
        return Result.success(postService.save(post));
    }

    @PostMapping("/{postId}/permissions")
    @PreAuthorize("@ss.hasPermission('system:post:edit')")
    public Result<Boolean> updatePermissions(@PathVariable Long postId, @RequestBody List<Long> permissionIds) {
        return Result.success(postService.updatePostPermissions(postId, permissionIds));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysPostController.java
git commit -m "feat: expose SysPost management API"
```
