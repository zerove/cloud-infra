package com.cloudinfra.system.converter;

import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserDTO;
import com.cloudinfra.system.domain.vo.SysUserVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SysUserConverter {

    public SysUser dtoToEntity(SysUserDTO dto) {
        if (dto == null) {
            return null;
        }
        SysUser entity = new SysUser();
        entity.setUserId(dto.getId());
        entity.setUsername(dto.getUsername());
        entity.setPassword(dto.getPassword());
        entity.setEmail(dto.getEmail());
        entity.setPhoneNumber(dto.getPhoneNumber());
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysUserVO entityToVo(SysUser entity) {
        if (entity == null) {
            return null;
        }
        return new SysUserVO(
            entity.getUserId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getPhoneNumber(),
            entity.getStatus(),
            entity.getCreateTime(),
            null
        );
    }

    /**
     * 带权限的实体转 VO（用于列表预加载权限场景）
     */
    public SysUserVO entityToVoWithPermissions(SysUser entity, List<String> permissions) {
        if (entity == null) {
            return null;
        }
        return new SysUserVO(
            entity.getUserId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getPhoneNumber(),
            entity.getStatus(),
            entity.getCreateTime(),
            permissions
        );
    }

    public List<SysUserVO> entityListToVoList(List<SysUser> list) {
        if (list == null) {
            return List.of();
        }
        return list.stream().map(this::entityToVo).collect(Collectors.toList());
    }

    /**
     * 批量转换并填充权限（避免 N+1）
     */
    public List<SysUserVO> entityListToVoListWithPermissions(List<SysUser> list, Map<Long, List<String>> userPermissions) {
        if (list == null) {
            return List.of();
        }
        return list.stream()
            .map(entity -> entityToVoWithPermissions(entity, userPermissions.get(entity.getUserId())))
            .collect(Collectors.toList());
    }
}
