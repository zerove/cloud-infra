package com.cloudinfra.system.converter;

import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import com.cloudinfra.system.domain.vo.SysDeptVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SysDeptConverter {

    public SysDept createDtoToEntity(SysDeptCreateDTO dto) {
        if (dto == null) {
            return null;
        }
        SysDept entity = new SysDept();
        entity.setDeptName(dto.getDeptName());
        entity.setParentId(dto.getParentId());
        entity.setOrderNum(dto.getOrderNum());
        entity.setLeader(dto.getLeader());
        entity.setPhone(dto.getPhone());
        entity.setEmail(dto.getEmail());
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysDept updateDtoToEntity(SysDeptUpdateDTO dto) {
        if (dto == null) {
            return null;
        }
        SysDept entity = new SysDept();
        entity.setDeptId(dto.getDeptId());
        entity.setDeptName(dto.getDeptName());
        entity.setParentId(dto.getParentId());
        entity.setOrderNum(dto.getOrderNum());
        entity.setLeader(dto.getLeader());
        entity.setPhone(dto.getPhone());
        entity.setEmail(dto.getEmail());
        entity.setStatus(dto.getStatus());
        return entity;
    }

    public SysDeptVO entityToVo(SysDept entity) {
        if (entity == null) {
            return null;
        }
        return new SysDeptVO(
            entity.getDeptId(),
            entity.getParentId() != null ? entity.getParentId().toString() : null,
            entity.getDeptName(),
            entity.getLeader(),
            entity.getPhone(),
            entity.getEmail(),
            entity.getStatus(),
            entity.getOrderNum() != null ? entity.getOrderNum().toString() : null,
            null
        );
    }

    public List<SysDeptVO> entityListToVoList(List<SysDept> list) {
        if (list == null) {
            return List.of();
        }
        return list.stream().map(this::entityToVo).collect(Collectors.toList());
    }
}
