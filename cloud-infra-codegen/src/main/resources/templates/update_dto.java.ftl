package ${config.packageName}.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
<#assign hasDateTime = false>
<#assign hasBigDecimal = false>
<#list table.fields as field>
<#if !field.primaryKey && !field.fill && !field.logicDelete>
<#if field.javaType == "java.time.LocalDateTime" || field.javaType == "java.time.LocalDate" || field.javaType == "java.time.LocalTime">
<#assign hasDateTime = true>
</#if>
<#if field.javaType == "java.math.BigDecimal">
<#assign hasBigDecimal = true>
</#if>
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
 * ${table.comment} - 更新请求 DTO
 */
</#if>
@Data
public class ${table.className}UpdateDTO {
<#if table.primaryKey??>
    @NotNull(message = "ID不能为空")
    private ${table.primaryKey.getSimpleJavaType()} ${table.primaryKey.propertyName};
</#if>

<#list table.fields as field>
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
    <#if field.javaType == "java.lang.String">
    @Size(max = ${field.length!256}, message = "${field.comment}长度不能超过${field.length!256}")
    </#if>
    private ${field.getSimpleJavaType()} ${field.propertyName};
    </#if>
</#list>
}
