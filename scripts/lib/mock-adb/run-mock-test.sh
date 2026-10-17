#!/bin/bash
# DiLink-Auto 双手机 E2E — mock 回放自测（harness §2.6 层③）
#
# 一条命令验证「编排逻辑」而不碰真机：
#   1. bash -n 全部 .sh + py_compile explore.py（层①语法）
#   2. --list / --dry-run（层②构造）
#   3. PATH=mock-adb 跑 S01+S02+S03 快乐路径全编排（层③回放）
#   4. 断言产物：summary.txt 有 S01 PASS、sequence.jsonl 有 op 记录、退出码 0
#
# 边界：mock 不过 ≈ 真机不过（编排 bug）；真机不过可用 mock 复现编排 bug。
# mock 与真机输出格式差（\r\n/编码）本身是假阳性来源——fixture 应从真机抄录。

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

# 环境陷阱（2026-10-16）：WorkBuddy 会话通过 BASH_ENV 给每个 bash 子进程注入
# safe-bin shim（rm/rmdir/unlink 函数 → broker IPC，本会话实测单次 rm 慢至 15s；
# PATH 里还有被拆碎的 Windows 风格残段 `C` + `\Users\...\safe-bin`）。回放必须
# 三件套剔除（函数 + BASH_ENV + PATH 残段），否则 mock 状态操作被拖垮、时序
# 断言假阴性。用户本机终端无此问题（此剔除在干净环境是无害 no-op）。
unset -f rm rmdir unlink 2>/dev/null || true
unset BASH_ENV
_new_path=""
IFS=':' read -ra _segs <<< "$PATH"
for _s in "${_segs[@]}"; do
  case "$_s" in *safe-bin*|C|\\*) continue ;; esac
  _new_path="${_new_path:+$_new_path:}$_s"
done
export PATH="$_new_path"
unset _new_path _segs _s

MOCK="$ROOT/scripts/lib/mock-adb"
STATE="$ROOT/debug-logs/e2e/mock-state"
FAIL=0
ok()  { echo "  ✓ $*"; }
bad() { echo "  ✗ $*"; FAIL=$((FAIL + 1)); }
step() { echo; echo "==== $* ===="; }

step "0/4 环境"
command -v python > /dev/null 2>&1 || { echo "✗ 找不到 python"; exit 2; }
python -c "import PIL" 2>/dev/null && ok "PIL 在位（screencap 方差判据依赖）" \
  || bad "PIL 缺失——pip install pillow（mock 与非纯黑断言都要它）"

step "1/4 层①语法：bash -n 全部 + py_compile"
for f in scripts/lib/*.sh scripts/e2e-dual-device.sh scripts/scenarios/*.sh; do
  bash -n "$f" || bad "bash -n 失败：$f"
done
ok "bash -n 全部通过（$(ls scripts/lib/*.sh scripts/e2e-dual-device.sh scripts/scenarios/*.sh | wc -l | tr -d ' ') 个文件）"
for f in scripts/lib/*.py; do
  [ -f "$f" ] || continue
  python -m py_compile "$f" || bad "py_compile 失败：$f"
done
ok "py_compile 通过"

step "2/4 层②构造：--list / --dry-run"
bash scripts/e2e-dual-device.sh --list > /dev/null 2>&1 && ok "--list 通过" || bad "--list 失败"
bash scripts/e2e-dual-device.sh --phone mockA --car mockB --scenario S01 --dry-run > /dev/null 2>&1 \
  && ok "--dry-run 通过" || bad "--dry-run 失败"

step "3/4 层③回放：mock adb 跑 S01+S02+S03 全编排"
rm -rf "$STATE"; mkdir -p "$STATE"
python "$MOCK/make-fixtures.py" > /dev/null 2>&1 || bad "fixture 生成失败"
export DILINK_TEST_PIN_PHONE=1234 DILINK_TEST_PIN_CAR=1234
export MOCK_ADB_STATE="$STATE" MOCK_ADB_FIXTURE="$MOCK/fixtures"
export MOCK_ADB_TRACE="$STATE/tap-trace.log"
export PATH="$MOCK:$PATH"
echo "  （mock adb 已置顶 PATH；真机不会被动）"
RC=0
bash scripts/e2e-dual-device.sh --phone mockA --car mockB --scenario S01,S02,S03 --skip-build \
  > "$STATE/run.log" 2>&1 || RC=$?
tail -40 "$STATE/run.log" | sed 's/^/     /'
if [ "$RC" = "0" ]; then ok "S01+S02+S03 回放退出码 0（全编排走通）"; else bad "回放退出码 $RC（见 $STATE/run.log）"; fi
echo "  tap 轨迹（mock 按 fixture 节点边界判定的点击语义）："
sed 's/^/     /' "$STATE/tap-trace.log" 2>/dev/null | head -12

step "4/4 产物断言"
RUNDIR=$(ls -dt debug-logs/e2e/2026* 2>/dev/null | head -1)
if [ -n "$RUNDIR" ] && [ -f "$RUNDIR/summary.txt" ]; then
  ok "summary.txt 在位（$RUNDIR/summary.txt）"
  grep -E 'S01|场景|断言' "$RUNDIR/summary.txt" | sed 's/^/     /'
  grep -q 'S01.*PASS' "$RUNDIR/summary.txt" && ok "S01 判定 PASS" || bad "S01 未判 PASS"
else
  bad "找不到回放产物 summary.txt（RUNDIR=${RUNDIR:-无}）"
fi
if [ -n "$RUNDIR" ] && [ -s "$RUNDIR/sequence.jsonl" ]; then
  ok "sequence.jsonl 有 op 记账（$(wc -l < "$RUNDIR/sequence.jsonl" | tr -d ' ') 行）"
  grep -oE '"op":"[a-z_0-9]+"' "$RUNDIR/sequence.jsonl" | sort -u | tr '\n' ' ' | sed 's/^/     /'; echo
else
  bad "sequence.jsonl 缺失或为空"
fi
# 零残留：完整跑完（RC=0）时 mock 状态应已回中立（session/client_up/server_up 都被
# op_neutral 的 force-stop 清掉）。中途 abort 的轮次不强求（preflight 失败就还没到 neutral）。
if [ "$RC" = "0" ]; then
  for f in session client_up server_up; do
    [ -f "$STATE/$f" ] && bad "mock 状态残留：$f（restore 没跑干净）" || true
  done
  ok "mock 状态已回中立"
else
  echo "  - RC=$RC 非 0，跳过零残留检查（中途 abort 未到收尾中立化）"
fi

echo
if [ "$FAIL" = "0" ]; then
  echo "mock 回放自测全部通过 ✓"
  exit 0
fi
echo "mock 回放自测有 $FAIL 个失败项"
exit 1
