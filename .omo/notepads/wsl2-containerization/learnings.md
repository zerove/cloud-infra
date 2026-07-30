# WSL2 Containerization Learnings

## Phase 1: Environment Setup

### JDK 25 Installation
- Command: `sudo dnf install -y java-25-openjdk-devel`
- Result: Installed `java-25-openjdk-devel-1:25.0.3.0.9-1.fc44.x86_64`.
- Verification: `java -version` output:
  ```
  java version "25.0.1" 2025-10-21 LTS
  Java(TM) SE Runtime Environment Oracle GraalVM 25.0.1+8.1 (build 25.0.1+8-LTS-jvmci-b01)
  Java HotSpot(TM) 64-Bit Server VM Oracle GraalVM 25.0.1+8.1 (build 25.0.1+8-LTS-jvmci-b01, mixed mode, sharing)
  ```

### Maven 3.9+ Installation
- Command: `sudo dnf install -y maven`
- Result: Installed `maven-1:3.9.11-11.fc44.noarch`.
- Verification: `mvn -version` output:
  ```
  Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
  Maven home: /home/zeron/.local/share/mise/installs/maven/latest/apache-maven-3.9.16
  Java version: 25.0.1, vendor: Oracle Corporation, runtime: /home/zeron/.local/share/mise/installs/java/oracle-graalvm-25.0.1
  ```

### Podman & Podman-Compose Installation
- Command: `sudo dnf install -y podman podman-compose`
- Result: Already installed.
- Verification:
  - `podman version`: `5.8.2`
  - `podman-compose version`: `1.5.0`
