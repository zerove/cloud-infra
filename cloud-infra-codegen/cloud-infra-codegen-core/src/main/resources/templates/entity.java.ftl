package ${config.packageName}.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
<#if table.baseEntity>
import com.cloudinfra.common.core.domain.BaseEntity;
</#if>
import lombok.Data;
import lombok.EqualsAndHashCode;
<#assign hasDateTime = false>
<#list table.fields as field>
<#if field.javaType == "java.time.LocalDateTime" || field.javaType == "java.time.LocalDate" || field.javaType == "java.time.LocalTime">
<#assign hasDateTime = true>
</#if>
</#list>
<#if hasDateTime>
import java.time.*;
</#if>

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
    <#elseif field.logicDelete>
    /** ${field.comment} (逻辑删除: 0-未删除, 1-已删除) */
    @TableLogic
    private ${field.getSimpleJavaType()} ${field.propertyName};
    <#elseif field.fill>
    /** ${field.comment} */
    @TableField(fill = FieldFill.${field.fillStrategy?upper_case})
    private ${field.getSimpleJavaType()} ${field.propertyName};
    <#else>
    /** ${field.comment} */
    <#if field.length?? && field.length < 255 && field.javaType == "java.lang.String">
    @TableField(length = ${field.length})
    <#else>
    @TableField
    </#if>
    private ${field.getSimpleJavaType()} ${field.propertyName};
    </#if>
</#list>
}
