#!/bin/bash
# @id: S09
# @name: 会话中杀 client
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, IDLE, absent, absent, off)
# @budget_s: 240
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,kill_client
# @refs: docs/e2e-scenarios.md §4 S09
#
# S01 + kill_client（am force-stop B 的 client）。断言（docs §4 S09）：收尾清理
# 全过（同 S03 断言集）+ car 回 IDLE。
# 机理注记（源码实证）：引擎经 LIFECYCLE_PORT 连的就是 client 进程（connectToPhoneHost），
# client 一死，引擎 readLifecycleCommands 的 finally 置 running=false 并跑完整
# cleanup()——与 S03 优雅退出同一条引擎侧路径，故同样要求无强制 kill 串；
# 9637 随 client 进程死亡释放；phone_state_now 进程存亡优先，client 死后恒 IDLE。
# @post 全中立：杀 client 等于把 B 侧应用层清掉，剩下的由 restore 的中立化兜底。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  run_op kill_client || { log_err "kill_client op 失败"; return 1; }
  log_step "B 的 client 已 force-stop，等引擎经 lifecycle 通道断开自收"
  return 0
}

scenario_asserts() {
  local rc=0
  # 收尾清理全过（S03 同款断言集）：引擎退出（≤30s 观测窗）+ 9637 释放 +
  # 哨兵恢复基线 + 无强制 kill（vdlog 面）
  assert_teardown_clean || rc=1
  # car 回 IDLE（A 的 server 进程还活着，但会话已断——日志末条状态行应是断开）
  wait_state_eq car IDLE 30 || { bad "car 未回 IDLE（$(car_state_now)）"; rc=1; }
  # phone：client 进程已死 → phone_state_now 恒 IDLE（同时反证 kill_client 真的杀了进程）
  wait_state_eq phone IDLE 30 || { bad "phone 未回 IDLE（$(phone_state_now)）"; rc=1; }
  # 无 ANR/崩溃 + 无回归标记
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
