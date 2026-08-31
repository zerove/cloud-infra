package ${config.packageName}.converter;

import ${config.packageName}.domain.${table.className};
import ${config.packageName}.domain.dto.${table.className}CreateDTO;
import ${config.packageName}.domain.dto.${table.className}UpdateDTO;
import ${config.packageName}.domain.vo.${table.className}VO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ${table.comment} 转换器
 */
@Component
public class ${table.className}Converter {

    public ${table.className} createDtoToEntity(${table.className}CreateDTO dto) {
        if (dto == null) {
            return null;
        }
        ${table.className} entity = new ${table.className}();
<#list table.fields as field>
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
        entity.set${field.propertyName?cap_first}(dto.get${field.propertyName?cap_first}());
    </#if>
</#list>
        return entity;
    }

    public ${table.className} updateDtoToEntity(${table.className}UpdateDTO dto) {
        if (dto == null) {
            return null;
        }
        ${table.className} entity = new ${table.className}();
<#if table.primaryKey??>
        entity.set${table.primaryKey.propertyName?cap_first}(dto.get${table.primaryKey.propertyName?cap_first}());
</#if>
<#list table.fields as field>
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
        entity.set${field.propertyName?cap_first}(dto.get${field.propertyName?cap_first}());
    </#if>
</#list>
        return entity;
    }

    public ${table.className}VO entityToVo(${table.className} entity) {
        if (entity == null) {
            return null;
        }
<#if table.baseEntity>
        return new ${table.className}VO(
            entity.get${table.primaryKey.propertyName?cap_first}(),
<#else>
        ${table.className}VO vo = new ${table.className}VO();
        vo.set${table.primaryKey.propertyName?cap_first}(entity.get${table.primaryKey.propertyName?cap_first}());
</#if>
<#list table.fields as field>
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
<#if table.baseEntity>
            entity.get${field.propertyName?cap_first}(),
<#else>
        vo.set${field.propertyName?cap_first}(entity.get${field.propertyName?cap_first}());
</#if>
    </#if>
</#list>
<#if table.baseEntity>
            entity.getCreateTime(),
            entity.getUpdateTime()
        );
<#else>
        return vo;
</#if>
    }

    public List<${table.className}VO> entityListToVoList(List<${table.className}> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(this::entityToVo).collect(Collectors.toList());
    }
}
