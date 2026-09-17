package com.cloudinfra.system.grpc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;
import com.cloudinfra.system.service.ISysUserService;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

import static com.cloudinfra.system.grpc.GrpcAdapterSupport.*;

/** 实验性适配层。鉴权尚未完成，默认禁用，禁止生产开启。 */
@Component
@ConditionalOnProperty(prefix = "cloudinfra.system.grpc", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class SysUserGrpcService extends SysUserServiceGrpc.SysUserServiceImplBase {
    private final ISysUserService userService;
    private final Validator validator;

    @Override
    public void getUser(GetUserRequest request, StreamObserver<UserResponse> observer) {
        respond(observer, () -> toUserResponse(found(userService.selectUserById(id(request.getUserId())))));
    }

    @Override
    public void listUsers(ListUsersRequest request, StreamObserver<ListUsersResponse> observer) {
        respond(observer, () -> {
            Page<SysUser> page = new Page<>(pageNum(request.getPageNum()), pageSize(request.getPageSize()));
            SysUser query = new SysUser();
            query.setUsername(request.getUsername().isEmpty() ? null : request.getUsername());
            if (!request.getStatus().isEmpty()) query.setStatus(status(request.getStatus()));
            query.setDeptId(filterDeptId(request.getDeptId()));
            var result = userService.selectUserList(page, query);
            return ListUsersResponse.newBuilder()
                .addAllRecords(result.getRecords().stream().map(this::toUserResponse).toList())
                .setTotal(result.getTotal()).setPageNum((int) result.getCurrent()).setPageSize((int) result.getSize()).build();
        });
    }

    @Override
    public void createUser(CreateUserRequest request, StreamObserver<UserResponse> observer) {
        respond(observer, () -> {
            require(request.getSex().isEmpty(), "sex is not supported");
            require(request.getRoleIdsCount() <= 1 && request.getPostIdsCount() <= 1, "Only one role and one post are supported");
            SysUserCreateDTO dto = new SysUserCreateDTO();
            dto.setUsername(request.getUsername());
            dto.setPassword(request.getPassword());
            dto.setNickName(request.getNickname());
            dto.setEmail(request.getEmail());
            dto.setPhoneNumber(request.getPhone());
            require(request.getDeptId() >= 0, "dept_id must not be negative");
            if (request.getDeptId() > 0) dto.setDeptId(request.getDeptId());
            if (request.getRoleIdsCount() == 1) dto.setRoleId(id(request.getRoleIds(0)));
            if (request.getPostIdsCount() == 1) dto.setPostId(id(request.getPostIds(0)));
            validate(validator, dto);
            Long createdId = userService.createUserReturningId(dto);
            return toUserResponse(found(userService.selectUserById(found(createdId))));
        });
    }

    @Override
    public void updateUser(UpdateUserRequest request, StreamObserver<UserResponse> observer) {
        respond(observer, () -> {
            require(!request.hasSex(), "sex is not supported");
            require(request.getRoleIdsCount() <= 1 && request.getPostIdsCount() <= 1, "Only one role and one post are supported");
            SysUserUpdateDTO dto = new SysUserUpdateDTO();
            dto.setUserId(id(request.getUserId()));
            if (request.hasNickname()) dto.setNickName(request.getNickname());
            if (request.hasEmail()) dto.setEmail(request.getEmail());
            if (request.hasPhone()) dto.setPhoneNumber(request.getPhone());
            if (request.hasStatus()) dto.setStatus(status(request.getStatus()));
            if (request.hasDeptId()) dto.setDeptId(id(request.getDeptId()));
            if (request.getRoleIdsCount() == 1) dto.setRoleId(id(request.getRoleIds(0)));
            if (request.getPostIdsCount() == 1) dto.setPostId(id(request.getPostIds(0)));
            validate(validator, dto);
            if (userService.updateUser(dto) == 0) return toUserResponse(found(null));
            return toUserResponse(found(userService.selectUserById(request.getUserId())));
        });
    }

    @Override
    public void deleteUser(DeleteUserRequest request, StreamObserver<DeleteUserResponse> observer) {
        respond(observer, () -> {
            require(request.getUserIdsCount() > 0 && request.getUserIdsCount() <= 100, "Provide between 1 and 100 user IDs");
            Long[] ids = request.getUserIdsList().stream().map(GrpcAdapterSupport::id).distinct().toArray(Long[]::new);
            return DeleteUserResponse.newBuilder().setAffectedRows(userService.deleteUserByIds(ids)).build();
        });
    }

    // ResetPassword 故意保留基类 UNIMPLEMENTED，待 P2 鉴权协议完成后再开放。

    private UserResponse toUserResponse(SysUser user) {
        UserResponse.Builder builder = UserResponse.newBuilder()
            .setUserId(number(user.getUserId())).setUsername(text(user.getUsername()))
            .setNickname(text(user.getNickName())).setEmail(text(user.getEmail()))
            .setPhone(text(user.getPhoneNumber())).setStatus(user.getStatus() == null ? "" : user.getStatus().toString())
            .setDeptId(number(user.getDeptId()));
        if (user.getCreateTime() != null) builder.setCreateTime(user.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        if (user.getUpdateTime() != null) builder.setUpdateTime(user.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return builder.build();
    }
}
