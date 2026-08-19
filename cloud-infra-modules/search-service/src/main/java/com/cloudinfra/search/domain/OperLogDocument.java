package com.cloudinfra.search.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
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

    @Field(type = FieldType.Text, analyzer = "standard")
    private String title;

    @Field(type = FieldType.Integer)
    private Integer businessType;

    @Field(type = FieldType.Text)
    private String method;

    @Field(type = FieldType.Keyword)
    private String operName;

    @Field(type = FieldType.Text, analyzer = "standard")
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

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime createTime;

    // Getters and Setters (manual for Lombok compatibility)
    public Long getOperId() { return operId; }
    public void setOperId(Long operId) { this.operId = operId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getBusinessType() { return businessType; }
    public void setBusinessType(Integer businessType) { this.businessType = businessType; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getOperName() { return operName; }
    public void setOperName(String operName) { this.operName = operName; }
    public String getOperUrl() { return operUrl; }
    public void setOperUrl(String operUrl) { this.operUrl = operUrl; }
    public String getOperIp() { return operIp; }
    public void setOperIp(String operIp) { this.operIp = operIp; }
    public String getOperParam() { return operParam; }
    public void setOperParam(String operParam) { this.operParam = operParam; }
    public String getJsonResult() { return jsonResult; }
    public void setJsonResult(String jsonResult) { this.jsonResult = jsonResult; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
