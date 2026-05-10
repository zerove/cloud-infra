package com.cloudinfra.file.service;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

public interface IFileService {
    /**
     * 上传文件
     * @param file 文件对象
     * @return 文件访问路径
     */
    String upload(MultipartFile file) throws IOException;

    /**
     * 删除文件
     * @param filePath 文件路径
     * @return 是否成功
     */
    boolean delete(String filePath);
}
