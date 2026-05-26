# 任务1: MinIO 集成评审

## 任务要求
1. 修改 `cloud-infra-modules/file-service/pom.xml`
2. 创建 `cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/service/MinioFileService.java`
3. 修改 `cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/service/LocalFileService.java` (策略模式/扩展)

## 实施情况
1. `cloud-infra-modules/file-service/pom.xml` 已添加 minio 8.5.7 依赖。
2. 已创建 `MinioFileService.java`。
3. 已创建 `MinioFileServiceTest.java` (不在原计划中，属于测试驱动)。
4. 未修改 `LocalFileService.java`。
