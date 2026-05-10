# SkyWalking Design for cloud-infra

## Overview
Integrate Apache SkyWalking 9.7.0 for distributed tracing and observability.

## Components
1. **OAP Server**: `apache/skywalking-oap-server:9.7.0`
2. **UI**: `apache/skywalking-ui:9.7.0`

## Implementation Details
- **docker-compose.yaml**:
    - Add `oap` service:
      - Image: `apache/skywalking-oap-server:9.7.0`
      - Ports: 11800 (gRPC), 12800 (HTTP)
    - Add `ui` service:
      - Image: `apache/skywalking-ui:9.7.0`
      - Environment: `SW_OAP_ADDRESS=http://oap:12800`
      - Ports: 8080:8080
- **Dockerfile**:
    - Download SkyWalking agent during build (or via multi-stage).
    - Update `ENTRYPOINT` to include `-javaagent:/app/skywalking-agent/skywalking-agent.jar`.
- **Environment Variables** (for microservices):
    - `SW_AGENT_COLLECTOR_BACKEND_SERVICES=oap:11800`
    - `SW_AGENT_NAME` (derived from service name)

## Plan
1. Update `deploy/compose.yaml`.
2. Modify `deploy/Dockerfile` to include the agent.
3. Update service definitions in `compose.yaml` to pass the required environment variables.
