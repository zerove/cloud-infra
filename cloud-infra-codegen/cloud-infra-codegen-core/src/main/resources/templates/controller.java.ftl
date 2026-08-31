package ${config.packageName}.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import ${config.packageName}.domain.${table.className};
import ${config.packageName}.domain.dto.${table.className}CreateDTO;
import ${config.packageName}.domain.dto.${table.className}UpdateDTO;
import ${config.packageName}.domain.vo.${table.className}VO;
import ${config.packageName}.service.I${table.className}Service;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ${table.comment} Controller
 */
@Tag(name = "${table.classAliasName}")
@RestController
@RequestMapping("${config.controllerPrefix}/${table.classAliasName}")
@RequiredArgsConstructor
public class ${table.className}Controller {

    private final I${table.className}Service ${table.classAliasName}Service;

    @Operation(summary = "查询${table.comment}列表")
    @PreAuthorize("@ss.hasPermi('${config.permissionPrefix}:${table.classAliasName}:list')")
    @GetMapping("/list")
    public IPage<${table.className}VO> list(${table.className} query,
                                           @RequestParam(defaultValue = "1") Integer pageNum,
                                           @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<${table.className}> page = new Page<>(pageNum, pageSize);
        return ${table.classAliasName}Service.select${table.className}Page(page, query);
    }

    @Operation(summary = "根据ID获取${table.comment}")
    @PreAuthorize("@ss.hasPermi('${config.permissionPrefix}:${table.classAliasName}:query')")
    @GetMapping("/{id}")
    public Result<${table.className}VO> get(@PathVariable("${table.primaryKey.propertyName}") ${table.primaryKey.getSimpleJavaType()} id) {
        return Result.success(${table.classAliasName}Service.select${table.className}ById(id));
    }

    @Operation(summary = "新增${table.comment}")
    @PreAuthorize("@ss.hasPermi('${config.permissionPrefix}:${table.classAliasName}:add')")
    @AuditLog(module = "${table.className?upper_case}", action = "ADD")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody ${table.className}CreateDTO dto) {
        ${table.classAliasName}Service.insert${table.className}(dto);
        return Result.success();
    }

    @Operation(summary = "修改${table.comment}")
    @PreAuthorize("@ss.hasPermi('${config.permissionPrefix}:${table.classAliasName}:edit')")
    @AuditLog(module = "${table.className?upper_case}", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@Valid @RequestBody ${table.className}UpdateDTO dto) {
        ${table.classAliasName}Service.update${table.className}(dto);
        return Result.success();
    }

    @Operation(summary = "删除${table.comment}")
    @PreAuthorize("@ss.hasPermi('${config.permissionPrefix}:${table.classAliasName}:remove')")
    @AuditLog(module = "${table.className?upper_case}", action = "DELETE")
    @DeleteMapping("/{ids}")
    public Result<Void> remove(@PathVariable("${table.primaryKey.propertyName}s") ${table.primaryKey.getSimpleJavaType()}[] ids) {
        ${table.classAliasName}Service.delete${table.className}ByIds(ids);
        return Result.success();
    }
}