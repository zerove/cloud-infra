package com.cloudinfra.cloudinfra.auth.result;
import lombok.AllArgsConstructor;
import lombok.Data;
@Data @AllArgsConstructor public class Result<T> {
    private int code;
    private String msg;
    private T data;
    public static <T> Result<T> success(T data) { return new Result<>(200, "success", data); }
}
