#!/bin/bash
set -e
# Native Image 一键重建脚本（修复 AOT 类名过期问题）
GATEWAY_DIR=/home/zeron/dev/cloud-infra/cloud-infra-gateway
BUILD_DIR=$GATEWAY_DIR/target/native-build
AOT_DIR=$GATEWAY_DIR/target/spring-aot/main

echo "=== 1. 准备 native-build 目录 ==="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo "=== 2. 解压 fat jar ==="
jar xf ../cloud-infra-gateway-1.0.0-SNAPSHOT.jar

echo "=== 3. 生成依赖 classpath ==="
ls BOOT-INF/lib/ > cp_list.txt
sed -i 's|^|BOOT-INF/lib/|' cp_list.txt

echo "=== 4. 打包 spring-aot resources 为 jar ==="
jar cf spring-aot-resources.jar -C "$AOT_DIR/resources" .

echo "=== 5. 复制 spring-aot classes ==="
mkdir -p spring-aot-classes
cp -r "$AOT_DIR/classes/"* spring-aot-classes/

echo "=== 6. 生成反射配置（核心 AOT 类 + jasypt 动态类） ==="
python3 - "$BUILD_DIR" << 'PYEOF'
import json, os, sys
build_dir = sys.argv[1]
config_path = os.path.join(build_dir, 'aot-reflect-config.json')

# 只注册 GatewayApplication 的核心 AOT 类（避免 JVMCI 编译器过载）
# 其余类由 spring-aot resources 中的 reachability-metadata.json 自动发现
aot_classes = []
for root, dirs, files in os.walk(os.path.join(build_dir, 'spring-aot-classes')):
    for f in files:
        if f.endswith('.class') and ('__BeanDefinitions' in f or '__ApplicationContextInitializer' in f or '__BeanFactoryRegistrations' in f):
            rel = os.path.relpath(os.path.join(root, f), os.path.join(build_dir, 'spring-aot-classes'))
            cls = rel.replace('.class', '').replace('/', '.')
            if cls.startswith('com.cloudinfra.gateway.') or cls.startswith('com.cloudinfra.framework.'):
                aot_classes.append(cls)

config = []
for cls in sorted(set(aot_classes)):
    config.append({
        'name': cls,
        'allDeclaredConstructors': True,
        'allDeclaredMethods': True,
        'allDeclaredFields': True
    })

# 添加 jasypt 动态 Class.forName 加载的类（Native Image 必需）
for extra in [
    'org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource',
    'com.ulisesbocchio.jasyptspringboot.EncryptablePropertySourceConverter',
]:
    config.append({
        'name': extra,
        'allDeclaredConstructors': True,
        'allDeclaredMethods': True,
        'allDeclaredFields': True
    })

with open(config_path, 'w') as f:
    json.dump(config, f, indent=2)
print(f'生成反射配置: {len(config)} 个类')
PYEOF

echo "=== 7. 生成动态代理配置（jboss-logging/hibernate-validator） ==="
cat > "$BUILD_DIR/proxy-config.json" << 'EOF'
[
  ["org.hibernate.validator.internal.util.logging.Log"],
  ["org.hibernate.validator.internal.util.logging.Messages"]
]
EOF
# 实现类通过 Class.forName 加载，需反射注册
python3 - "$BUILD_DIR/aot-reflect-config.json" << 'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    config = json.load(f)
existing = {item['name'] for item in config}
for impl in [
    'org.hibernate.validator.internal.util.logging.Log_$logger',
    'org.hibernate.validator.internal.util.logging.Messages_$bundle',
]:
    if impl not in existing:
        config.append({
            'name': impl,
            'allDeclaredConstructors': True,
            'allDeclaredMethods': True,
            'allDeclaredFields': True
        })
with open(path, 'w') as f:
    json.dump(config, f, indent=2)
print('已追加 hibernate-validator 日志实现类反射注册')
PYEOF

echo "=== 8. 生成最终 classpath ==="
{ echo -n "spring-aot-resources.jar:spring-aot-classes:BOOT-INF/classes:"; paste -sd: cp_list.txt; } > cp.txt

echo "=== 8. 启动 native-image 构建 ==="
setsid native-image \
    -cp @cp.txt \
    -H:ReflectionConfigurationFiles="$BUILD_DIR/aot-reflect-config.json" \
    -H:DynamicProxyConfigurationFiles="$BUILD_DIR/proxy-config.json" \
    --initialize-at-build-time=org.redisson.misc.BiHashMap \
    --initialize-at-build-time=org.redisson.liveobject.core.RedissonObjectBuilder\$CodecMethodRef \
    -H:+SharedArenaSupport \
    com.cloudinfra.gateway.GatewayApplication \
    -o cloud-infra-gateway-binary \
    -J-Xmx7g -J-XX:CICompilerCount=2 > native-build.log 2>&1 &
echo "native-image 已在后台启动 (PID=$!)"
echo "监控: tail -f $BUILD_DIR/native-build.log"
