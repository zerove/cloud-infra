# JWT Security Scheme Design

## Goal
Add JWT-based authentication support to the API documentation using SpringDoc OpenAPI.

## Approach
Configure a `SecurityScheme` in the `OpenAPI` bean and add a global `SecurityRequirement` to require JWT Bearer tokens for all endpoints.

## Design Details
- **SecurityScheme Name**: "bearer-key"
- **Type**: `SecurityScheme.Type.HTTP`
- **Scheme**: "bearer"
- **BearerFormat**: "JWT"
- **SecurityRequirement**: Add a requirement referencing "bearer-key".

## Implementation Plan
1. Modify `OpenApiConfig.java`.
2. Update `OpenAPI` bean definition.
3. Add `Components` with the `SecurityScheme`.
4. Add `SecurityRequirement` to `OpenAPI` instance.
5. Verify with a build/test (if applicable).
