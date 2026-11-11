import { buildSync } from 'esbuild'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'
import assert from 'node:assert/strict'

// 编译真实 store，直接测试产品使用的 reducer；不访问模型或网络。
const bundle = buildSync({
  entryPoints: ['src/stores/agentStore.ts'], bundle: true, platform: 'node', format: 'cjs', write: false,
  define: { 'import.meta.env': JSON.stringify({ DEV: true }) }, logLevel: 'silent',
}).outputFiles[0].text
const module = { exports: {} }
const storageValues=new Map()
runInNewContext(bundle, {
  module, exports: module.exports, require: createRequire(import.meta.url), console, process,
  setTimeout, clearTimeout, setInterval, clearInterval, AbortController,
  localStorage: { getItem: key => storageValues.get(key) ?? null, setItem: (key,value) => storageValues.set(key,value), removeItem: key => storageValues.delete(key) },
})
const { createRunTrace, applyTraceEvent, applyApprovalResult } = module.exports
const call = { event: 'tool_call', toolCallId: 'exec-1', toolName: 'executeCommand', status: 'running', totalToolCalls: 1 }

let trace = applyTraceEvent(createRunTrace(), {
  event: 'tool_result', toolCallId: 'blocked-1', toolName: 'executeCommand', status: 'blocked', executionStatus: 'BLOCKED',
  ruleId: 'C002', toolArgs: '{"command":"rm -fr /"}', content: '自动删除被禁止', totalToolCalls: 0,
})
assert.equal(trace.tools[0].status, 'blocked')
assert.equal(trace.tools[0].ruleId, 'C002')
assert.equal(trace.totalToolCalls, 0)
assert.match(trace.tools[0].argumentsText, /rm -fr/)

trace = applyTraceEvent(createRunTrace(), {
  event: 'tool_result', toolCallId: 'rewrite-1', status: 'blocked', executionStatus: 'BLOCKED',
  rewriteAllowed: true, rewritesRemaining: 1, totalToolCalls: 0,
})
assert.equal(trace.stage, 'thinking')
assert.equal(trace.totalToolCalls, 0)
assert.equal(trace.tools[0].rewriteAllowed, true)
assert.match(trace.statusText, /改写/)
trace = applyTraceEvent(trace, { event: 'done', content: '查询完成' })
assert.equal(trace.stage, 'completed')

trace = applyTraceEvent(createRunTrace(), {
  event: 'tool_result', toolCallId: 'pending-1', toolName: 'executeCommand', status: 'pending',
  executionStatus: 'WAITING_APPROVAL', approvalId: 'approval-1', totalToolCalls: 0,
})
assert.equal(trace.stage, 'waiting_approval')
assert.equal(trace.totalToolCalls, 0)
assert.equal(trace.tools[0].approvalId, 'approval-1')
trace=applyTraceEvent(trace,{event:'done',stopReason:'WAITING_APPROVAL',content:'等待确认'})
assert.equal(trace.stage,'waiting_approval')
assert.equal(trace.error,undefined)
assert.equal(trace.tools[0].status,'pending')
assert.equal(applyApprovalResult(trace,'approval-1','REJECTED').stage,'stopped')
assert.equal(applyApprovalResult(trace,'approval-1','SUCCEEDED').stage,'completed')
assert.equal(applyApprovalResult(trace,'approval-1','UNKNOWN').stage,'error')
assert.equal(applyApprovalResult(trace,'other','SUCCEEDED').stage,'waiting_approval')
const store=module.exports.useAgentStore
store.setState({sessions:new Map([['approval-session',{id:'approval-session',name:'审批测试',backendSessionId:'backend-session',createdAt:1,messages:[{id:'msg',role:'assistant',content:'等待确认',timestamp:1,trace}]}]]),currentSessionId:'approval-session'})
store.getState().setApprovalResult('approval-1','PENDING')
store.setState({sessions:new Map(),currentSessionId:null})
store.getState().loadPersistedSessions()
assert.equal(store.getState().currentSessionId,'approval-session')
assert.equal(store.getState().sessions.get('approval-session').messages[0].trace.stage,'waiting_approval')
store.getState().setApprovalResult('approval-1','REJECTED')
store.setState({sessions:new Map(),currentSessionId:null})
store.getState().loadPersistedSessions()
assert.equal(store.getState().sessions.get('approval-session').messages[0].trace.stage,'stopped')

trace = applyTraceEvent(applyTraceEvent(createRunTrace(), call), {
  event: 'tool_result', toolCallId: 'exec-1', status: 'unknown', executionStatus: 'UNKNOWN', content: '远程结果未知',
})
assert.equal(trace.tools[0].status, 'unknown')
assert.equal(applyTraceEvent(trace, { event: 'done' }).tools[0].status, 'unknown')

trace = applyTraceEvent(applyTraceEvent(createRunTrace(), call), { event: 'done' })
assert.equal(trace.tools[0].status, 'unknown')

trace = applyTraceEvent(applyTraceEvent(createRunTrace(), call), {
  event: 'error', stopReason: 'CONSECUTIVE_FAILURE_LIMIT', content: '连续失败 3 次，停止自动执行',
})
assert.equal(trace.stopReason, 'CONSECUTIVE_FAILURE_LIMIT')
assert.equal(trace.tools[0].status, 'unknown')
assert.equal(trace.stage, 'error')

trace = applyTraceEvent(createRunTrace(), { event: 'tool_result', toolCallId: 'blocked-1', status: 'blocked', executionStatus: 'BLOCKED', totalToolCalls: 0 })
assert.equal(applyTraceEvent(trace, { event: 'done' }).tools[0].status, 'blocked')
console.log('执行状态测试通过：拦截、计数、参数保留、未知结果、结束事件和停止原因。')

trace=applyTraceEvent(createRunTrace(),{event:'tool_result',toolCallId:'ordinary-failure',toolName:'executeCommand',status:'error',executionStatus:'REPORTED_FAILURE',content:'No such file'})
assert.equal(trace.stage,'thinking')
trace=applyTraceEvent(trace,{event:'text',content:'文件不存在，可以继续'})
trace=applyTraceEvent(trace,{event:'run_finished',status:'completed',stopReason:'completed'})
assert.equal(trace.stage,'completed')
assert.equal(applyTraceEvent(trace,{event:'status',stage:'thinking',content:'late'}).stage,'completed')
trace=applyTraceEvent(createRunTrace(),{event:'run_finished',status:'failed',stopReason:'REQUEST_TIMEOUT',content:'模型超时'})
assert.equal(trace.stage,'error');assert.equal(trace.stopReason,'REQUEST_TIMEOUT')
console.log('请求终态定向测试通过：普通工具失败继续、明确终态结束、忽略晚到状态。')

trace=applyTraceEvent(applyTraceEvent(createRunTrace(),call),{event:'run_finished',status:'paused',stopReason:'PROGRESS_STALLED',content:'没有新证据，保留原作业待核查'})
assert.equal(trace.stage,'paused')
assert.equal(trace.error,undefined)
assert.equal(trace.stopReason,'PROGRESS_STALLED')
assert.equal(trace.tools[0].status,'unknown')
assert.equal(applyTraceEvent(trace,{event:'status',stage:'thinking',content:'late'}).stage,'paused')
console.log('进展暂停测试通过：暂停不标成失败或成功，保留未知工具状态，忽略晚到事件。')
