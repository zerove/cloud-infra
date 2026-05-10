package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysDictData;
import com.cloudinfra.system.domain.SysDictType;
import java.util.List;

public interface ISysDictService {
    // Dictionary Type methods
    List<SysDictType> selectDictTypeList();
    SysDictType selectDictTypeById(Long id);
    int insertDictType(SysDictType dictType);
    int updateDictType(SysDictType dictType);
    int deleteDictTypeByIds(Long[] ids);

    // Dictionary Data methods
    List<SysDictData> selectDictDataByType(String dictType);
    int insertDictData(SysDictData dictData);
    int updateDictData(SysDictData dictData);
    int deleteDictDataByIds(Long[] ids);
    
    // Cache methods
    void clearDictCache();
}
