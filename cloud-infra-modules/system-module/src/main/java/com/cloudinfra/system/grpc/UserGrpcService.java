package com.cloudinfra.system.grpc;

import com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest;
import com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse;
import com.cloudinfra.api.system.grpc.GetUserByIdRequest;
import com.cloudinfra.api.system.grpc.GetUserByIdResponse;
import com.cloudinfra.api.system.grpc.GetUserByUsernameRequest;
import com.cloudinfra.api.system.grpc.GetUserByUsernameResponse;
import com.cloudinfra.api.system.grpc.RegisterUserRequest;
import com.cloudinfra.api.system.grpc.RegisterUserResponse;
import com.cloudinfra.api.system.grpc.Result;
import com.cloudinfra.api.system.grpc.UserDTO;
import com.cloudinfra.api.system.grpc.UserServiceGrpc;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.service.ISysUserService;
import com.cloudinfra.system.service.ISysPermissionService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户 gRPC 服务实现
 * 基于原生 io.grpc 生成代码，提供高性能内部服务调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final ISysUserService userService;
    private final ISysPermissionService permissionService;

    private static final Result OK = Result.newBuilder()
        .setCode(200)
        .setMessage("成功")
        .setSuccess(true)
        .build();

    private static final Result NOT_FOUND = Result.newBuilder()
        .setCode(404)
        .setMessage("用户不存在")
        .setSuccess(false)
        .build();

    @Override
    public void getUserById(GetUserByIdRequest request,
                            StreamObserver<GetUserByIdResponse> responseObserver) {
        try {
            log.debug("gRPC 获取用户: userId={}", request.getUserId());
            SysUser user = userService.selectUserById(request.getUserId());
            if (user == null) {
                responseObserver.onNext(GetUserByIdResponse.newBuilder()
                    .setResult(NOT_FOUND).build());
            } else {
                responseObserver.onNext(GetUserByIdResponse.newBuilder()
                    .setResult(OK)
                    .setUser(toUserDTO(user))
                    .build());
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("gRPC 获取用户失败: userId={}", request.getUserId(), e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void getUserByUsername(GetUserByUsernameRequest request,
                                  StreamObserver<GetUserByUsernameResponse> responseObserver) {
        try {
            log.debug("gRPC 根据用户名获取用户: username={}", request.getUsername());
            SysUser user = userService.selectUserByUsername(request.getUsername());
            if (user == null) {
                responseObserver.onNext(GetUserByUsernameResponse.newBuilder()
                    .setResult(NOT_FOUND).build());
            } else {
                responseObserver.onNext(GetUserByUsernameResponse.newBuilder()
                    .setResult(OK)
                    .setUser(toUserDTO(user))
                    .build());
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("gRPC 根据用户名获取用户失败: username={}", request.getUsername(), e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void getPermissionsByUserId(GetPermissionsByUserIdRequest request,
                                       StreamObserver<GetPermissionsByUserIdResponse> responseObserver) {
        try {
            log.debug("gRPC 获取用户权限: userId={}", request.getUserId());
            List<String> permissions = permissionService.getPermissionsByUserId(request.getUserId());
            responseObserver.onNext(GetPermissionsByUserIdResponse.newBuilder()
                .setResult(OK)
                .addAllPermissions(permissions)
                .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("gRPC 获取用户权限失败: userId={}", request.getUserId(), e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void registerUser(RegisterUserRequest request,
                             StreamObserver<RegisterUserResponse> responseObserver) {
        try {
            UserDTO dto = request.getUserDTO();
            log.debug("gRPC 注册用户: username={}", dto.getUsername());

            SysUserCreateDTO createDTO = new SysUserCreateDTO();
            createDTO.setUsername(dto.getUsername());
            createDTO.setEmail(dto.getEmail());
            createDTO.setPhoneNumber(dto.getPhone());
            createDTO.setDeptId(dto.getDeptId());
            createDTO.setRoleId(dto.getRoleId());

            userService.insertUser(createDTO);

            responseObserver.onNext(RegisterUserResponse.newBuilder()
                .setResult(OK).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("gRPC 注册用户失败", e);
            responseObserver.onError(e);
        }
    }

    /**
     * 转换 SysUser 到 gRPC UserDTO
     */
    private UserDTO toUserDTO(SysUser user) {
        return UserDTO.newBuilder()
            .setUserId(user.getUserId() != null ? user.getUserId() : 0L)
            .setUsername(user.getUsername() != null ? user.getUsername() : "")
            .setEmail(user.getEmail() != null ? user.getEmail() : "")
            .setPhone(user.getPhoneNumber() != null ? user.getPhoneNumber() : "")
            .setStatus(user.getStatus() != null ? user.getStatus() : 1)
            .setDeptId(user.getDeptId() != null ? user.getDeptId() : 0L)
            .setRoleId(user.getRoleId() != null ? user.getRoleId() : 0L)
            .build();
    }
}