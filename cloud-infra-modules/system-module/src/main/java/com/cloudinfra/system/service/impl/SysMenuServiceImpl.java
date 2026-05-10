package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.mapper.SysMenuMapper;
import com.cloudinfra.system.service.ISysMenuService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements ISysMenuService {

    @Override
    public List<SysMenu> selectMenuList(SysMenu menu) {
        return list();
    }

    @Override
    public List<SysMenu> selectMenuTree() {
        List<SysMenu> menus = list();
        return buildTree(menus, 0L);
    }

    @Override
    public int insertMenu(SysMenu menu) {
        return save(menu) ? 1 : 0;
    }

    @Override
    public int updateMenu(SysMenu menu) {
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
