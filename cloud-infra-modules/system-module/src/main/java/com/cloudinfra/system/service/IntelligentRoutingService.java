package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.ResourceMapping;
import com.cloudinfra.system.domain.ServiceTicket;
import com.cloudinfra.system.mapper.ResourceMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智能路由服务 - 基于资产拓扑自动寻找责任人
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligentRoutingService {

    private final ResourceMappingMapper resourceMappingMapper;

    /**
     * 根据资产ID智能计算该服务工单的责任人
     * 路由逻辑：资产直接所有者 $\rightarrow$ 部门主管 $\rightarrow$ 系统管理员
     */
    public Long routeToResponsibleUser(Long assetId) {
        log.info("正在为资产 {} 计算智能路由...", assetId);

        // 1. 查找资产的直接映射关系
        ResourceMapping mapping = resourceMappingMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceMapping>()
                .eq(ResourceMapping::getResourceId, assetId)
                .last("LIMIT 1")
        );

        if (mapping == null) {
            log.warn("资产 {} 未绑定所有者，路由至系统管理员", assetId);
            return 1L; // 默认回退到超级管理员
        }

        // 2. 如果映射到的是部门，则获取该部门的负责人 (此处简化处理，实际应查询部门表)
        if ("DEPT".equals(mapping.getResourceType())) {
            log.info("资产绑定至部门 {}, 路由至部门负责人", mapping.getOwnerId());
            // 模拟：部门ID $\rightarrow$ 负责人ID
            return mapping.getOwnerId() + 100; 
        }

        // 3. 如果映射到的是用户，直接分发
        return mapping.getOwnerId();
    }
}