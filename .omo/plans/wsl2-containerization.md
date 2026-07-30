# Work Plan: WSL2 Fedora Containerization & Win11 Access

## 1. Objective
Implement a containerized deployment of the Cloud-Infra platform within WSL2 Fedora, ensuring all services are accessible via `localhost` from the Windows 11 host using mirrored networking.

## 2. Decision Summary
- **Network Mode**: `mirrored` (via `.wslconfig`)
- **Container Engine**: Podman + `podman-compose`
- **Build Strategy**: Hybrid (Native for Gateway/Auth, JRE for Others)
- **Host OS**: Fedora (WSL2)
- **Host Tooling**: JDK 25, Maven 3.9+

## 3. Architecture Map
- **Entry**: Win11 Browser $\rightarrow$ `localhost:9000` $\rightarrow$ WSL2 Mirror $\rightarrow$ Podman Gateway Container.
- **Coordination**: All containers reside in `cloud-infra-net`.
- **Configuration**: `up.sh` $\rightarrow$ push config to Nacos $\rightarrow$ start business services.

## 4. Implementation Tasks

### Phase 1: Host Environment Preparation
- [x] **T1.1: Install JDK 25**. Run `sudo dnf install java-25-openjdk-devel` (or install Eclipse Temurin 25). Verify with `java -version`.
- [x] **T1.2: Install Maven 3.9+**. Run `sudo dnf install maven`. Verify with `mvn -version`.
- [x] **T1.3: Install Podman & Compose**. Run `sudo dnf install podman podman-compose`. Verify with `podman version` and `podman-compose version`.
- [x] **T1.4: Verify Host Compilation**. Run `mvn clean package -DskipTests` at the root to ensure the host can build the project successfully before containerization.

### Phase 2: WSL2 Networking & Resource Configuration
### Phase 2: WSL2 Networking & Resource Configuration
### Phase 2: WSL2 Networking & Resource Configuration
- [x] **T2.1: Configure .wslconfig (Windows Side)**. In Windows PowerShell, create/edit `%USERPROFILE%\.wslconfig`. Add the following to ensure network mirroring and sufficient RAM for Native builds:
  ```ini
  [wsl2]
  networkingMode=mirrored
  memory=12GB 
  processors=4
  ```
  *(Note: 12GB is the minimum recommended for GraalVM Native compilation)*
- [x] **T2.2: Restart WSL**. Execute `wsl --shutdown` in PowerShell and restart Fedora to apply network and resource changes.
- [x] **T2.3: Verify Mirrored Mode**. Run `ip addr` in Fedora; verify the network interfaces reflect the Windows host's IP space.


### Phase 3: Container Engine Setup
- [x] **T3.1: Podman Systemd Enable**. Ensure systemd is enabled in `/etc/wsl.conf` (`[boot] systemd=true`) to allow Podman to run as a service.
- [x] **T3.2: Rootless Port Verification**. Verify that ports 9000, 8848, etc., are not reserved by other Windows processes.
- [x] **T3.3: Podman Init**. Run `podman machine init` (if required) or verify `podman info` to ensure the socket is available.

### Phase 4: Deployment Execution
- [x] **T4.1: Execution of up.sh**. (Infrastructure part done, configurations synced).
- [x] **T4.2: Monitor Nacos Startup**. Nacos is UP and healthy.
- [x] **T4.3: Verify Config Sync**. Configurations (`auth.yaml`, `gateway.yaml`, etc.) have been pushed to Nacos.
- [ ] **T4.4: Business Service Startup**. Need to run `build-native.sh` first, then `up.sh`.

### Phase 5: Win11 Access Validation
- [ ] **T5.1: Middleware Ping**. Access `http://localhost:8848/nacos` and `http://localhost:6379` (via redis-cli) from Windows.
- [ ] **T5.2: Gateway Access**. Access `http://localhost:9000` and verify the gateway response.
- [ ] **T5.3: Full Flow Test**. Use the `cloud-auth` login API $\rightarrow$ get Token $\rightarrow$ access `system-module` via Gateway. Expect 200 OK.
- [ ] **T5.4: Native Performance Check**. Verify the Gateway starts in milliseconds (Native Image characteristic) after a container restart.




## 5. Final Verification Wave
- [ ] **Phase 1: Infrastructure**. Verify `.wslconfig` applied and Podman is operational.
- [ ] **Phase 2: Connectivity**. Verify `http://localhost:8848/nacos` is accessible from Windows.
- [ ] **Phase 3: Application**. Verify `http://localhost:9000` responds with the Gateway interface.
- [ ] **Phase 4: End-to-End**. Successfully login via `cloud-auth` and access `system-module`.

**Requirement**: All boxes must be checked. Final confirmation from the user is required before marking the plan as complete.
