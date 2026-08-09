package com.cloudinfra.system.converter;

import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.common.domain.SysPermission;
import com.cloudinfra.system.domain.dto.SysRoleCreateDTO;
import com.cloudinfra.system.domain.dto.SysRoleUpdateDTO;
import com.cloudinfra.system.domain.vo.SysRoleVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色转换器
 */
@Component
public class SysRoleConverter {

    public SysRole createDtoToEntity(SysRoleCreateDTO dto) {
        if (dto == null) return null;
        SysRole entity = new SysRole();
        entity.setRoleName(dto.getRoleName());
        entity.setRoleKey(dto.getRoleKey());
        
        Integer ro = dto.getRoleOrder();
        entity.setRoleOrder(ro);
        
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysRole updateDtoToEntity(SysRoleUpdateDTO dto) {
        if (dto == null) return null;
        SysRole entity = new SysRole();
        entity.setId(dto.getRoleId());
        entity.setRoleName(dto.getRoleName());
        entity.setRoleKey(dto.getRoleKey());
        
        Integer ro = dto.getRoleOrder();
        entity.setRoleOrder(ro);
        
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysRoleVO entityToVo(SysRole entity) {
        if (entity == null) return null;
        
        Integer ro = entity.getRoleOrder();
        
        return new SysRoleVO(
            entity.getId(),
            entity.getRoleName(),
            entity.getRoleKey(),
            ro,
            entity.getStatus(),
            null // permissionIds 需要在 Service 层异步加载
        );
    }

    /**
     * 带权限的实体转 VO（用于列表预加载权限场景）
     */
    public SysRoleVO entityToVoWithPermissions(SysRole entity, List<SysPermission> permissions) {
        if (entity == null) return null;
        
        Integer ro = entity.getRoleOrder();
        List<Long> permissionIds = (permissions == null) ? null : permissions.stream()
            .map(SysPermission::getId)
            .collect(Collectors.toList());
        
        return new SysRoleVO(
            entity.getId(),
            entity.getRoleName(),
            entity.getRoleKey(),
            ro,
            entity.getStatus(),
            permissionIds
        );
    }

    public List<SysRoleVO> entityListToVoList(List<SysRole> list) {
        if (list == null) return List.of();
        return list.stream().map(this::entityToVo).collect(Collectors.toList());
    }

    /**
     * 批量转换并填充权限（避免 N+1）
     */
    public List<SysRoleVO> entityListToVoListWithPermissions(List<SysRole> list, Map<Long, List<SysPermission>> rolePermissions) {
        if (list == null) return List.of();
        return list.stream()
            .map(entity -> entityToVoWithPermissions(entity, rolePermissions.get(entity.getId())))
            .collect(Collectors.toList());
    }
}
