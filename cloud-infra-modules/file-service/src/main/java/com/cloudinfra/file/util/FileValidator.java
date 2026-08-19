package com.cloudinfra.file.util;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文件校验器：魔数嗅探 + 扩展名白名单 + 文件名清洗 + MIME 二次校验
 */
@Component
public class FileValidator {

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    // 扩展名白名单
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
        "jpg", "jpeg", "png", "gif", "pdf",
        "doc", "docx", "txt", "zip", "rar"
    ));

    // 魔数映射：文件类型 -> 魔数字节数组 (txt 等无魔数类型不放入 Map，改用 containsKey 判断)
    private static final Map<String, byte[]> MAGIC_BYTES = Map.of(
        "jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
        "png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
        "gif", new byte[]{0x47, 0x49, 0x46, 0x38},
        "pdf", new byte[]{0x25, 0x50, 0x44, 0x46},
        "doc", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
        "docx", new byte[]{0x50, 0x4B, 0x03, 0x04},
        "zip", new byte[]{0x50, 0x4B, 0x03, 0x04},
        "rar", new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00}
    );

    // 扩展名对应的魔数键
    private static final Map<String, String> EXT_TO_MAGIC_KEY = Map.of(
        "jpg", "jpeg", "jpeg", "jpeg", "png", "png", "gif", "gif",
        "pdf", "pdf", "doc", "doc", "docx", "docx",
        "zip", "zip", "rar", "rar", "txt", "txt"
    );

    // 危险文件名模式：路径遍历、控制字符、保留名
    private static final Pattern DANGEROUS_FILENAME = Pattern.compile(
        "[\\\\/:*?\"<>|\\x00-\\x1F]" // 路径分隔符、控制字符、Windows 保留字符
    );
    private static final Set<String> WINDOWS_RESERVED = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过限制 (最大 100MB)");
        }

        String originalFilename = file.getOriginalFilename();
        String safeFilename = sanitizeFilename(originalFilename);
        if (!safeFilename.equals(originalFilename)) {
            throw new IllegalArgumentException("文件名包含非法字符: " + originalFilename);
        }

        String extension = getExtension(safeFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new IllegalArgumentException("不支持的文件扩展名: " + extension);
        }

        String contentType = file.getContentType();
        if (contentType == null || !isContentTypeAllowed(contentType, extension)) {
            throw new IllegalArgumentException("MIME 类型不匹配或不支持: " + contentType);
        }

        // 魔数嗅探校验
        validateMagicBytes(file, extension);
    }

    /**
     * 清洗文件名：移除路径遍历、控制字符、Windows 保留名
     */
    public String sanitizeFilename(String filename) {
        if (filename == null) {
            return "";
        }
        // 仅保留文件名部分（去除路径）
        String name = filename.replaceAll("[\\\\/]", "_");
        // 移除危险字符
        name = DANGEROUS_FILENAME.matcher(name).replaceAll("_");
        // 去除首尾空格和点
        name = name.trim().replaceAll("^[.\\s]+|[.\\s]+$", "");
        // Windows 保留名检查
        String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (WINDOWS_RESERVED.contains(baseName.toUpperCase())) {
            name = "_" + name;
        }
        // 长度限制
        if (name.length() > 255) {
            String ext = getExtension(name);
            name = name.substring(0, 255 - ext.length() - 1) + "." + ext;
        }
        return name;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1) : "";
    }

    private boolean isContentTypeAllowed(String contentType, String extension) {
        return switch (extension.toLowerCase()) {
            case "jpg", "jpeg" -> contentType.equals("image/jpeg");
            case "png" -> contentType.equals("image/png");
            case "gif" -> contentType.equals("image/gif");
            case "pdf" -> contentType.equals("application/pdf");
            case "doc" -> contentType.equals("application/msword");
            case "docx" -> contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "txt" -> contentType.equals("text/plain");
            case "zip" -> contentType.equals("application/zip");
            case "rar" -> contentType.equals("application/x-rar-compressed");
            default -> false;
        };
    }

    private void validateMagicBytes(MultipartFile file, String extension) {
        String magicKey = EXT_TO_MAGIC_KEY.get(extension.toLowerCase());
        if (magicKey == null) return;
        byte[] expectedMagic = MAGIC_BYTES.get(magicKey);
        if (expectedMagic == null) return; // txt 等无魔数

        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[expectedMagic.length];
            int read = is.read(header);
            if (read != expectedMagic.length || !Arrays.equals(header, expectedMagic)) {
                throw new IllegalArgumentException("文件内容与扩展名不匹配（魔数校验失败）: " + extension);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件进行魔数校验", e);
        }
    }
}