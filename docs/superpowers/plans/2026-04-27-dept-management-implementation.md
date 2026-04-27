# 部门管理实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现支持懒加载树形结构的部门管理系统。

**Architecture:** 采用 MyBatis-Plus 基础 CRUD，通过控制 `parentId` 查询实现树形结构懒加载。

**Tech Stack:** Spring Boot 4.0.6, MyBatis Plus, JDK 25.

---

### Task 1: 创建实体与持久层

**Files:**
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/SysDept.java`
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/mapper/SysDeptMapper.java`

- [ ] **Step 1: 创建 SysDept 实体**

```java
package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;

@Data
@TableName("sys_dept")
public class SysDept implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long deptId;
    private Long parentId;
    private String ancestors;
    private String deptName;
    private Integer orderNum;
    private String status;
}
```

- [ ] **Step 2: 创建 SysDeptMapper 接口**

```java
package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.system.domain.SysDept;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysDeptMapper extends BaseMapper<SysDept> {}
```

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/SysDept.java
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/mapper/SysDeptMapper.java
git commit -m "feat: create SysDept entity and mapper"
```

### Task 2: 实现部门业务层

**Files:**
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysDeptService.java`
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysDeptServiceImpl.java`

- [ ] **Step 1: 创建 ISysDeptService 接口**

```java
package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysDept;
import java.util.List;

public interface ISysDeptService extends IService<SysDept> {
    List<SysDept> selectDeptList(Long parentId);
}
```

- [ ] **Step 2: 实现 SysDeptServiceImpl**

```java
package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.mapper.SysDeptMapper;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements ISysDeptService {
    @Override
    public List<SysDept> selectDeptList(Long parentId) {
        return list(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, parentId).orderByAsc(SysDept::getOrderNum));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysDeptService.java
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysDeptServiceImpl.java
git commit -m "feat: implement SysDept service logic"
```

### Task 3: 暴露部门接口

**Files:**
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysDeptController.java`

- [ ] **Step 1: 创建 SysDeptController**

```java
package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.service.ISysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/system/dept")
@RequiredArgsConstructor
public class SysDeptController {
    private final ISysDeptService deptService;

    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermission('system:dept:list')")
    public Result<List<SysDept>> list(Long parentId) {
        return Result.success(deptService.selectDeptList(parentId == null ? 0L : parentId));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysDeptController.java
git commit -m "feat: expose SysDept management API"
```
