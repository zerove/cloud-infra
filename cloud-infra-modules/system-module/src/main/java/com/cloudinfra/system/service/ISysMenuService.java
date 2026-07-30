package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.domain.dto.SysMenuCreateDTO;
import com.cloudinfra.system.domain.dto.SysMenuUpdateDTO;
import java.util.List;

public interface ISysMenuService extends IService<SysMenu> {
    List<SysMenu> selectMenuList(SysMenu menu);
    List<SysMenu> selectMenuTree();
    int insertMenu(SysMenuCreateDTO menuDTO);
    int updateMenu(SysMenuUpdateDTO menuDTO);
    int deleteMenuById(Long menuId);
}
