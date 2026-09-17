package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.system.converter.SysDeptConverter;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.mapper.SysDeptMapper;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部门服务单元测试
 * 覆盖：列表查询（过滤排序）、树构建、同级重名校验、祖级路径计算、更新、删除
 */
@ExtendWith(MockitoExtension.class)
public class SysDeptServiceImplTest {

    @Mock
    private SysDeptMapper deptMapper;

    @Mock
    private SysDeptConverter deptConverter;

    @InjectMocks
    private SysDeptServiceImpl deptService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(deptService, "baseMapper", deptMapper);
    }

    @Test
    void shouldListDeptsWithFilters() {
        SysDept query = new SysDept();
        query.setDeptName("研发");
        query.setParentId(0L);
        query.setStatus("0");
        when(deptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(query));

        List<SysDept> result = deptService.selectDeptList(query);

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldBuildDeptTree() {
        SysDept root = new SysDept();
        root.setDeptId(1L);
        root.setParentId(0L);

        SysDept child = new SysDept();
        child.setDeptId(2L);
        child.setParentId(1L);

        SysDept orphan = new SysDept();
        orphan.setDeptId(3L);
        orphan.setParentId(99L);

        when(deptMapper.selectDeptTree()).thenReturn(List.of(root, child, orphan));

        List<SysDept> tree = deptService.buildDeptTree(List.of(root, child, orphan));

        // 根节点被挂载 children，孤儿节点兜底返回
        assertThat(tree).isNotEmpty();
        SysDept rootNode = tree.stream().filter(d -> d.getDeptId() == 1L).findFirst().orElseThrow();
        assertThat(rootNode.getChildren()).hasSize(1);
        assertThat(rootNode.getChildren().get(0).getDeptId()).isEqualTo(2L);
    }

    @Test
    void shouldRejectDuplicateSiblingDeptName() {
        SysDeptCreateDTO dto = new SysDeptCreateDTO();
        dto.setDeptName("财务部");
        dto.setParentId(0L);

        when(deptMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(new SysDept());

        assertThatThrownBy(() -> deptService.insertDept(dto))
            .isInstanceOf(SystemBusinessException.class)
            .hasMessageContaining("同级部门已存在");

        verify(deptMapper, never()).insert(any(SysDept.class));
    }

    @Test
    void shouldInsertDeptWithAncestorsFromParent() {
        SysDeptCreateDTO dto = new SysDeptCreateDTO();
        dto.setDeptName("研发部");
        dto.setParentId(10L);

        SysDept parent = new SysDept();
        parent.setDeptId(10L);
        parent.setAncestors("0,5");

        SysDept entity = new SysDept();
        entity.setDeptName("研发部");
        entity.setParentId(10L);
        when(deptMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        when(deptConverter.createDtoToEntity(dto)).thenReturn(entity);
        when(deptMapper.selectById(10L)).thenReturn(parent);
        when(deptMapper.insert(any(SysDept.class))).thenReturn(1);

        boolean result = deptService.insertDept(dto);

        assertThat(result).isTrue();
        assertThat(entity.getAncestors()).isEqualTo("0,5,10");
    }

    @Test
    void shouldInsertRootDeptWithAncestorsZero() {
        SysDeptCreateDTO dto = new SysDeptCreateDTO();
        dto.setDeptName("总公司");
        dto.setParentId(0L);

        SysDept entity = new SysDept();
        entity.setDeptName("总公司");
        entity.setParentId(0L);
        when(deptMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        when(deptConverter.createDtoToEntity(dto)).thenReturn(entity);
        // parent 0 查出为 null
        when(deptMapper.selectById(0L)).thenReturn(null);
        when(deptMapper.insert(any(SysDept.class))).thenReturn(1);

        boolean result = deptService.insertDept(dto);

        assertThat(result).isTrue();
        assertThat(entity.getAncestors()).isEqualTo("0");
    }

    @Test
    void shouldUpdateDeptAndRecalculateAncestors() {
        SysDeptUpdateDTO dto = new SysDeptUpdateDTO();
        dto.setDeptId(3L);
        dto.setParentId(10L);
        dto.setDeptName("新部门");

        SysDept entity = new SysDept();
        entity.setDeptId(3L);
        entity.setParentId(10L);

        SysDept newParent = new SysDept();
        newParent.setDeptId(10L);
        newParent.setAncestors("0,5");

        SysDept oldDept = new SysDept();
        oldDept.setDeptId(3L);
        oldDept.setAncestors("0,2");

        when(deptConverter.updateDtoToEntity(dto)).thenReturn(entity);
        when(deptMapper.selectById(10L)).thenReturn(newParent);
        when(deptMapper.selectById(3L)).thenReturn(oldDept);
        when(deptMapper.updateById(any(SysDept.class))).thenReturn(1);

        boolean result = deptService.updateDept(dto);

        assertThat(result).isTrue();
        assertThat(entity.getAncestors()).isEqualTo("0,5,10");
    }

    @Test
    void shouldDeleteDept() {
        when(deptMapper.deleteById(any(Serializable.class))).thenReturn(1);

        boolean result = deptService.deleteDeptById(3L);

        assertThat(result).isTrue();
        verify(deptMapper).deleteById(any(Serializable.class));
    }

    @Test
    void shouldPropagateExceptionWhenInsertDeptWithException() {
        SysDeptCreateDTO dto = new SysDeptCreateDTO();
        dto.setDeptName("回滚测试");
        dto.setParentId(0L);
        when(deptMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        SysDept entity = new SysDept();
        entity.setParentId(0L);
        when(deptConverter.createDtoToEntity(dto)).thenReturn(entity);
        when(deptMapper.selectById(0L)).thenReturn(null);
        when(deptMapper.insert(any(SysDept.class))).thenReturn(1);

        assertThatThrownBy(() -> deptService.insertDeptWithException(dto))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("rollback");
    }
    @Test
    void shouldReturnGeneratedDeptIdWithoutPostInsertScan() {
        SysDeptCreateDTO dto = new SysDeptCreateDTO();
        dto.setDeptName("研发部");
        dto.setParentId(0L);
        SysDept entity = new SysDept();
        entity.setParentId(0L);
        when(deptConverter.createDtoToEntity(dto)).thenReturn(entity);
        when(deptMapper.insert(any(SysDept.class))).thenAnswer(call -> {
            ((SysDept) call.getArgument(0)).setDeptId(73L);
            return 1;
        });
        assertThat(deptService.createDeptReturningId(dto)).isEqualTo(73L);
        verify(deptMapper, never()).selectList(any(Wrapper.class));
        verify(deptMapper, times(1)).selectById(any(Serializable.class));
    }

    @Test
    void shouldRejectMissingGeneratedDeptId() {
        SysDeptCreateDTO dto = new SysDeptCreateDTO();
        dto.setDeptName("研发部");
        dto.setParentId(0L);
        SysDept entity = new SysDept();
        entity.setParentId(0L);
        when(deptConverter.createDtoToEntity(dto)).thenReturn(entity);
        when(deptMapper.insert(any(SysDept.class))).thenReturn(1);
        assertThatThrownBy(() -> deptService.createDeptReturningId(dto))
            .isInstanceOf(IllegalStateException.class);
    }

}