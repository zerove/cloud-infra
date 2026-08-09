package com.cloudinfra.system.converter;

import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.domain.dto.SysMenuCreateDTO;
import com.cloudinfra.system.domain.dto.SysMenuUpdateDTO;
import com.cloudinfra.system.domain.vo.SysMenuVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 菜单转换器
 */
@Component
public class SysMenuConverter {

    public SysMenu createDtoToEntity(SysMenuCreateDTO dto) {
        if (dto == null) {
            return null;
        }
        SysMenu entity = new SysMenu();
        entity.setMenuName(dto.getMenuName());
        entity.setPerms(dto.getPerms());
        entity.setParentId(dto.getParentId());
        entity.setPath(dto.getPath());
        entity.setComponent(dto.getComponent());
        
        Integer fs = dto.getFrameStatus();
        entity.setFrameStatus(fs);
        
        entity.setMenuType(dto.getMenuType());
        entity.setOrderNum(dto.getOrderNum());
        
        Integer vs = dto.getVisibleStatus();
        entity.setVisibleStatus(vs);
        
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysMenu updateDtoToEntity(SysMenuUpdateDTO dto) {
        if (dto == null) {
            return null;
        }
        SysMenu entity = new SysMenu();
        entity.setId(dto.getMenuId());
        entity.setMenuName(dto.getMenuName());
        entity.setPerms(dto.getPerms());
        entity.setParentId(dto.getParentId());
        entity.setPath(dto.getPath());
        entity.setComponent(dto.getComponent());
        
        Integer fs = dto.getFrameStatus();
        entity.setFrameStatus(fs);
        
        entity.setMenuType(dto.getMenuType());
        entity.setOrderNum(dto.getOrderNum());
        
        Integer vs = dto.getVisibleStatus();
        entity.setVisibleStatus(vs);
        
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysMenuVO entityToVo(SysMenu entity) {
        if (entity == null) {
            return null;
        }
        
        Integer fs = entity.getFrameStatus();
        Integer vs = entity.getVisibleStatus();
        
        return new SysMenuVO(
            entity.getId(),
            entity.getParentId(),
            entity.getMenuName(),
            entity.getPerms(),
            entity.getPath(),
            entity.getComponent(),
            fs,
            entity.getMenuType(),
            entity.getOrderNum(),
            vs,
            entity.getStatus(),
            entity.getChildren() == null ? null : 
                entity.getChildren().stream().map(this::entityToVo).collect(Collectors.toList())
        );
    }

    public List<SysMenuVO> entityListToVoList(List<SysMenu> list) {
        if (list == null) {
            return List.of();
        }
        return list.stream().map(this::entityToVo).collect(Collectors.toList());
    }
}
