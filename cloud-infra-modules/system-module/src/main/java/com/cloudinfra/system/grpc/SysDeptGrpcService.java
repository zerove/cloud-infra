package com.cloudinfra.system.grpc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import com.cloudinfra.system.service.ISysDeptService;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static com.cloudinfra.system.grpc.GrpcAdapterSupport.*;

/** 实验性适配层。鉴权尚未完成，默认禁用，禁止生产开启。 */
@Component
@ConditionalOnProperty(prefix = "cloudinfra.system.grpc", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class SysDeptGrpcService extends SysDeptServiceGrpc.SysDeptServiceImplBase {
    private final ISysDeptService deptService;
    private final Validator validator;

    @Override
    public void getDept(GetDeptRequest request, StreamObserver<DeptResponse> observer) {
        respond(observer, () -> toDeptResponse(found(deptService.getById(id(request.getDeptId())))));
    }

    @Override
    public void listDepts(ListDeptsRequest request, StreamObserver<ListDeptsResponse> observer) {
        respond(observer, () -> {
            Page<SysDept> page = new Page<>(pageNum(request.getPageNum()), pageSize(request.getPageSize()));
            SysDept query = new SysDept();
            if (!request.getDeptName().isEmpty()) query.setDeptName(request.getDeptName());
            if (!request.getStatus().isEmpty()) query.setStatus(status(request.getStatus()).toString());
            var result = deptService.selectDeptList(page, query);
            return ListDeptsResponse.newBuilder()
                .addAllRecords(result.getRecords().stream().map(this::toDeptResponse).toList())
                .setTotal(result.getTotal()).setPageNum((int) result.getCurrent()).setPageSize((int) result.getSize()).build();
        });
    }

    @Override
    public void createDept(CreateDeptRequest request, StreamObserver<DeptResponse> observer) {
        respond(observer, () -> {
            require(request.getParentId() >= 0 && request.getOrderNum() >= 0, "parent_id and order_num must not be negative");
            SysDeptCreateDTO dto = new SysDeptCreateDTO();
            dto.setParentId(request.getParentId());
            dto.setDeptName(request.getDeptName());
            dto.setOrderNum(request.getOrderNum());
            dto.setLeader(request.getLeader());
            dto.setPhone(request.getPhone());
            dto.setEmail(request.getEmail());
            dto.setStatus(request.getStatus().isEmpty() ? "0" : status(request.getStatus()).toString());
            validate(validator, dto);
            Long createdId = deptService.createDeptReturningId(dto);
            return toDeptResponse(found(deptService.getById(found(createdId))));
        });
    }

    @Override
    public void updateDept(UpdateDeptRequest request, StreamObserver<DeptResponse> observer) {
        respond(observer, () -> {
            SysDeptUpdateDTO dto = new SysDeptUpdateDTO();
            dto.setDeptId(id(request.getDeptId()));
            if (request.hasParentId()) {
                require(request.getParentId() >= 0 && request.getParentId() != request.getDeptId(), "Invalid parent_id");
                dto.setParentId(request.getParentId());
            }
            if (request.hasDeptName()) {
                require(!request.getDeptName().isBlank() && request.getDeptName().length() >= 2 && request.getDeptName().length() <= 20, "Invalid dept_name");
                dto.setDeptName(request.getDeptName());
            }
            if (request.hasOrderNum()) {
                require(request.getOrderNum() >= 0, "order_num must not be negative");
                dto.setOrderNum(request.getOrderNum());
            }
            if (request.hasLeader()) dto.setLeader(request.getLeader());
            if (request.hasPhone()) dto.setPhone(request.getPhone());
            if (request.hasEmail()) dto.setEmail(request.getEmail());
            if (request.hasStatus()) dto.setStatus(status(request.getStatus()).toString());
            validate(validator, dto);
            if (!deptService.updateDept(dto)) return toDeptResponse(found(null));
            return toDeptResponse(found(deptService.getById(request.getDeptId())));
        });
    }

    @Override
    public void deleteDept(DeleteDeptRequest request, StreamObserver<DeleteDeptResponse> observer) {
        respond(observer, () -> {
            boolean success = deptService.deleteDeptById(id(request.getDeptId()));
            return DeleteDeptResponse.newBuilder().setSuccess(success)
                .setMessage(success ? "Dept deleted successfully" : "Dept not found").build();
        });
    }

    @Override
    public void getDeptTree(GetDeptTreeRequest request, StreamObserver<DeptTreeResponse> observer) {
        respond(observer, () -> {
            require(request.getRootId() >= 0, "root_id must not be negative");
            List<SysDept> tree = deptService.buildDeptTree(List.of());
            if (request.getRootId() > 0) tree = List.of(found(findRoot(tree, request.getRootId())));
            return DeptTreeResponse.newBuilder().addAllNodes(tree.stream().map(this::toDeptResponseWithChildren).toList()).build();
        });
    }

    private SysDept findRoot(List<SysDept> nodes, long rootId) {
        for (SysDept node : nodes) {
            if (Objects.equals(node.getDeptId(), rootId)) return node;
            if (node.getChildren() != null) {
                SysDept foundNode = findRoot(node.getChildren(), rootId);
                if (foundNode != null) return foundNode;
            }
        }
        return null;
    }

    private DeptResponse toDeptResponse(SysDept dept) {
        return DeptResponse.newBuilder()
            .setDeptId(number(dept.getDeptId())).setParentId(number(dept.getParentId()))
            .setDeptName(text(dept.getDeptName())).setOrderNum(dept.getOrderNum() == null ? 0 : dept.getOrderNum())
            .setLeader(text(dept.getLeader())).setPhone(text(dept.getPhone()))
            .setEmail(text(dept.getEmail())).setStatus(text(dept.getStatus()))
            .setCreateTime(dept.getCreateTime() == null ? 0 : dept.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .setUpdateTime(dept.getUpdateTime() == null ? 0 : dept.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).build();
    }

    private DeptResponse toDeptResponseWithChildren(SysDept dept) {
        DeptResponse.Builder builder = toDeptResponse(dept).toBuilder();
        if (dept.getChildren() != null) builder.addAllChildren(dept.getChildren().stream().map(this::toDeptResponseWithChildren).toList());
        return builder.build();
    }
}
