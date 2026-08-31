package ${config.packageName}.service;

import com.baomidou.mybatisplus.extension.service.IService;
import ${config.packageName}.domain.${table.className};
import ${config.packageName}.domain.dto.${table.className}CreateDTO;
import ${config.packageName}.domain.dto.${table.className}UpdateDTO;
import ${config.packageName}.domain.vo.${table.className}VO;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * ${table.comment} Service 接口
 */
public interface I${table.className}Service extends IService<${table.className}> {

    /**
     * 查询${table.comment}列表
     */
    List<${table.className}VO> select${table.className}List(${table.className} query);

    /**
     * 分页查询${table.comment}列表
     */
    IPage<${table.className}VO> select${table.className}Page(IPage<${table.className}> page, ${table.className} query);

    /**
     * 根据ID查询${table.comment}
     */
    ${table.className}VO select${table.className}ById(${table.primaryKey.getSimpleJavaType()} ${table.primaryKey.propertyName});

    /**
     * 新增${table.comment}
     */
    int insert${table.className}(${table.className}CreateDTO dto);

    /**
     * 修改${table.comment}
     */
    int update${table.className}(${table.className}UpdateDTO dto);

    /**
     * 根据ID删除${table.comment}
     */
    int delete${table.className}ByIds(${table.primaryKey.getSimpleJavaType()}[] ${table.primaryKey.propertyName}s);
}
