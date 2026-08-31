package ${package}.domain;

import com.baomidou.mybatisplus.annotation.*;
<#if t.hasDateTime()>
import java.time.*;
</#if>
import lombok.Data;
import java.io.Serializable;

@Data
@TableName("${t.tableName}")
public class ${t.className} implements Serializable {
<#list t.fields as f>
    /** ${f.comment!""} */
    <#if f.isPrimaryKey>
    @TableId(type = IdType.AUTO)
    <#elseif f.isCreateBy || f.isUpdateBy>
    @TableField(fill = FieldFill.INSERT_UPDATE)
    <#elseif f.isCreateTime>
    @TableField(fill = FieldFill.INSERT)
    <#elseif f.isUpdateTime>
    @TableField(fill = FieldFill.INSERT_UPDATE)
    <#elseif f.isLogicDelete>
    @TableLogic
    <#else>
    @TableField
    </#if>
    private ${f.javaType} ${f.propertyName};
</#list>
}
