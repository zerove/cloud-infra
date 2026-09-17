package com.cloudinfra.system.grpc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.service.ISysDeptService;
import com.cloudinfra.system.service.ISysUserService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SystemGrpcAdapterTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private final ISysUserService users = mock(ISysUserService.class);
    private final ISysDeptService depts = mock(ISysDeptService.class);
    private final SysUserGrpcService userGrpc = new SysUserGrpcService(users, FACTORY.getValidator());
    private final SysDeptGrpcService deptGrpc = new SysDeptGrpcService(depts, FACTORY.getValidator());

    @AfterAll
    static void closeValidator() { FACTORY.close(); }

    @Test
    void nullableDatabaseFieldsAreSafeForBothResponsesAndTree() {
        when(users.selectUserById(1L)).thenReturn(new SysUser());
        when(depts.getById(1L)).thenReturn(new SysDept());
        Recorder<UserResponse> user = new Recorder<>();
        Recorder<DeptResponse> dept = new Recorder<>();
        userGrpc.getUser(GetUserRequest.newBuilder().setUserId(1).build(), user);
        deptGrpc.getDept(GetDeptRequest.newBuilder().setDeptId(1).build(), dept);
        assertThat(user.value).isEqualTo(UserResponse.getDefaultInstance());
        assertThat(dept.value).isEqualTo(DeptResponse.getDefaultInstance());
        assertThat(user.completed && dept.completed).isTrue();
        when(depts.buildDeptTree(any())).thenReturn(List.of(new SysDept()));
        Recorder<DeptTreeResponse> tree = new Recorder<>();
        deptGrpc.getDeptTree(GetDeptTreeRequest.getDefaultInstance(), tree);
        assertThat(tree.value.getNodesCount()).isEqualTo(1);
    }

    @Test
    void defaultsUseBoundedPageAndNoAccidentalDeptZeroFilter() {
        when(users.selectUserList(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(depts.selectDeptList(any(Page.class), any(SysDept.class))).thenAnswer(call -> call.getArgument(0));
        Recorder<ListUsersResponse> user = new Recorder<>();
        Recorder<ListDeptsResponse> dept = new Recorder<>();
        userGrpc.listUsers(ListUsersRequest.getDefaultInstance(), user);
        deptGrpc.listDepts(ListDeptsRequest.getDefaultInstance(), dept);
        assertThat(user.value.getPageNum()).isEqualTo(1);
        assertThat(user.value.getPageSize()).isEqualTo(20);
        assertThat(dept.value.getPageSize()).isEqualTo(20);
        ArgumentCaptor<SysUser> filter = ArgumentCaptor.forClass(SysUser.class);
        verify(users).selectUserList(any(), filter.capture());
        assertThat(filter.getValue().getDeptId()).isNull();
    }

    @Test
    void invalidPaginationNeverReachesBusinessServices() {
        for (int size : List.of(-1, 101, Integer.MAX_VALUE)) {
            Recorder<ListUsersResponse> user = new Recorder<>();
            Recorder<ListDeptsResponse> dept = new Recorder<>();
            userGrpc.listUsers(ListUsersRequest.newBuilder().setPageSize(size).build(), user);
            deptGrpc.listDepts(ListDeptsRequest.newBuilder().setPageSize(size).build(), dept);
            user.assertError(Status.Code.INVALID_ARGUMENT);
            dept.assertError(Status.Code.INVALID_ARGUMENT);
        }
        for (int page : List.of(-1, 100001, Integer.MAX_VALUE)) {
            Recorder<ListUsersResponse> user = new Recorder<>();
            Recorder<ListDeptsResponse> dept = new Recorder<>();
            userGrpc.listUsers(ListUsersRequest.newBuilder().setPageNum(page).build(), user);
            deptGrpc.listDepts(ListDeptsRequest.newBuilder().setPageNum(page).build(), dept);
            user.assertError(Status.Code.INVALID_ARGUMENT);
            dept.assertError(Status.Code.INVALID_ARGUMENT);
        }
        verifyNoInteractions(users, depts);
    }

    @Test
    void malformedFiltersAreInvalidArgumentsNotInternalErrors() {
        for (String status : List.of("secret", "2", "999999999999999999")) {
            Recorder<ListUsersResponse> user = new Recorder<>();
            Recorder<ListDeptsResponse> dept = new Recorder<>();
            userGrpc.listUsers(ListUsersRequest.newBuilder().setStatus(status).build(), user);
            deptGrpc.listDepts(ListDeptsRequest.newBuilder().setStatus(status).build(), dept);
            user.assertError(Status.Code.INVALID_ARGUMENT);
            dept.assertError(Status.Code.INVALID_ARGUMENT);
        }
        Recorder<ListUsersResponse> user = new Recorder<>();
        userGrpc.listUsers(ListUsersRequest.newBuilder().setDeptId("not-a-number").build(), user);
        user.assertError(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(users, depts);
    }

    @Test
    void errorsNeverExposeServiceMessagesOrCauses() {
        when(users.selectUserById(anyLong())).thenThrow(new IllegalStateException("db-password=secret"));
        when(depts.getById(anyLong())).thenThrow(new SystemBusinessException("private database state"));
        Recorder<UserResponse> user = new Recorder<>();
        Recorder<DeptResponse> dept = new Recorder<>();
        userGrpc.getUser(GetUserRequest.newBuilder().setUserId(1).build(), user);
        deptGrpc.getDept(GetDeptRequest.newBuilder().setDeptId(1).build(), dept);
        user.assertError(Status.Code.INTERNAL);
        dept.assertError(Status.Code.FAILED_PRECONDITION);
        assertThat(Status.fromThrowable(user.error).getDescription()).isEqualTo("Internal server error");
        assertThat(Status.fromThrowable(dept.error).getDescription()).isEqualTo("Business operation rejected");
        assertThat(user.error.getCause()).isNull();
    }

    @Test
    void createsReadByGeneratedIdAndNeverSearchByNameOrScanAllDepts() {
        SysUser createdUser = new SysUser(); createdUser.setUserId(42L);
        SysDept createdDept = new SysDept(); createdDept.setDeptId(73L);
        when(users.createUserReturningId(any())).thenReturn(42L);
        when(users.selectUserById(42L)).thenReturn(createdUser);
        when(depts.createDeptReturningId(any())).thenReturn(73L);
        when(depts.getById(73L)).thenReturn(createdDept);
        Recorder<UserResponse> user = new Recorder<>();
        Recorder<DeptResponse> dept = new Recorder<>();
        userGrpc.createUser(CreateUserRequest.newBuilder().setUsername("alice").setPassword("test-only-password").setNickname("Alice").build(), user);
        deptGrpc.createDept(CreateDeptRequest.newBuilder().setDeptName("研发部门").build(), dept);
        assertThat(user.value.getUserId()).isEqualTo(42);
        assertThat(dept.value.getDeptId()).isEqualTo(73);
        verify(users, never()).selectUserByUsername(any());
        verify(depts, never()).selectDeptList(any(SysDept.class));
        verify(users, never()).insertUser(any());
        verify(depts, never()).insertDept(any());
    }

    @Test
    void invalidCreatesAndUnsupportedMultiValueFieldsAreRejected() {
        Recorder<UserResponse> user = new Recorder<>();
        Recorder<DeptResponse> dept = new Recorder<>();
        userGrpc.createUser(CreateUserRequest.getDefaultInstance(), user);
        deptGrpc.createDept(CreateDeptRequest.getDefaultInstance(), dept);
        user.assertError(Status.Code.INVALID_ARGUMENT);
        dept.assertError(Status.Code.INVALID_ARGUMENT);
        Recorder<UserResponse> roles = new Recorder<>();
        userGrpc.createUser(CreateUserRequest.newBuilder().addRoleIds(1).addRoleIds(2).build(), roles);
        roles.assertError(Status.Code.INVALID_ARGUMENT);
        Recorder<UserResponse> sex = new Recorder<>();
        userGrpc.updateUser(UpdateUserRequest.newBuilder().setUserId(1).setSex("1").build(), sex);
        sex.assertError(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(users, depts);
    }

    @Test
    void omittedUpdateFieldsRemainNullWhileExplicitEmptyStringIsPreserved() {
        when(users.updateUser(any())).thenReturn(1);
        when(users.selectUserById(1L)).thenReturn(new SysUser());
        when(depts.updateDept(any())).thenReturn(true);
        when(depts.getById(2L)).thenReturn(new SysDept());
        userGrpc.updateUser(UpdateUserRequest.newBuilder().setUserId(1).setEmail("").build(), new Recorder<>());
        deptGrpc.updateDept(UpdateDeptRequest.newBuilder().setDeptId(2).setLeader("").build(), new Recorder<>());
        ArgumentCaptor<SysUserUpdateDTO> user = ArgumentCaptor.forClass(SysUserUpdateDTO.class);
        ArgumentCaptor<SysDeptUpdateDTO> dept = ArgumentCaptor.forClass(SysDeptUpdateDTO.class);
        verify(users).updateUser(user.capture()); verify(depts).updateDept(dept.capture());
        assertThat(user.getValue().getEmail()).isEmpty();
        assertThat(user.getValue().getNickName()).isNull();
        assertThat(user.getValue().getDeptId()).isNull();
        assertThat(dept.getValue().getLeader()).isEmpty();
        assertThat(dept.getValue().getParentId()).isNull();
        assertThat(dept.getValue().getOrderNum()).isNull();
        assertThat(dept.getValue().getDeptName()).isNull();
    }

    @Test
    void missingRowsReturnNotFoundInsteadOfEmptySuccess() {
        Recorder<UserResponse> user = new Recorder<>();
        Recorder<DeptResponse> dept = new Recorder<>();
        userGrpc.updateUser(UpdateUserRequest.newBuilder().setUserId(1).build(), user);
        deptGrpc.updateDept(UpdateDeptRequest.newBuilder().setDeptId(2).build(), dept);
        user.assertError(Status.Code.NOT_FOUND); dept.assertError(Status.Code.NOT_FOUND);
        verify(users, never()).selectUserById(anyLong());
        verify(depts, never()).getById(anyLong());
    }

    @Test
    void emptyOrInvalidDeleteIdsAreRejectedAndDuplicatesAreDeduplicated() {
        Recorder<DeleteUserResponse> empty = new Recorder<>();
        userGrpc.deleteUser(DeleteUserRequest.getDefaultInstance(), empty);
        empty.assertError(Status.Code.INVALID_ARGUMENT);
        Recorder<DeleteDeptResponse> invalid = new Recorder<>();
        deptGrpc.deleteDept(DeleteDeptRequest.getDefaultInstance(), invalid);
        invalid.assertError(Status.Code.INVALID_ARGUMENT);
        userGrpc.deleteUser(DeleteUserRequest.newBuilder().addUserIds(1).addUserIds(1).build(), new Recorder<>());
        verify(users).deleteUserByIds(new Long[]{1L});
        verifyNoInteractions(depts);
    }

    @Test
    void requestedRootIsHonoredAndUnknownRootIsNotFound() {
        SysDept root = new SysDept(); root.setDeptId(1L);
        SysDept child = new SysDept(); child.setDeptId(2L);
        root.setChildren(List.of(child));
        when(depts.buildDeptTree(any())).thenReturn(List.of(root));
        Recorder<DeptTreeResponse> tree = new Recorder<>();
        deptGrpc.getDeptTree(GetDeptTreeRequest.newBuilder().setRootId(2).build(), tree);
        assertThat(tree.value.getNodes(0).getDeptId()).isEqualTo(2);
        Recorder<DeptTreeResponse> missing = new Recorder<>();
        deptGrpc.getDeptTree(GetDeptTreeRequest.newBuilder().setRootId(3).build(), missing);
        missing.assertError(Status.Code.NOT_FOUND);
        verify(depts, never()).selectDeptList(any(SysDept.class));
    }

    @Test
    void passwordResetRemainsUnimplementedWithoutInvokingBusinessService() {
        Recorder<ResetPasswordResponse> observer = new Recorder<>();
        userGrpc.resetPassword(ResetPasswordRequest.getDefaultInstance(), observer);
        observer.assertError(Status.Code.UNIMPLEMENTED);
        verifyNoInteractions(users);
    }

    @Test
    void typedMissingUserBusinessExceptionIsNotFound() {
        when(users.selectUserById(7L)).thenThrow(new SystemBusinessException(
            com.cloudinfra.common.core.exception.SystemExceptionCode.USER_NOT_FOUND));
        Recorder<UserResponse> observer = new Recorder<>();
        userGrpc.getUser(GetUserRequest.newBuilder().setUserId(7).build(), observer);
        observer.assertError(Status.Code.NOT_FOUND);
    }

    private static final class Recorder<T> implements StreamObserver<T> {
        T value;
        Throwable error;
        boolean completed;
        int errors;
        @Override public void onNext(T value) { this.value = value; }
        @Override public void onError(Throwable error) { this.error = error; errors++; }
        @Override public void onCompleted() { completed = true; }
        void assertError(Status.Code code) {
            assertThat(Status.fromThrowable(error).getCode()).isEqualTo(code);
            assertThat(errors).isEqualTo(1);
            assertThat(value).isNull();
            assertThat(completed).isFalse();
        }
    }
}
