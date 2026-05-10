# MQ Integration Design Spec

**Goal:** Implement reliable transaction message retry using Spring Cloud Stream and RabbitMQ to improve system reliability.

**Architecture:**
- Use **Spring Cloud Stream** with `StreamBridge` for decoupling and architectural consistency.
- **Message Broker:** RabbitMQ.
- **Reliability:** `Publisher Confirms` mechanism to ensure delivery. `TxMessageRetryTask` retains database-level retry logic for final consistency.
- **Message Format:** Standardized `CloudMessage<T>` wrapper to include `traceId` for full-chain observability.

**Components:**
- `CloudMessage<T>`: Message wrapper class.
- `TxMessageRetryTask`: Refactored to use `StreamBridge`.
- `application.yml`: Stream binder configuration.

**Data Flow:**
1. `TxMessageRetryTask` retrieves pending messages from the database.
2. Messages are wrapped in `CloudMessage`.
3. `StreamBridge` sends `CloudMessage` to the RabbitMQ exchange.
4. On success, `TxMessageRetryTask` updates the database status.

**Testing Strategy:**
- Integration Test: Use `Testcontainers` (RabbitMQContainer) in `BaseIntegrationTest` to verify end-to-end messaging.

---
**Spec self-review:**
- Checked for placeholders: None.
- Checked consistency: Architecture matches Spring Cloud Stream standard.
- Scope: Focused on `TxMessageRetryTask`.
