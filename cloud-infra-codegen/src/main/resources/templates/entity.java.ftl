package ${config.packageName}.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
<#if table.baseEntity>
import com.cloudinfra.common.core.domain.BaseEntity;
</#if>
import lombok.Data;
import lombok.EqualsAndHashCode;

<#if table.comment??>
/**
 * ${table.comment} ${table.tableName}
 */
</#if>
@Data
@EqualsAndHashCode(callSuper = ${table.baseEntity?string('true', 'false')})
@TableName("${table.tableName}")
public class ${table.className} <#if table.baseEntity>extends BaseEntity</#if> {
<#list table.fields as field>
    <#if field.primaryKey>
    /** ${field.comment} */
    @TableId(type = IdType.${field.idType?string})
    private ${field.getSimpleJavaType()} ${field.propertyName};
    <#else>
    <#if field.logicDelete>
    /** ${field.comment} (逻辑删除) */
    @TableField("`" + ${field.delval} + "`")
    private ${field.getSimpleJavaType()} ${field.propertyName};
    <#else>
    /** ${field.comment} */
    <#if field.fill>
    @TableField(fill = FieldFill.${field.fillStrategy?upper_case})
    <#else>
    @TableField
    </#if>
    private ${field.getSimpleJavaType()} ${field.propertyName};
    </#if>
</#if>
</#list>
}
