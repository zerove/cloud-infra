package ${package}.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import ${package}.domain.${t.className};
import ${package}.mapper.${t.className}Mapper;
import ${package}.service.I${t.className}Service;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ${t.className}ServiceImpl extends ServiceImpl<${t.className}Mapper, ${t.className}> implements I${t.className}Service {

    @Override
    public List<${t.className}> listByCondition(${t.className} query) {
        LambdaQueryWrapper<${t.className}> wrapper = new LambdaQueryWrapper<>();
        // TODO: 根据 query 字段动态构造条件
        return list(wrapper);
    }
}
