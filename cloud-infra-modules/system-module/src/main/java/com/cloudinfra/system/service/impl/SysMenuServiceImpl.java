package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.domain.dto.SysMenuCreateDTO;
import com.cloudinfra.system.domain.dto.SysMenuUpdateDTO;
import com.cloudinfra.system.converter.SysMenuConverter;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.mapper.SysMenuMapper;
import com.cloudinfra.system.service.ISysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements ISysMenuService {

    private final SysMenuConverter menuConverter;

    @Override
    public List<SysMenu> selectMenuList(SysMenu menu) {
        List<SysMenu> menus = list();
        return buildTree(menus, 0L);
    }

    @Override
    public List<SysMenu> selectMenuTree() {
        List<SysMenu> menus = list();
        return buildTree(menus, 0L);
    }

    @Override
    public int insertMenu(SysMenuCreateDTO menuDTO) {
        // 1. 业务唯一性校验：权限标识不能重复
        if (this.getOne(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getPerms, menuDTO.getPerms())) != null) {
            throw new SystemBusinessException("权限标识 [" + menuDTO.getPerms() + "] 已存在");
        }

        // 2. 转换为实体
        SysMenu menu = menuConverter.createDtoToEntity(menuDTO);
        return save(menu) ? 1 : 0;
    }

    @Override
    public int updateMenu(SysMenuUpdateDTO menuDTO) {
        SysMenu menu = menuConverter.updateDtoToEntity(menuDTO);
        
        // 校验更新后的权限标识是否与其他菜单冲突
        SysMenu existing = this.getOne(new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getPerms, menuDTO.getPerms())
            .ne(SysMenu::getId, menuDTO.getMenuId()));
        if (existing != null) {
            throw new SystemBusinessException("权限标识 [" + menuDTO.getPerms() + "] 已被其他菜单占用");
        }
        
        return updateById(menu) ? 1 : 0;
    }

    @Override
    public int deleteMenuById(Long menuId) {
        return removeById(menuId) ? 1 : 0;
    }

    private List<SysMenu> buildTree(List<SysMenu> menus, Long parentId) {
        List<SysMenu> tree = menus.stream()
                .filter(m -> m.getParentId().equals(parentId))
                .collect(Collectors.toList());
        for (SysMenu menu : tree) {
            menu.setChildren(buildTree(menus, menu.getId()));
        }
        return tree;
    }
}
