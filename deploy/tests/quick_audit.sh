#!/usr/bin/env bash
# quick_audit.sh — 2026-11-02 Session 四条核验命令的可执行固化版本。
# 由 AGENTS.md「🔍 快速核查清单」第 0~3 条固化；下个 session 直接执行即可复现。
#
# 使用: bash deploy/tests/quick_audit.sh
# 通过标准: 汇总以**失败计数 fail** 为准 —— fail > 0 即 exit 1（不再依赖 fail_lines）。
#
# 2026-11-12 修复: 原汇总只判 fail_lines, 而站 1 / 站 2 的 FAIL 只累加 fail 计数、
# 未写入 fail_lines —— 于是这两站无论怎么红, 脚本都输出 "🟢 ALL PASS" 且 exit 0
# （守护自身假绿, 第八课补记三同型）。
#
# 负向对照（第六课）已固化为 --self-test: 临时注入一个无 @PreAuthorize 的 controller,
# 断言脚本判红(exit 1), 移除后断言判绿(exit 0) —— 防止假绿回归。
set -u

REPO="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
cd "$REPO" || { echo "无法进入仓库根目录: $REPO"; exit 2; }

ok=0
fail=0
fail_lines=()

# ---- --self-test: 负向对照（证明守护真的会红）----
if [[ "${1:-}" == "--self-test" ]]; then
  probe="$REPO/cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/ZzSelfTestProbeController.java"
  cat > "$probe" <<'JAVA'
package com.cloudinfra.system.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 临时探针: 故意没有 @PreAuthorize, 用于验证 quick_audit 站 2 判红。 */
@RestController
public class ZzSelfTestProbeController {
    @GetMapping("/self-test-probe")
    public String probe() {
        return "probe";
    }
}
JAVA
  out=$(bash "$0" 2>&1); rc=$?
  rm -f "$probe"
  if [[ "$rc" -ne 1 ]]; then
    echo "❌ self-test 失败: 注入无 @PreAuthorize 的 controller 后期望 exit 1, 实际 exit $rc"
    echo "$out" | tail -6
    exit 1
  fi
  out2=$(bash "$0" 2>&1); rc2=$?
  if [[ "$rc2" -ne 0 ]]; then
    echo "❌ self-test 失败: 移除探针后期望 exit 0, 实际 exit $rc2"
    echo "$out2" | tail -6
    exit 1
  fi
  echo "✅ self-test 通过: 注入探针 → exit 1 (判红); 移除探针 → exit 0 (判绿)"
  exit 0
fi

echo "==== 根目录: $(pwd) ===="

# extract_order —— 只返回"显式 return 数字"或空串。
# rg 的 -o 会把方法体整段匹配出来(含注释行), 得不出干净 order 数字;
# 这里用 Python 正则切到单行 return —— 让守护判据只验到 order 字面量,
# 不被注释行或方法签名干扰(第六课负向对照所需的隔离)。
extract_order() {
  python3 - "$1" <<'PYEOF' 2>/dev/null
import re, sys
t = open(sys.argv[1], encoding='utf-8').read()
for line in t.splitlines():
    m = re.search(r'\breturn\s+(-?\d+)', line)
    if m:
        print(m.group(1))
        sys.exit(0)
print('')
PYEOF
}

echo "==== 站 0: 多租户已真正启用 ===="
tcf=$(extract_order "$REPO/cloud-infra-common/src/main/java/com/cloudinfra/common/core/tenant/TenantContextFilter.java")
n=$(grep -R --include="*.java" -c "@TenantFilter" cloud-infra-modules 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
switches=0
for f in deploy/config/{system-module,file-service,notification-service}.yaml; do
  if grep -q "^  tenant:" "$f"; then switches=$((switches+1)); fi
done
if [[ "$n" -ge 9 ]] && [[ "$switches" -eq 3 ]] && [[ -n "$tcf" ]]; then
  echo "PASS TenantContextFilter 与 @TenantFilter 注解数(≥9)、部署态开关(3)均满足。"
else
  echo "FAIL TCF: '$tcf' (期望 ≥9 注解；开关 $switches / TCF 含 order = $tcf)"; fail=$((fail+1)); fail_lines+=("站 0: 多租户"); fail_lines+=("站 3: trust chain")
fi

echo "==== 站 1: 已删除框架残留归零 ===="
hits=$(grep -R --include='*.java' -nE '@Idempotent|@EnableOutbox|OpenApiExporter|WorkflowEngine|@EnableOpenApi' cloud-infra-modules 2>/dev/null | wc -l)
if [[ "$hits" -eq 0 ]]; then
  echo "PASS 0 命中。"
else
  echo "FAIL 命中 $hits。禁止为凑完整而补齐配套。"; fail=$((fail+1)); fail_lines+=("站 1: 框架残留")
fi

echo "==== 站 2: Controller @PreAuthorize 覆盖率（豁免清单: internal 端点 / 登录 / 验证码 / 健康检查）===="
# 仅审计生产源码中的 Controller（src/main）；测试夹具 Controller 不受本安全守护约束。
py_out=$(python3 - "$REPO" <<'PYEOF'
import re, sys, glob, os
root = sys.argv[1]
pa = re.compile(r'@PreAuthorize\(')
pa_files = []
with_mapping = 0
for f in sorted(glob.glob(root + '/**/controller/*.java', recursive=True)):
    if '/src/test/' in f:
        continue
    t = open(f).read()
    if re.search(r'@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping)', t):
        with_mapping += 1
    has_pa = bool(re.search(r'@RequestMapping', t) or re.search(r'@GetMapping', t)
                   or re.search(r'@PostMapping', t) or re.search(r'@PutMapping', t)
                   or re.search(r'@DeleteMapping', t))
    if not has_pa:
        rel = os.path.relpath(f, root)
        if rel.endswith('InternalUserController.java') or rel.endswith('AuthController.java') \
           or rel.endswith('CaptchaController.java') or rel.endswith('HealthController.java') \
           or rel.endswith('InternalNotificationController.java'):
            continue   # 豁免：internal 业务端点 / 登录验证码 / 健康检查无 @PreAuthorize 属设计允许
        pa_files.append(rel)
sys.stdout.write(','.join(pa_files) + '\n')
PYEOF
)
pycode=$?
missing=$(python3 - "$REPO" <<'PYEOF' 2>/dev/null
import re, sys, glob, os
root = sys.argv[1]
pa_files = set()
with_mapping = 0
for f in sorted(glob.glob(root + '/**/controller/*.java', recursive=True)):
    if '/src/test/' in f:
        continue
    t = open(f).read()
    if re.search(r'@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping)', t):
        with_mapping += 1
    if not any(m in t for m in ['@RequestMapping','@GetMapping','@PostMapping','@PutMapping','@DeleteMapping']):
        continue
    rel = os.path.relpath(f, root)
    if rel.endswith('InternalUserController.java') or rel.endswith('AuthController.java') \
       or rel.endswith('CaptchaController.java') or rel.endswith('HealthController.java') \
       or rel.endswith('InternalNotificationController.java'):
        continue
    if not re.search(r'@PreAuthorize\(', t):
        pa_files.add(rel)
sys.stdout.write(','.join(sorted(pa_files)) + '\n')
PYEOF
)
if [[ $pycode -eq 0 ]] && [[ -z "$missing" ]]; then
  echo "PASS Controller @PreAuthorize 覆盖率正常。"
else
  echo "FAIL Controller @PreAuthorize 覆盖率异常: missing='$missing'"; fail=$((fail+1)); fail_lines+=("站 2: @PreAuthorize 覆盖")
fi

echo "==== 站 3: 关键过滤器 order 与信任链（TCF < HAF, 负向对照）===="
# 守护判据: 两端都无显式 order 的同时被忽略... 不, 是两端的 order 字面量**都非空**才能 PASS。
# TCF 有 order 且 HAF 无 order => 绿。任一方缺失 => 红。
tcf_v=$(extract_order "$REPO/cloud-infra-common/src/main/java/com/cloudinfra/common/core/tenant/TenantContextFilter.java")
haf_v=$(extract_order "$REPO/cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/filter/HeaderAuthenticationFilter.java")
if [[ -n "$tcf_v" ]] && [[ -z "$haf_v" ]]; then
  echo "PASS TCF order=$tcf_v (无显式 order) 与 HAF 无 order => 信任链绿。"
else
  echo "FAIL TCF='$tcf_v' HAF='$haf_v'; 任一方缺失 => 信任链红"; fail=$((fail+1)); fail_lines+=("站 3: trust chain")
fi

echo ""
echo "==== 汇总 ===="
# 权威判据是**失败计数** fail, 不是 fail_lines —— 否则任何"只加计数不加清单"的站位
# 都会变成假绿(2026-11-12 修复前的实际行为: 站 1 / 站 2 红也报 ALL PASS)。
if [[ "$fail" -gt 0 ]]; then
  echo "❌ FAIL ($fail): ${fail_lines[*]}"
  exit 1
fi
echo "🟢 ALL PASS (exit 0)"
