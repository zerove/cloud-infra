package com.cloudinfra.file.service;

import java.io.IOException;
import java.io.InputStream;

/**
 * 存储策略接口 - 定义物理存储的统一原语
 */
public interface StorageStrategy {
    /**
     * 上传文件
     * @param fileName 文件名
     * @param inputStream 文件输入流
     * @return 存储后的相对路径/标识
     * @throws IOException 存储异常
     */
    String upload(String fileName, InputStream inputStream) throws IOException;

    /**
     * 下载文件
     * @param path 文件路径
     * @return 文件输入流
     * @throws IOException 存储异常
     */
    InputStream download(String path) throws IOException;

    /**
     * 删除文件
     * @param path 文件路径
     * @return 是否删除成功
     */
    boolean delete(String path);

    /**
     * 检查文件是否存在
     * @param path 文件路径
     * @return 是否存在
     */
    boolean exists(String path);
}
