package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysMenu;
import java.util.List;

public interface SysMenuService {
    List<SysMenu> treeList();
    void addMenu(SysMenu menu);
    void updateMenu(SysMenu menu);
    void deleteMenu(Long menuId);
}
