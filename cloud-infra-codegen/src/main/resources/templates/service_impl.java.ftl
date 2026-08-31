package ${config.packageName}.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import ${config.packageName}.domain.${table.className};
import ${config.packageName}.domain.dto.${table.className}CreateDTO;
import ${config.packageName}.domain.dto.${table.className}UpdateDTO;
import ${config.packageName}.domain.vo.${table.className}VO;
import ${config.packageName}.mapper.${table.className}Mapper;
import ${config.packageName}.service.I${table.className}Service;
import ${config.packageName}.converter.${table.className}Converter;
import com.cloudinfra.common.core.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ${table.comment} Service 实现
 */
@Service
@RequiredArgsConstructor
public class ${table.className}ServiceImpl extends ServiceImpl<${table.className}Mapper, ${table.className}> implements I${table.className}Service {

    private final ${table.className}Converter ${table.classAliasName}Converter;

    @Override
    public List<${table.className}VO> select${table.className}List(${table.className} query) {
        return ${table.classAliasName}Converter.entityListToVoList(list(buildQueryWrapper(query)));
    }

    @Override
    public IPage<${table.className}VO> select${table.className}Page(IPage<${table.className}> page, ${table.className} query) {
        IPage<${table.className}> result = page(page, buildQueryWrapper(query));
        return result.convert(${table.classAliasName}Converter::entityToVo);
    }

    @Override
    public ${table.className}VO select${table.className}ById(${table.primaryKey.getSimpleJavaType()} id) {
        ${table.className} entity = getById(id);
        if (entity == null) {
            throw new BusinessException("${table.comment}不存在，ID: " + id);
        }
        return ${table.classAliasName}Converter.entityToVo(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insert${table.className}(${table.className}CreateDTO dto) {
        ${table.className} entity = ${table.classAliasName}Converter.createDtoToEntity(dto);
        save(entity);
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int update${table.className}(${table.className}UpdateDTO dto) {
        ${table.className} entity = ${table.classAliasName}Converter.updateDtoToEntity(dto);
        updateById(entity);
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delete${table.className}ByIds(${table.primaryKey.getSimpleJavaType()}[] ids) {
        return removeByIds(List.of(ids)) ? 1 : 0;
    }

    private LambdaQueryWrapper<${table.className}> buildQueryWrapper(${table.className} query) {
        LambdaQueryWrapper<${table.className}> wrapper = new LambdaQueryWrapper<>();
<#list table.fields as field>
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
        if (query.get${field.propertyName?cap_first}() != null) {
            <#if field.javaType == "java.lang.String">
            wrapper.like(${table.className}::get${field.propertyName?cap_first}, query.get${field.propertyName?cap_first}());
            <#else>
            wrapper.eq(${table.className}::get${field.propertyName?cap_first}, query.get${field.propertyName?cap_first}());
            </#if>
        }
    </#if>
</#list>
        return wrapper;
    }
}
