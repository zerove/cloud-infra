package com.cloudinfra.file.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("file_permission")
public class FilePermission {
    @TableId
    private Long id;
    
    private Long fileId;
    
    /**
     * 权限主体类型: USER, ROLE, DEPT
     */
    private String subjectType;
    
    /**
     * 主体ID (用户ID, 角色ID 或 部门ID)
     */
    private String subjectId;
    
    /**
     * 权限等级: READ, WRITE, OWNER
     */
    private String permissionLevel;

    // Manual getters/setters for Lombok compatibility
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getPermissionLevel() { return permissionLevel; }
    public void setPermissionLevel(String permissionLevel) { this.permissionLevel = permissionLevel; }
}