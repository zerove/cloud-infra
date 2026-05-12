package com.cloudinfra.auth.controller;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.framework.redis.service.RedisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "验证码管理")
@RestController
@RequestMapping("/auth")
public class CaptchaController {

    private final RedisService redisService;

    public CaptchaController(RedisService redisService) {
        this.redisService = redisService;
    }

    @Operation(summary = "生成验证码")
    @GetMapping("/captchaImage")
    public Result<Map<String, Object>> getCode() {
        // 保存验证码信息
        String uuid = UUID.randomUUID().toString();
        String verifyKey = "captcha_codes:" + uuid;

        // 生成验证码
        LineCaptcha lineCaptcha = CaptchaUtil.createLineCaptcha(120, 40);
        String code = lineCaptcha.getCode();
        String imageBase64 = lineCaptcha.getImageBase64();

        // 放入redis, 2分钟有效
        redisService.set(verifyKey, code, 120 * 1000L);

        Map<String, Object> ajax = new HashMap<>();
        ajax.put("uuid", uuid);
        ajax.put("img", imageBase64);
        return Result.success(ajax);
    }
}
