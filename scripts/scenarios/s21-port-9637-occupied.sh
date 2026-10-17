#!/bin/bash
# @id: S21
# @name: 9637 被抢占
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, WAITING, absent, absent, off)
# @budget_s: 300
# @priority: P2
# @expect: fail
# @invariants: full
# @ops: neutral,occupy_9637,reconnect
# @refs: docs/e2e-scenarios.md §4 S21；.plan/e2e-harness-design.md §2.4
#
# 9637 被外用进程（toybox nc，op_occupy_9637）占用时建流：按设计应失败并
# 给出明确状态。@expect: fail——expect_fail 只翻转主操作判向；健康类不变量
# 照常硬断言，不因 @expect: fail 翻转（§2.4 核心规则）。
#
# 失败面预分析（源码级，2026-10-16 写场景时逐字核对）：
#   - op_occupy_9637 先 force-stop client 再占端口 → connect 时 B 的 client 不在
#     （A 的 server app 由 setup 的 launch_server 起着——op_connect 只驱动 A 的
#     UI，不负责拉起；缺它 connect 会挂在控件定位上，那是 env 失败不是预期失败面）。
#   - A 的 WiFi 轨连 B:9637：nc 在 LISTEN → TCP 通但无协议响应 → 控制连接
#     watchdog 10s 超时断开（Connection.kt:261，HEARTBEAT_TIMEOUT_MS=10000）；
#     若 nc 已退 → TCP 拒绝（CarConnectionService.kt:606 "WiFi connect failed:"）。
#   - A 的 TCP ADB 轨连 B:5555（授权仍在）→ connectTcpAdb 会 am start 重启
#     B 的 client（CarConnectionService.kt:826）→ client bind 9637 撞 nc →
#     BindException → client.log「Connection error: …」（ConnectionService.kt:439）。
#   - 引擎在 handshakeDone 后即由 A 部署（A 侧不等 HANDSHAKE_RESPONSE，
#     CarConnectionService.kt:596/821-823）——失败后引擎是否自清由
#     wait_engine_gone 实测；残留 = 失败路径泄漏，诚实记录。
# 证据串（全部源码逐字，observability 式校准，非猜测）：
#   client.log「Connection error:」/ A logcat「WiFi connect failed:」
#   / A logcat「watchdog: no frame received」/ A logcat「Failed to connect ADB to」。
#   四条全未命中 → 宽松兜底「ERROR」（P0.5 校准点：真机首跑后按 §F 收紧）。
#
# 与 §2.4 骨架的两处偏差（均有据，记录在案）：
#   ① 主操作判向（2026-10-17 批次 C 修正）：原用 op_connect 驱动 A 的 UI，但
#      9637 被占时 A 的「自动连接尝试→失败」不产生任何 client.log 状态行
#      （car_state=UNKNOWN）→ op_connect ①窗口判「未自动连接」→ 走手动 UI 兜底
#      → ManualConnectBox 真机不可达（竖屏超高被裁）→ UI 驱动失败 rc=1 → 误判
#      「ops 失败」FAIL（而非 PASS(expected)）。改挂 reconnect 原语（重启 A 的
#      app，rc 恒 0 语义纯净）：启动路径自动连接必然发起（WiFi 轨 + ADB 轨），
#      产品级成败看 60s 收敛窗口——不收敛 STREAMING = 按预期失败（ok）；
#      竟收敛 = UNEXPECTED-PASS（bad）。与 expect_fail 的「rc 判向翻转」语义
#      等价，只是把判据从 op rc 换成产品事实。
#   ② 9637 终态：骨架的 wait_port_free 30 只认「端口释放」。但 A 的 TCP ADB
#      轨会 am start 重启 B 的 client（CarConnectionService.kt:826），nc 退出
#      后 client 重试 bind 成功 → 9637 由产品持守（phone=WAITING）是合法收敛，
#      不算冲突残留。故改为三态判据：free / 产品持守 / 缺陷态（nc 仍占或
#      属主不明）。
# SKIPPED(env) 语义：occupy_9637 返回 2 = toybox 无 nc（.plan 风险 §5-3：
# Android 无内置监听工具）→ setup return 0，ops/asserts 显式 log_step 记
# SKIPPED(env) 且 asserts return 0——缺断言=没测，不混入通过（P6）。

scenario_setup() {
  run_op neutral || return 1
  run_op occupy_9637 || {
    local rc=$?
    if [ "$rc" = "2" ]; then
      log_step "SKIPPED(env)：toybox 无 nc——无法构造 9637 占用（不得假通过，P6）"
      skip_env "toybox 无 nc——无法构造 9637 占用（.plan 风险 §5-3）"
      return 0
    fi
    log_err "occupy_9637 失败（rc=$rc）——非 env 豁免面"
    return 1
  }
  # A 的 app 由 ops 的 reconnect 原语拉起（force-stop + monkey，触发启动路径
  # 自动连接）——setup 不再预拉起（2026-10-17 批次 C：预拉起的 A 其失败尝试
  # 不产生状态行，判定困难；统一走 reconnect 一条路径）。B 的 client 故意不拉：
  # occupy 已 force-stop 它，失败面就是「连接过程中 client 被 A 的 adb 轨重启后
  # bind 撞 nc」。
  return 0
}

scenario_ops() {
  if [ -n "${E2E_SCENARIO_SKIP:-}" ]; then
    log_step "SKIPPED(env)：9637 占用未构造，跳过 connect"
    return 0
  fi
  # 主操作判向（见文件头偏差①）：reconnect 只做「重启 A 的 app」（rc 恒 0、
  # 语义纯净，不含 UI 驱动）；产品级成败看 60s 收敛窗口。
  run_op reconnect || { log_err "reconnect op 失败（重启 A 的 app）"; return 1; }
  local deadline=$((SECONDS + 60)) cur=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur=$(car_state_now)
    [ "$cur" = "STREAMING" ] && break
    [ "$cur" = "IDLE" ] && break   # 已收敛到明确的失败终态，无需等满窗口
    sleep 2
  done
  if [ "$cur" = "STREAMING" ]; then
    bad "UNEXPECTED-PASS：9637 被占时建流竟收敛 STREAMING（按设计应失败却成功——预期过期或产品缺陷）"
    return 1
  fi
  ok "按预期失败：9637 被占时建流未收敛（car=$cur phone=$(phone_state_now)）"
  return 0
}

scenario_asserts() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  mkdir -p "$d"
  if [ -n "${E2E_SCENARIO_SKIP:-}" ]; then
    # 显式 SKIPPED(env) 留痕（P6：缺断言=没测）；return 0 让编排记
    # SKIPPED(env) 而非 FAIL——真机无 nc 时本场景不构成任何产品判定。
    log_step "S21 SKIPPED(env)：toybox 无 nc，9637 占用未构造——未执行任何产品断言"
    return 0
  fi
  local rc=0
  # —— 失败必须「明确」：有错误/引导证据；静默失败 = 产品缺陷（§2.4）——
  logcat_dump car   "$d/logcat-car.txt"   > /dev/null 2>&1 || true
  logcat_dump phone "$d/logcat-phone.txt" > /dev/null 2>&1 || true
  pull_clientlog "$d/client.log"          > /dev/null 2>&1 || true
  local hit=0 f pat
  for f in "$d/logcat-car.txt" "$d/client.log" "$d/logcat-phone.txt"; do
    for pat in 'Connection error:' 'WiFi connect failed:' 'watchdog: no frame received' 'Failed to connect ADB to'; do
      if [ "$(countF "$f" "$pat")" -ge 1 ]; then
        ok "失败证据命中（源码逐字串）：「$pat」@$(basename "$f")"
        hit=1; break 2
      fi
    done
  done
  if [ "$hit" = "0" ]; then
    # P0.5 校准点：四条源码串均未命中。宽松兜底只记「弱通过」并告警——真机
    # 首跑后必须按 .plan/e2e-observability.md §F 校准收紧，不减轻缺失事实。
    local c; c=$(countF "$d/logcat-car.txt" 'ERROR')
    if [ "$c" -ge 1 ]; then
      ok "失败证据（弱通过·宽松兜底串「ERROR」×$c）——P0.5 待按 §F 收紧"
    else
      bad "静默失败：A logcat/client.log 既无源码串也无 ERROR——不给明确错误是产品缺陷"
      rc=1
    fi
  fi
  # —— 健康类不变量照常硬断言，不因 @expect: fail 翻转（§2.4 核心规则）——
  assert_no_anr "$d/logcat-phone.txt" "$d/logcat-car.txt" || rc=1
  pull_vdlog "$d/vd-server.log" > /dev/null 2>&1 || true
  assert_no_force_kill "$d/vd-server.log" || rc=1
  wait_engine_gone 30 && ok "失败后无引擎残留" || { bad "引擎残留（pid=$(engine_pid)）——失败路径泄漏"; rc=1; }
  # 9637 终态三态判据（见文件头偏差②）：free / 产品持守 / 缺陷态。
  if wait_port_free phone 9637 30; then
    ok "9637 已释放（nc 退出，无残留监听）"
  else
    local holder
    holder=$(dphone "ss -ltnp 2>/dev/null | grep ':9637'" 2>/dev/null | tr -d '\r')
    case "$holder" in
      *nc*)
        bad "9637 仍被 nc 占用——冲突未解除（$holder）"; rc=1 ;;
      *)
        if [ "$(phone_state_now)" = "WAITING" ]; then
          ok "9637 由产品持守（phone=WAITING）——nc 退出后 client 重试 bind 自恢复，端口归正确属主"
        else
          bad "9637 仍 LISTEN 但属主/状态不明（holder=${holder:-<ss 无进程信息：shell 无权看 app 属主>} phone=$(phone_state_now)）"; rc=1
        fi ;;
    esac
  fi
  return $rc
}

scenario_restore() {
  # op_neutral 会读 /sdcard/DiLinkAuto/e2e-occupy.pid 兜底杀掉 nc 占位进程
  # （ops.sh op_neutral 步 4）——这里无需也不能自己 kill（场景不改设备状态）。
  run_op neutral
}
