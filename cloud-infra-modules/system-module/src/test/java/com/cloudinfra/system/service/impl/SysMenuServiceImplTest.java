package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.cloudinfra.system.converter.SysMenuConverter;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.domain.dto.SysMenuCreateDTO;
import com.cloudinfra.system.domain.dto.SysMenuUpdateDTO;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.mapper.SysMenuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜单服务单元测试
 * 覆盖：菜单树构建、权限标识唯一性校验（创建/更新）、删除
 */
@ExtendWith(MockitoExtension.class)
public class SysMenuServiceImplTest {

    @Mock
    private SysMenuMapper menuMapper;

    @Mock
    private SysMenuConverter menuConverter;

    @InjectMocks
    private SysMenuServiceImpl menuService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(menuService, "baseMapper", menuMapper);
    }

    private SysMenu menu(Long id, Long parentId, String name) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        m.setMenuName(name);
        return m;
    }

    @Test
    void shouldBuildMenuTree() {
        SysMenu root = menu(1L, 0L, "系统管理");
        SysMenu child = menu(2L, 1L, "用户管理");
        SysMenu grandchild = menu(3L, 2L, "用户列表");
        when(menuMapper.selectList(any(Wrapper.class))).thenReturn(List.of(root, child, grandchild));

        List<SysMenu> tree = menuService.selectMenuTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getMenuName()).isEqualTo("系统管理");
        assertThat(tree.get(0).getChildren()).hasSize(1);
        assertThat(tree.get(0).getChildren().get(0).getChildren()).hasSize(1);
    }

    @Test
    void shouldRejectDuplicatePermsOnCreate() {
        SysMenuCreateDTO dto = new SysMenuCreateDTO();
        dto.setPerms("system:user:list");
        when(menuMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(new SysMenu());

        assertThatThrownBy(() -> menuService.insertMenu(dto))
            .isInstanceOf(SystemBusinessException.class)
            .hasMessageContaining("已存在");

        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    void shouldCreateMenu() {
        SysMenuCreateDTO dto = new SysMenuCreateDTO();
        dto.setPerms("system:role:list");
        dto.setMenuName("角色管理");
        dto.setParentId(1L);

        SysMenu entity = new SysMenu();
        entity.setPerms("system:role:list");
        when(menuMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        when(menuConverter.createDtoToEntity(dto)).thenReturn(entity);
        when(menuMapper.insert(any(SysMenu.class))).thenReturn(1);

        int result = menuService.insertMenu(dto);

        assertThat(result).isEqualTo(1);
        verify(menuMapper).insert(any(SysMenu.class));
    }

    @Test
    void shouldRejectPermsConflictOnUpdate() {
        SysMenuUpdateDTO dto = new SysMenuUpdateDTO();
        dto.setMenuId(5L);
        dto.setPerms("system:user:edit");
        when(menuMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(new SysMenu());

        assertThatThrownBy(() -> menuService.updateMenu(dto))
            .isInstanceOf(SystemBusinessException.class)
            .hasMessageContaining("已被其他菜单占用");

        verify(menuMapper, never()).updateById(any(SysMenu.class));
    }

    @Test
    void shouldUpdateMenuWhenNoConflict() {
        SysMenuUpdateDTO dto = new SysMenuUpdateDTO();
        dto.setMenuId(5L);
        dto.setPerms("system:user:reset");
        SysMenu entity = new SysMenu();
        when(menuMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        when(menuConverter.updateDtoToEntity(dto)).thenReturn(entity);
        when(menuMapper.updateById(any(SysMenu.class))).thenReturn(1);

        int result = menuService.updateMenu(dto);

        assertThat(result).isEqualTo(1);
    }

    @Test
    void shouldDeleteMenu() {
        when(menuMapper.deleteById(any(Serializable.class))).thenReturn(1);

        int result = menuService.deleteMenuById(9L);

        assertThat(result).isEqualTo(1);
        verify(menuMapper).deleteById(any(Serializable.class));
    }
}