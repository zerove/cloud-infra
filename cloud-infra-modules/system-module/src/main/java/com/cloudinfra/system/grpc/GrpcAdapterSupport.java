package com.cloudinfra.system.grpc;

import com.cloudinfra.system.exception.SystemBusinessException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/** 仅用于实验性业务适配层的边界校验与固定错误映射。 */
@Slf4j
final class GrpcAdapterSupport {
    private GrpcAdapterSupport() {}

    static <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        T response;
        try {
            response = action.get();
        } catch (RequestFailure failure) {
            observer.onError(failure.status.asRuntimeException());
            return;
        } catch (SystemBusinessException failure) {
            Status status = "USER_NOT_FOUND".equals(failure.getErrorCode())
                ? Status.NOT_FOUND.withDescription("Resource not found")
                : Status.FAILED_PRECONDITION.withDescription("Business operation rejected");
            observer.onError(status.asRuntimeException());
            return;
        } catch (RuntimeException failure) {
            log.error("System gRPC operation failed", failure);
            observer.onError(Status.INTERNAL.withDescription("Internal server error").asRuntimeException());
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }

    static void require(boolean valid, String message) {
        if (!valid) throw new RequestFailure(Status.INVALID_ARGUMENT.withDescription(message));
    }

    static void validate(Validator validator, Object dto) {
        require(validator.validate(dto).isEmpty(), "Invalid request fields");
    }

    static long id(long value) {
        require(value > 0, "ID must be positive");
        return value;
    }

    static int pageNum(int value) {
        require(value >= 0 && value <= 100_000, "page_num must be between 0 and 100000");
        return value == 0 ? 1 : value;
    }

    static int pageSize(int value) {
        require(value >= 0 && value <= 100, "page_size must be between 0 and 100");
        return value == 0 ? 20 : value;
    }

    static Integer status(String value) {
        require("0".equals(value) || "1".equals(value), "status must be 0 or 1");
        return Integer.valueOf(value);
    }

    static Long filterDeptId(String value) {
        if (value.isEmpty()) return null;
        try {
            return id(Long.parseLong(value));
        } catch (NumberFormatException failure) {
            throw new RequestFailure(Status.INVALID_ARGUMENT.withDescription("dept_id must be a positive integer"));
        }
    }

    static <T> T found(T value) {
        if (value == null) throw new RequestFailure(Status.NOT_FOUND.withDescription("Resource not found"));
        return value;
    }

    static String text(String value) { return value == null ? "" : value; }
    static long number(Long value) { return value == null ? 0 : value; }

    private static final class RequestFailure extends RuntimeException {
        private final Status status;
        private RequestFailure(Status status) { this.status = status; }
    }
}
