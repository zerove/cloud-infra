package ${config.packageName}.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ${config.packageName}.domain.${table.className};
import org.apache.ibatis.annotations.Mapper;

/**
 * ${table.comment} Mapper 接口
 */
@Mapper
public interface ${table.className}Mapper extends BaseMapper<${table.className}> {
}
