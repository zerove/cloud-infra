-- ${table.comment} 表结构
CREATE TABLE `${table.tableName}` (
<#list table.fields as field>
    <#if field.primaryKey>
    `${field.columnName}` ${field.jdbcType} NOT NULL AUTO_INCREMENT COMMENT '${field.comment}',
    <#else>
    `${field.columnName}` ${field.jdbcType}<#if field.notNull> NOT NULL</#if> COMMENT '${field.comment}',
    </#if>
</#list>
    PRIMARY KEY (`${table.primaryKey.columnName}`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='${table.comment}';
