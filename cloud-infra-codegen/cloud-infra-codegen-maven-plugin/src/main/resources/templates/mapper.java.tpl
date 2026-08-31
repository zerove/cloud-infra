package ${package}.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ${package}.domain.${t.className};
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ${t.className}Mapper extends BaseMapper<${t.className}> {
}
