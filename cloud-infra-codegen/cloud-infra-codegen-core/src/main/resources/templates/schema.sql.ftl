<#-- PostgreSQL 16 表结构生成 (项目统一使用 PG, 参考 V1__init_schema.sql 风格) -->
<#-- 由 cloud-infra-codegen 自动生成 -->
-- ====================================================================
-- ${table.comment} (${table.tableName})
-- ====================================================================

CREATE TABLE IF NOT EXISTS ${table.tableName} (
<#list table.fields as field>
    <#if field.primaryKey>
    ${field.columnName}    BIGSERIAL PRIMARY KEY<#if field_has_next>,</#if>
    <#else>
    ${field.columnName}    ${field.jdbcType}<#if field_has_next>,</#if>
    </#if>
</#list>
);

COMMENT ON TABLE ${table.tableName} IS '${table.comment}';
<#list table.fields as field>
COMMENT ON COLUMN ${table.tableName}.${field.columnName} IS '${field.comment}';
</#list>
