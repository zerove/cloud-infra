package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.cloudinfra.system.domain.SysMenu;
import java.util.List;
import com.cloudinfra.system.domain.dto.SysMenuCreateDTO;
import com.cloudinfra.system.domain.dto.SysMenuUpdateDTO;

public interface ISysMenuService extends IService<SysMenu> {
    List<SysMenu> selectMenuList(SysMenu menu);
    List<SysMenu> selectMenuTree();
    int insertMenu(SysMenuCreateDTO menuDTO);
    int updateMenu(SysMenuUpdateDTO menuDTO);
    int deleteMenuById(Long menuId);
}
