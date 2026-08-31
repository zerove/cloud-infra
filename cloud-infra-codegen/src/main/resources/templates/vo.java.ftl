package ${config.packageName}.domain.vo;

import lombok.Data;

<#if table.baseEntity>
import java.time.LocalDateTime;
</#if>

<#if table.comment??>
/**
 * ${table.comment} - 响应 VO
 */
</#if>
@Data
public class ${table.className}VO {
<#if table.primaryKey??>
    private ${table.primaryKey.getSimpleJavaType()} ${table.primaryKey.propertyName};
</#if>

<#list table.fields as field>
    <#if !field.primaryKey && !field.fill>
    private ${field.getSimpleJavaType()} ${field.propertyName};
    </#if>
</#list>

<#if table.baseEntity>
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
</#if>
}
