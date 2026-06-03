package com.cloudinfra.file.service;

import com.cloudinfra.file.domain.FileContent;
import java.io.IOException;

public interface IFileService {
    /**
     * 上传文件
     * @param content 文件领域内容
     * @return 文件访问路径
     */
    String upload(FileContent content) throws IOException;

    /**
     * 删除文件
     * @param filePath 文件路径
     * @return 是否成功
     */
    boolean delete(String filePath);
}

