# Distributed Tracing Verification Plan

This document describes the procedure for verifying end-to-end distributed tracing across the `cloud-infra` ecosystem.

## 1. Verification Environment
- **Tracing Backend**: Jaeger or Tempo (connected via OTLP collector).
- **Collector Endpoint**: `http://otel-collector:4317`.
- **Sampling Rate**: Set to `1.0` (100%) for verification.

## 2. Test Scenario: Cross-Module Call Flow
**Scenario**: A request flows from Gateway $\rightarrow$ Auth $\rightarrow$ System $\rightarrow$ Notification.

### Verification Steps:
1. **Trigger Request**: Send a request to the Gateway that triggers this flow.
2. **Extract Trace ID**: Capture the `traceId` from the response headers or logs.
3. **Query Backend**: Search for the `traceId` in the Jaeger/Tempo UI.
4. **Validate Spans**:
    - [ ] **Span Continuity**: Ensure the trace contains spans from all four services.
    - [ ] **Parent-Child Relationship**: Verify that the `spanId` of the Gateway call is the `parentSpanId` of the Auth call, and so on.
    - [ ] **Latency Analysis**: Check the duration of each span to identify bottlenecks.
    - [ ] **Custom Tags**: Verify that tags added via `TraceUtils.addTag()` are present in the spans.

## 3. Automated Verification (Integration Test)
A test in `cloud-infra-test-support` should:
1. Use `RestTemplate` to initiate a call.
2. Intercept the `X-B3-TraceId` or `traceparent` header.
3. Call the tracing backend's API to verify the existence and structure of the trace.
