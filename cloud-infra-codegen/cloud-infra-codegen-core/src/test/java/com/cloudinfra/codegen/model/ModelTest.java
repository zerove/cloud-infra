package com.cloudinfra.codegen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldModel / TableModel 单元测试
 */
class ModelTest {

    @Test
    void testFieldModelGetters() {
        FieldModel field = new FieldModel("user_name", "userName", "java.lang.String", "VARCHAR", "用户名", false);
        assertEquals("user_name", field.getColumnName());
        assertEquals("userName", field.getPropertyName());
        assertEquals("java.lang.String", field.getJavaType());
        assertEquals("String", field.getSimpleJavaType());
        assertEquals("VARCHAR", field.getJdbcType());
        assertEquals("用户名", field.getComment());
        assertFalse(field.isPrimaryKey());
    }

    @Test
    void testFieldModelPrimaryKey() {
        FieldModel field = new FieldModel("id", "id", "java.lang.Long", "BIGINT", "主键", true);
        assertTrue(field.isPrimaryKey());
        assertTrue(field.isNotNull());
    }

    @Test
    void testTableModelBaseFields() {
        FieldModel id = new FieldModel("id", "id", "java.lang.Long", "BIGINT", "主键", true);
        FieldModel createBy = new FieldModel("create_by", "createBy", "java.lang.String", "VARCHAR", "创建人", false);
        createBy.setFill(true);
        createBy.setFillStrategy("insert");
        FieldModel createTime = new FieldModel("create_time", "createTime", "java.time.LocalDateTime", "TIMESTAMP", "创建时间", false);
        createTime.setFill(true);
        createTime.setFillStrategy("insert");
        FieldModel updateBy = new FieldModel("update_by", "updateBy", "java.lang.String", "VARCHAR", "更新人", false);
        updateBy.setFill(true);
        updateBy.setFillStrategy("insert_update");
        FieldModel updateTime = new FieldModel("update_time", "updateTime", "java.time.LocalDateTime", "TIMESTAMP", "更新时间", false);
        updateTime.setFill(true);
        updateTime.setFillStrategy("insert_update");
        FieldModel name = new FieldModel("name", "name", "java.lang.String", "VARCHAR", "名称", false);

        TableModel table = new TableModel();
        table.setTableName("test");
        table.setClassName("Test");
        table.setBaseEntity(true);
        table.setFields(List.of(id, createBy, createTime, updateBy, updateTime, name));
        table.setPrimaryKey(id);

        List<FieldModel> baseFields = table.getBaseFields();
        assertEquals(4, baseFields.size());

        List<FieldModel> bizFields = table.getBizFields();
        assertEquals(2, bizFields.size());
        assertEquals("id", bizFields.get(0).getPropertyName());
        assertEquals("name", bizFields.get(1).getPropertyName());
    }

    @Test
    void testTableModelNoBaseEntity() {
        TableModel table = new TableModel();
        table.setBaseEntity(false);
        FieldModel id = new FieldModel("id", "id", "java.lang.Long", "BIGINT", "主键", true);
        FieldModel name = new FieldModel("name", "name", "java.lang.String", "VARCHAR", "名称", false);
        table.setFields(List.of(id, name));

        assertTrue(table.getBaseFields().isEmpty());
        assertEquals(2, table.getBizFields().size());
    }
}
