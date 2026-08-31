package ${config.packageName}.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

<#if table.comment??>
/**
 * ${table.comment} - 创建请求 DTO
 */
</#if>
@Data
public class ${table.className}CreateDTO {
<#list table.fields as field>
    <#if !field.primaryKey>
    <#if field.notNull>
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
