package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.mapper.SysMenuMapper;
import com.cloudinfra.system.service.SysMenuService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    @Override
    public List<SysMenu> treeList() {
        List<SysMenu> menus = list();
        return buildTree(menus, 0L);
    }

    @Override
    public void addMenu(SysMenu menu) {
        save(menu);
    }

    @Override
    public void updateMenu(SysMenu menu) {
        updateById(menu);
    }

    @Override
    public void deleteMenu(Long menuId) {
        removeById(menuId);
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
