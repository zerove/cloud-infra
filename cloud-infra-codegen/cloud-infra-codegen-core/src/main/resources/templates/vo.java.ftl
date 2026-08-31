package ${config.packageName}.domain.vo;

import lombok.Data;
<#assign hasDateTime = false>
<#assign hasBigDecimal = false>
<#list table.fields as field>
<#if field.javaType == "java.time.LocalDateTime" || field.javaType == "java.time.LocalDate" || field.javaType == "java.time.LocalTime">
<#assign hasDateTime = true>
</#if>
<#if field.javaType == "java.math.BigDecimal">
<#assign hasBigDecimal = true>
</#if>
</#list>
<#if hasDateTime>
import java.time.*;
</#if>
<#if hasBigDecimal>
import java.math.BigDecimal;
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
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
    private ${field.getSimpleJavaType()} ${field.propertyName};
    </#if>
</#list>

<#if table.baseEntity>
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
</#if>
}
