# API Documentation Upgrade Design

**Objective**: Annotate `SysUserController` and `SysRoleController` with OpenAPI 3 annotations (`@Tag`, `@Operation`) to improve production-grade API documentation.

## Proposed Changes

### 1. `SysUserController`
- **Class Annotation**: `@Tag(name = "User Management", description = "Operations related to system users")`
- **Method `list`**: `@Operation(summary = "List users", description = "Retrieve a paginated list of users based on search criteria")`

### 2. `SysRoleController`
- **Class Annotation**: `@Tag(name = "Role Management", description = "Operations related to system roles")`
- **Method `list`**: `@Operation(summary = "List roles", description = "Retrieve a list of all system roles")`
- **Method `add`**: `@Operation(summary = "Add role", description = "Create a new system role")`

## Imports
`io.swagger.v3.oas.annotations.tags.Tag` and `io.swagger.v3.oas.annotations.Operation` will be added to both files.
