package ${config.packageName}.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "${table.comment!table.tableName}")
public class ${table.className}VO {
<#if table.primaryKey??>
    @Schema(description = "${table.primaryKey.comment!"ID"}")
    private ${table.primaryKey.getSimpleJavaType()} ${table.primaryKey.propertyName};
</#if>

<#list table.fields as field>
    <#if !field.primaryKey && !field.fill && !field.logicDelete>
    @Schema(description = "${field.comment!""}")
    private ${field.getSimpleJavaType()} ${field.propertyName};
    </#if>
</#list>

<#if table.baseEntity>
    @Schema(description = "创建时间")
    private java.time.LocalDateTime createTime;
    @Schema(description = "更新时间")
    private java.time.LocalDateTime updateTime;
</#if>
}
