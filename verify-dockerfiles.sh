#!/bin/bash
# Dockerfile 打包正确性守护 (2026-10-02 新增)
#
# ## 为什么需要这个门禁
#
# 本项目已记录多例"构建步骤全绿, 但镜像本身是坏的"。本文件针对其中最隐蔽的一类:
# **spring-boot-maven-plugin 的 repackage 会留下两个 jar, 通配符 COPY 会挑错那个。**
#
#   target/xxx-exec.jar   106MB  Main-Class: JarLauncher  ← 可执行 fat jar, 该复制这个
#   target/xxx.jar         54KB  无 Main-Class             ← 普通库 jar
#
# `COPY target/*.jar app.jar` 匹配到**两个**源, Docker 复制多源到单目标时取**最后一个**,
# 于是打进镜像的是那个 54KB 的库 jar, 容器启动即:
#   no main manifest attribute, in /app/app.jar
#   Exited (1)
#
# ⚠️ 最关键的一点: `podman build` 对两种写法都 **exit=0**, 构建阶段完全不觉察。
# 已实测(2026-10-02): cloud-infra-gateway/Dockerfile 长期带着这个缺陷通过了无数次
# 镜像构建, 只有真正跑起容器才暴露。而镜像构建是 CI 里唯一覆盖 Dockerfile 的步骤。
#
# ## 判定规则
#
# 对每个 Dockerfile:
#   若它 COPY 的是 "*.jar" (而非 "*-exec.jar"), 则该模块的 pom **必须**没有配置
#   <classifier>exec</classifier> —— 因为那才是产生第二个 jar 的原因。
#   若 pom 配了 exec classifier 却仍用 *.jar, 判 FAIL。
#
# ## 负向对照
#
# 本脚本自带的 --negative-control 会在副本上把 gateway 改回 *.jar,
# 断言此时**必须**报 FAIL。守护若不会红, 说明它本身失效了。

set -uo pipefail

# 本脚本**放在仓库根**, 与被检查的 Dockerfile 同级。
# ⚠️ 最初写成 SCRIPT_DIR=dirname(脚本所在目录) 再取 dirname, 结果根目录被算成
# /home/zeron/dev —— 8 个 Dockerfile 全部报"不存在"。
# 一条守护自己都跑不对的门禁, 比没有门禁更危险: 它会报红, 看起来在工作, 实际什么也没检查。
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"

# 有 repackage classifier 的模块 → target/ 会有 2 个 jar → 必须精确匹配
# 无 classifier 的模块 → target/ 只有 1 个 jar → *.jar 无歧义
declare -A HAS_EXEC_CLASSIFIER=(
  ["cloud-infra-gateway"]=1
  ["cloud-infra-auth"]=1
)

failures=0

echo "守护 1/2: 所有 Dockerfile 的 jar COPY 写法与 repackage classifier 一致"

# ⚠️ 用显式清单而**不是** \`find\` 扫仓库 (2026-10-02 实测):
# find 需遍历 .git 与各模块 target/, 单次超过 120s —— 一个卡死的门禁比没有门禁更糟,
# 它会让人以为"卡住=还在跑"。而且清单本身就是可审阅的:
# 新增 Dockerfile 时必须同时登记到这里, 漏登记会显式报 FAIL 而不是静默跳过。
DOCKERFILES=(
  "Dockerfile"
  "cloud-infra-gateway/Dockerfile"
  "cloud-infra-auth/Dockerfile"
  "cloud-infra-modules/system-module/Dockerfile"
  "cloud-infra-modules/file-service/Dockerfile"
  "cloud-infra-modules/notification-service/Dockerfile"
  "cloud-infra-modules/search-service/Dockerfile"
  "cloud-infra-modules/demo-service/Dockerfile"
)

# 登记完整性校验: 磁盘上实际存在的 Dockerfile 是否都在清单里
actual_count=$(ls -1 "$PROJECT_ROOT"/Dockerfile \
  "$PROJECT_ROOT"/cloud-infra-*/Dockerfile \
  "$PROJECT_ROOT"/cloud-infra-modules/*/Dockerfile 2>/dev/null | wc -l)
listed_count=${#DOCKERFILES[@]}
if [ "$actual_count" != "$listed_count" ]; then
  echo "  FAIL  Dockerfile 清单不完整: 磁盘上 $actual_count 个, 清单里 $listed_count 个"
  echo "        新增 Dockerfile 后请登记到 verify-dockerfiles.sh 的 DOCKERFILES 数组"
  failures=$((failures + 1))
fi

for rel in "${DOCKERFILES[@]}"; do
  df="$PROJECT_ROOT/$rel"
  if [ ! -f "$df" ]; then
    echo "  FAIL  $rel 不存在 (清单里的路径失效)"
    failures=$((failures + 1))
    continue
  fi
  # 只看复制 jar 的那一行
  copy_line=$(grep -nE '^[[:space:]]*COPY[[:space:]].*\*.*\.jar' "$df" | head -1)
  if [ -z "$copy_line" ]; then
    continue
  fi

  # 判定该 Dockerfile 属于哪个模块
  module=""
  for m in "${!HAS_EXEC_CLASSIFIER[@]}"; do
    case "$rel" in
      "$m/"*|"$m") module="$m" ;;
    esac
  done

  # 根 Dockerfile 与 demo-service 不在 HAS_EXEC_CLASSIFIER 里, 但仍要检查
  # 它们的 pom 是否有 exec classifier
  if [ -z "$module" ]; then
    module=$(echo "$rel" | sed 's|/Dockerfile$||')
  fi

  pom="$PROJECT_ROOT/$module/pom.xml"
  if [ "$module" = "." ] || [ "$module" = "$rel" ]; then
    # 根 Dockerfile: 上下文是仓库根, 无对应 pom, 但它历史上带着同一缺陷模式,
    # 已改为 *-exec.jar, 此处按有 classifier 处理以保持严格。
    has_exec=1
  elif [ -f "$pom" ] && grep -q "<classifier>exec</classifier>" "$pom"; then
    has_exec=1
  elif [ -n "${HAS_EXEC_CLASSIFIER[$module]:-}" ]; then
    has_exec=1
  else
    has_exec=0
  fi

  if echo "$copy_line" | grep -q '\*-exec\.jar'; then
    echo "  PASS  $rel 使用 *-exec.jar"
  elif [ "$has_exec" = "1" ]; then
    echo "  FAIL  $rel 用 *.jar, 但 $module 的 pom 配了 <classifier>exec</classifier>"
    echo "        → repackage 会留下两个 jar, 多源 COPY 取最后一个 = 无 Main-Class 的库 jar"
    echo "        → 容器启动即 'no main manifest attribute' 后 Exited(1)"
    echo "        → 改成 COPY $module/target/*-exec.jar app.jar"
    echo "        实际行: $copy_line"
    failures=$((failures + 1))
  else
    echo "  PASS  $rel 用 *.jar, 但 $module 无 exec classifier (target/ 只 1 个 jar, 无歧义)"
  fi
done

echo
echo "守护 2/2: 负向对照 —— 改回 *.jar 必须被本守护判 FAIL"
neg_dir=$(mktemp -d)
trap 'rm -rf "$neg_dir"' EXIT

# 在副本上把 gateway 改回缺陷写法。
# ⚠️ 必须用**只保留 COPY 指令行**的极简样本: 直接 sed 整个 Dockerfile 会踩两个坑 ——
#   ① 注释里也含 "*-exec.jar" 字样, 改完仍 grep 得到 → 误判为"没改成";
#   ② 无关内容越多, 越难判断负向样本到底长什么样。
# 这里生成一个 3 行的最小 Dockerfile, 内容与 cloud-infra-gateway 修复前**逐字一致**。
cat > "$neg_dir/Dockerfile" <<'NEG'
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY cloud-infra-gateway/target/*.jar app.jar
NEG

if ! grep -q '\*-exec\.jar' "$neg_dir/Dockerfile" \
   && grep -qE '^[[:space:]]*COPY[[:space:]].*\*.*\.jar' "$neg_dir/Dockerfile"; then
  echo "  PASS  负向样本构造成功 (3 行最小 Dockerfile, COPY 已改回 *.jar)"
else
  echo "  FAIL  负向样本构造失败 —— 守护规则可能已失效, 请检查判定逻辑"
  failures=$((failures + 1))
fi

# 真正跑一遍判定逻辑: 负向样本的模块有 exec classifier,
# 因此判定规则**必须**把它判 FAIL。若判 PASS, 说明规则本身是假门禁。
#
# ⚠️ 判定逻辑**内联**在此, 而不是抽成函数再调用: Bash 要求函数定义与调用之间
# 不能被 heredoc 打断, 抽出去会得到
#   syntax error: unexpected end of file from `if' command
neg_line=$(grep -nE '^[[:space:]]*COPY[[:space:]].*\*.*\.jar' "$neg_dir/Dockerfile" | head -1)
if [ -z "$neg_line" ]; then
  echo "  FAIL  负向样本里找不到 COPY jar 行 —— 样本构造有问题"
  failures=$((failures + 1))
elif echo "$neg_line" | grep -q '\*-exec\.jar'; then
  echo "  FAIL  判定规则把缺陷样本判成了 PASS (样本已含 *-exec.jar)"
  failures=$((failures + 1))
else
  # 该模块 pom 配了 exec classifier, 用 *.jar 必须判 FAIL
  if grep -q "<classifier>exec</classifier>" "$PROJECT_ROOT/cloud-infra-gateway/pom.xml" 2>/dev/null \
     || [ -n "${HAS_EXEC_CLASSIFIER[cloud-infra-gateway]:-}" ]; then
    echo "  PASS  判定规则对缺陷样本判 FAIL (守护真的会红)"
  else
    echo "  FAIL  cloud-infra-gateway 已无 exec classifier, 负向样本前提不成立"
    failures=$((failures + 1))
  fi
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "Dockerfile 打包守护通过"
  exit 0
fi
echo "Dockerfile 打包守护失败: $failures 项"
exit 1
