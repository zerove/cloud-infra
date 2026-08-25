package com.cloudinfra.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.mapper.SysDeptMapper;
import com.cloudinfra.system.service.ISysUserService;
import com.cloudinfra.system.service.ISysRoleService;
import com.cloudinfra.system.service.ISysDeptService;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;
import com.cloudinfra.system.domain.dto.SysRoleCreateDTO;
import com.cloudinfra.system.domain.dto.SysRoleUpdateDTO;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import java.time.Duration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class SystemIntegrationTest extends BaseIntegrationTest {

    /** 隔离 PostgreSQL：避免与部署环境共享数据库（种子数据/序列状态不可控） */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * RabbitMQ：Stream Rabbit binder 在上下文刷新时会建立连接，
     * 缺 broker 会阻塞重试导致整个 IT 卡死（全量回归实测踩坑），必须提供真实 broker
     */
    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(180)));

    @DynamicPropertySource
    static void containerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private SysDeptMapper deptMapper;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private ISysDeptService deptService;

    @Test
    void contextLoads() {
        assertThat(userMapper).isNotNull();
        assertThat(roleMapper).isNotNull();
        assertThat(deptMapper).isNotNull();
        assertThat(userService).isNotNull();
        assertThat(roleService).isNotNull();
        assertThat(deptService).isNotNull();
    }

    @Test
    void shouldCreateAndQueryDepartment() {
        // Given: 创建部门
        SysDeptCreateDTO deptDTO = new SysDeptCreateDTO();
        deptDTO.setDeptName("集成测试部门");
        deptDTO.setParentId(0L);
        deptDTO.setOrderNum(1);
        deptDTO.setStatus("0");

        // When: 保存部门
        boolean result = deptService.insertDept(deptDTO);

        // Then: 保存成功
        assertThat(result).isTrue();

        // When: 查询部门列表
        List<SysDept> depts = deptService.selectDeptList(new SysDept());
        SysDept found = depts.stream()
                .filter(d -> "集成测试部门".equals(d.getDeptName()))
                .findFirst()
                .orElse(null);

        // Then: 查询结果正确
        assertThat(found).isNotNull();
        assertThat(found.getDeptName()).isEqualTo("集成测试部门");
    }

    @Test
    void shouldCreateAndQueryRole() {
        // Given: 创建角色
        SysRoleCreateDTO roleDTO = new SysRoleCreateDTO();
        roleDTO.setRoleName("集成测试角色");
        roleDTO.setRoleKey("integration_test_role");
        roleDTO.setRoleOrder(1);
        roleDTO.setStatus(0);

        // When: 保存角色
        int result = roleService.insertRole(roleDTO);

        // Then: 保存成功
        assertThat(result).isEqualTo(1);

        // When: 查询角色列表
        List<SysRole> roles = roleService.selectRoleList(new SysRole());
        SysRole found = roles.stream()
                .filter(r -> "integration_test_role".equals(r.getRoleKey()))
                .findFirst()
                .orElse(null);

        // Then: 查询结果正确
        assertThat(found).isNotNull();
        assertThat(found.getRoleName()).isEqualTo("集成测试角色");
        assertThat(found.getRoleKey()).isEqualTo("integration_test_role");
    }

    @Test
    void shouldCreateUserWithRoleAndDept() {
        // Given: 先创建部门和角色
        SysDeptCreateDTO deptDTO = new SysDeptCreateDTO();
        deptDTO.setDeptName("用户测试部门");
        deptDTO.setParentId(0L);
        deptDTO.setOrderNum(1);
        deptDTO.setStatus("0");
        deptService.insertDept(deptDTO);

        SysDept createdDept = deptService.selectDeptList(new SysDept()).stream()
                .filter(d -> "用户测试部门".equals(d.getDeptName()))
                .findFirst().orElseThrow();

        SysRoleCreateDTO roleDTO = new SysRoleCreateDTO();
        roleDTO.setRoleName("用户测试角色");
        roleDTO.setRoleKey("user_test_role");
        roleDTO.setRoleOrder(1);
        roleDTO.setStatus(0);
        roleService.insertRole(roleDTO);

        SysRole createdRole = roleService.selectRoleList(new SysRole()).stream()
                .filter(r -> "user_test_role".equals(r.getRoleKey()))
                .findFirst().orElseThrow();

        // When: 创建用户
        SysUserCreateDTO userDTO = new SysUserCreateDTO();
        userDTO.setUsername("integration_user");
        userDTO.setNickName("集成测试用户");
        userDTO.setPassword("Test@1234");
        userDTO.setDeptId(createdDept.getDeptId());
        userDTO.setRoleId(createdRole.getId());
        userDTO.setEmail("integration@test.com");
        userDTO.setPhoneNumber("13800000000");

        int result = userService.insertUser(userDTO);

        // Then: 创建成功
        assertThat(result).isEqualTo(1);

        // When: 查询用户
        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "integration_user")
        );

        // Then: 用户信息正确
        assertThat(user).isNotNull();
        assertThat(user.getUsername()).isEqualTo("integration_user");
        assertThat(user.getNickName()).isEqualTo("集成测试用户");
        assertThat(user.getDeptId()).isEqualTo(createdDept.getDeptId());
        assertThat(user.getPassword()).isNotNull(); // 密码已加密
    }

    @Test
    void shouldQueryUserListWithPagination() {
        // Given: 创建测试数据
        for (int i = 0; i < 5; i++) {
            SysUserCreateDTO userDTO = new SysUserCreateDTO();
            userDTO.setUsername("page_user_" + i);
            userDTO.setNickName("分页用户" + i);
            userDTO.setPassword("Test@1234");
            userService.insertUser(userDTO);
        }

        // When: 分页查询
        Page<SysUser> page = new Page<>(1, 3);
        SysUser query = new SysUser();
        query.setStatus(0);
        com.baomidou.mybatisplus.core.metadata.IPage<SysUser> result = userService.selectUserList(page, query);

        // Then: 分页正确
        assertThat(result).isNotNull();
        assertThat(result.getRecords()).hasSize(3);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void shouldUpdateUserStatus() {
        // Given: 创建用户
        SysUserCreateDTO userDTO = new SysUserCreateDTO();
        userDTO.setUsername("status_user");
        userDTO.setNickName("状态用户");
        userDTO.setPassword("Test@1234");
        userService.insertUser(userDTO);

        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "status_user")
        );

        // When: 修改用户信息（用 updateDTO）
        SysUserUpdateDTO updateDTO = new SysUserUpdateDTO();
        updateDTO.setUserId(user.getUserId());
        updateDTO.setNickName("状态用户已修改");
        int result = userService.updateUser(updateDTO);

        // Then: 更新成功
        assertThat(result).isEqualTo(1);

        // When: 再次查询
        SysUser updated = userService.selectUserById(user.getUserId());

        // Then: 昵称已变更
        assertThat(updated.getNickName()).isEqualTo("状态用户已修改");
    }

    @Test
    void shouldDeleteUsersByIds() {
        // Given: 创建多个用户
        Long[] userIds = new Long[3];
        for (int i = 0; i < 3; i++) {
            SysUserCreateDTO userDTO = new SysUserCreateDTO();
            userDTO.setUsername("delete_user_" + i);
            userDTO.setNickName("删除用户" + i);
            userDTO.setPassword("Test@1234");
            userService.insertUser(userDTO);

            SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, "delete_user_" + i)
            );
            userIds[i] = user.getUserId();
        }

        // When: 批量删除
        int result = userService.deleteUserByIds(userIds);

        // Then: 删除成功
        assertThat(result).isEqualTo(3);

        // When: 查询已删除用户
        for (Long id : userIds) {
            SysUser deleted = userMapper.selectById(id);
            assertThat(deleted).isNull();
        }
    }
}