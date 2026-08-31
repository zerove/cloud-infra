package ${package}.service;

import com.baomidou.mybatisplus.extension.service.IService;
import ${package}.domain.${t.className};
import java.util.List;

public interface I${t.className}Service extends IService<${t.className}> {
    List<${t.className}> listByCondition(${t.className} query);
    boolean removeByIds(java.util.Collection<java.lang.Long> ids);
}
