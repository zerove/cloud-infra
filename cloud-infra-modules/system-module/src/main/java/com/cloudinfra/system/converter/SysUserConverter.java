package com.cloudinfra.system.converter;

import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserDTO;
import com.cloudinfra.system.domain.vo.SysUserVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SysUserConverter {

    public SysUser dtoToEntity(SysUserDTO dto) {
        if (dto == null) return null;
        SysUser entity = new SysUser();
        entity.setId(dto.getId());
        entity.setUsername(dto.getUsername());
        entity.setPassword(dto.getPassword());
        entity.setEmail(dto.getEmail());
        entity.setPhone(dto.getPhone());
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysUserVO entityToVo(SysUser entity) {
        if (entity == null) return null;
        SysUserVO vo = new SysUserVO();
        vo.setId(entity.getId());
        vo.setUsername(entity.getUsername());
        vo.setEmail(entity.getEmail());
        vo.setPhone(entity.getPhone());
        vo.setStatus(entity.getStatus());
        if (entity.getCreateTime() != null) {
            vo.setCreateTime(entity.getCreateTime());
        }
        return vo;
    }

    public List<SysUserVO> entityListToVoList(List<SysUser> list) {
        if (list == null) return null;
        return list.stream().map(this::entityToVo).collect(Collectors.toList());
    }
}
