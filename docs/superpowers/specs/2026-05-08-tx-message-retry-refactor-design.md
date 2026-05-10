# TxMessageRetryTask Refactor Design

**Goal:** Refactor `TxMessageRetryTask` to use `StreamBridge` for asynchronous message processing.

**Architecture:**
- The current implementation uses `@Scheduled` to poll for pending messages and perform synchronous processing (placeholder).
- We will integrate `StreamBridge` to push messages to the configured binding (`txMessageOut-out-0`).
- This change enables a more decoupled, event-driven architecture using Spring Cloud Stream.

**Components:**
- `TxMessageRetryTask`: Modified to inject `StreamBridge` and replace the internal TODO with a `StreamBridge.send()` call.

**Data Flow:**
- `TxMessageRetryTask` -> `SysTxMessageService.findPendingMessages()` -> Loop -> `StreamBridge.send("txMessageOut-out-0", cloudMessage)` -> `SysTxMessageService.updateMessageStatus(..., 1)`.

**Testing:**
- Unit test for `TxMessageRetryTask` with a mocked `SysTxMessageService` and `StreamBridge`.
