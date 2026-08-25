#!/usr/bin/env bash
# 文档版本漂移检测（P1-H3 配套防回归）
# 校验 README 等文档中声明的关键依赖版本与根 pom 属性一致。
# 失败即退出非零，供 CI quality-gate 调用。

set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

pom_prop() {
    # 提取根 pom <properties> 中的版本属性值
    grep -oE "<$1>[^<]+</$1>" pom.xml | head -1 | sed -E "s/<\/?$1>//g"
}

expect_doc_contains() {
    local version="$1" label="$2"
    if grep -q "$version" README.md; then
        echo "✅ $label (README 声明 $version)"
    else
        echo "❌ $label 版本漂移: pom=$version, README 未声明或不一致"
        fail=1
    fi
}

echo "== 文档版本与 pom 对齐检查 =="

MP_VERSION=$(pom_prop mybatis-plus.version)
JJWT_VERSION=$(pom_prop jjwt.version)
REDISSON_VERSION=$(pom_prop redisson.version)
SB_VERSION=$(pom_arg_or_empty() { :; }; pom_prop spring-boot.version)

[[ -n "$MP_VERSION" ]] && expect_doc_contains "$MP_VERSION" "MyBatis Plus" || { echo "❌ 根 pom 缺少 mybatis-plus.version 属性"; fail=1; }
if [[ -n "$JJWT_VERSION" ]]; then
    if grep -qE "jjwt[ (]*${JJWT_VERSION}" README.md; then
        echo "✅ jjwt (README 声明 $JJWT_VERSION)"
    else
        echo "❌ jjwt 版本漂移: pom=$JJWT_VERSION"
        fail=1
    fi
else
    echo "⚠️  根 pom 无 jjwt.version 属性（可能声明于子模块），跳过"
fi

[[ -n "$REDISSON_VERSION" ]] && expect_doc_contains "$REDISSON_VERSION" "Redisson" || { echo "❌ 根 pom 缺少 redisson.version 属性"; fail=1; }

# 已移除的组件不得再被引用（H3 教训：framework-seata 删除后文档残留）
if grep -rn "framework-seata" README.md CONTEXT.md AGENTS.md docs/ 2>/dev/null | grep -v "已移除\|已删除\|removed\|移出"; then
    echo "❌ 发现已移除组件 framework-seata 的引用"
    fail=1
else
    echo "✅ 无 framework-seata 残留引用"
fi

if [[ $fail -ne 0 ]]; then
    echo ""
    echo "文档版本漂移检测失败，请校正 README/文档中的版本号。"
    exit 1
fi
echo ""
echo "全部版本一致。"
