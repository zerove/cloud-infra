package com.cloudinfra.search.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

/**
 * 操作日志 ES 文档
 */
@Data
@Document(indexName = "sys_oper_log")
public class OperLogDocument {
    @Id
    private Long operId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Integer)
    private Integer businessType;

    @Field(type = FieldType.Text)
    private String method;

    @Field(type = FieldType.Keyword)
    private String operName;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String operUrl;

    @Field(type = FieldType.Ip)
    private String operIp;

    @Field(type = FieldType.Text)
    private String operParam;

    @Field(type = FieldType.Text)
    private String jsonResult;

    @Field(type = FieldType.Integer)
    private Integer status;

    @Field(type = FieldType.Text)
    private String errorMsg;

    @Field(type = FieldType.Date)
    private LocalDateTime operTime;
}
