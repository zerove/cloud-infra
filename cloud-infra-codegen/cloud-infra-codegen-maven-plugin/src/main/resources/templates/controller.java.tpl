package ${package}.controller;

import ${package}.domain.${t.className};
import ${package}.service.I${t.className}Service;
import com.cloudinfra.common.core.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "${t.className}")
@RestController
@RequestMapping("${cp}/${t.aliasName}")
@RequiredArgsConstructor
public class ${t.className}Controller {

    private final I${t.className}Service ${t.aliasName}Service;

    @Operation(summary = "查询列表")
    @PreAuthorize("@ss.hasPermi('${pp}:${t.aliasName}:list')")
    @GetMapping("/list")
    public Result<List<${t.className}>> list(${t.className} query) {
        return Result.success(${t.aliasName}Service.listByCondition(query));
    }
}
