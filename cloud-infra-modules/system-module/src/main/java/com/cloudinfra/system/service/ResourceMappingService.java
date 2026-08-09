package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.ResourceMapping;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.mapper.ResourceMappingMapper;
import com.cloudinfra.system.mapper.SysDeptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * 资源拓扑映射服务 - 实现资源与组织架构的权限展开
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceMappingService {

    private final ResourceMappingMapper mappingMapper;
    private final SysDeptMapper deptMapper;

    /**
     * 绑定资源到组织节点
     */
    public void bindResource(String resourceId, String resourceType, Long nodeId, String nodeType, String relationType) {
        ResourceMapping mapping = new ResourceMapping();
        mapping.setResourceId(resourceId);
        mapping.setResourceType(resourceType);
        mapping.setOwnerId(nodeId);
        mapping.setNodeType(nodeType);
        mapping.setRelationType(relationType);
        mappingMapper.insert(mapping);
    }

    /**
     * 获取资源的有效权限范围 (展开拓扑)
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     * @return 包含所有授权节点ID的集合 (UserId, DeptId)
     */
    public Set<Long> getEffectiveAccessNodes(String resourceId, String resourceType) {
        List<ResourceMapping> mappings = mappingMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceMapping>()
                .eq(ResourceMapping::getResourceId, resourceId)
                .eq(ResourceMapping::getResourceType, resourceType)
        );

        Set<Long> effectiveNodes = new HashSet<>();
        for (ResourceMapping m : mappings) {
            if ("USER".equalsIgnoreCase(m.getNodeType())) {
                effectiveNodes.add(m.getOwnerId());
            } else if ("DEPT".equalsIgnoreCase(m.getNodeType())) {
                // 处理部门继承：添加当前部门及其所有上级部门
                expandDeptHierarchy(m.getOwnerId(), effectiveNodes);
            }
            // 角色(ROLE)可以通过关联的用户表进一步展开，此处简化处理
        }
        return effectiveNodes;
    }

    private void expandDeptHierarchy(Long deptId, Set<Long> nodes) {
        SysDept dept = deptMapper.selectById(deptId);
        if (dept == null) {
            return;
        }

        nodes.add(deptId);
        
        // 利用 ancestors 字段快速展开所有上级
        if (dept.getAncestors() != null && !dept.getAncestors().isBlank()) {
            String[] ancestors = dept.getAncestors().split(",");
            for (String ancestorId : ancestors) {
                if (!ancestorId.isBlank()) {
                    nodes.add(Long.parseLong(ancestorId));
                }
            }
        }
    }
}