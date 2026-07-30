# IoT 网关服务设计文档（已归档）

> **注意**: `iot-gateway-service` 模块因未完成已从项目构建中移除，本文档保留仅供参考。

## 概述
IoT 网关服务 (`iot-gateway-service`) 是 Cloud-Infra 平台中负责接入、管理及控制各类智能设备的核心模块。它支持手机移动端、家庭智能设备、计算设备及联网汽车的统一接入。

## 架构设计
### 1. 设备领域模型
所有设备抽象为统一的 `Device` 类：
- `deviceId`: 唯一识别号
- `category`: 设备类型 (CAR, AIR_CONDITIONER, LIGHT, PC, MOBILE 等)
- `status`: 状态 (ONLINE, OFFLINE)
- `properties`: 实时状态属性映射表

### 2. 状态存储（设备影子）
基于 Redis 构建“设备影子（Device Shadow）”，用于缓存设备最新上报状态。
- Key 格式: `device:shadow:{deviceId}`
- 存储有效期: 24小时

### 3. 通信适配
- **移动设备 (手机)**: 通过 RESTful API (`/api/v1/iot/devices/{deviceId}/report`) 主动上报状态。
- **智能家居**: 预留 MQTT 通道（基于 EMQX），支持指令异步下发。

## API 列表
- `GET /api/v1/iot/devices/{deviceId}`: 获取设备当前影子状态
- `POST /api/v1/iot/devices/{deviceId}/report`: 移动端上报设备状态
- `POST /api/v1/iot/devices/{deviceId}/command`: 向设备下发控制指令
