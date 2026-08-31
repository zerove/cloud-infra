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
 * ${table.comment} - 创建请求 DTO
 */
</#if>
@Data
public class ${table.className}CreateDTO {
<#list table.fields as field>
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
    <#if field.javaType == "java.lang.String">
    @NotBlank(message = "${field.comment}不能为空")
    <#else>
    @NotNull(message = "${field.comment}不能为空")
    </#if>
    <#if field.length?? && field.length < 255 && field.javaType == "java.lang.String">
    @Size(max = ${field.length}, message = "${field.comment}长度不能超过${field.length}")
    </#if>
    private ${field.getSimpleJavaType()} ${field.propertyName};
    </#if>
</#list>
}
