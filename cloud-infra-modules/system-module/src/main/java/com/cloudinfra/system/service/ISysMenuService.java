package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysMenu;
import java.util.List;

public interface ISysMenuService extends IService<SysMenu> {
    List<SysMenu> selectMenuList(SysMenu menu);
    List<SysMenu> selectMenuTree();
    int insertMenu(SysMenu menu);
    int updateMenu(SysMenu menu);
    int deleteMenuById(Long menuId);
}
