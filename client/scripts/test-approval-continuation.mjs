import { buildSync } from 'esbuild'
import assert from 'node:assert/strict'

const [bundle] = buildSync({
  entryPoints: ['src/utils/approvalContinuation.ts'], bundle: true, format: 'esm', write: false,
}).outputFiles
const { approvalContinuation, approvalOutput, isLegacyApprovalMessage, lastUserTask, taskForApproval } =
  await import('data:text/javascript;base64,' + Buffer.from(bundle.text).toString('base64'))

const id = '12345678-1234-1234-1234-123456789abc'
const original = { role: 'user', content: '安装 Nginx' }
const legacy = {
  role: 'user',
  content: '用户已通过确认卡批准并执行操作，审批编号 ' + id + '。操作：查询。执行结果：旧输出。原任务：安装 Nginx',
}
const approvedRun = { role: 'assistant', trace: { tools: [{ approvalId: id }] } }
const newer = { role: 'user', content: '检查磁盘' }
assert.equal(isLegacyApprovalMessage(legacy), true)
assert.equal(isLegacyApprovalMessage({ ...legacy, role: 'assistant' }), false)
assert.equal(lastUserTask([original, legacy]), original.content)
assert.equal(taskForApproval([original, legacy, approvedRun, newer], id), original.content)

const operation = {
  command: '安装 nginx',
  status: 'SUCCEEDED',
  result: JSON.stringify({ stdout: 'x'.repeat(4500) + '安装结果', output: '重复结果', exitCode: 0 }),
}
const continuation = approvalContinuation(original.content, operation)
assert.ok(continuation.includes(original.content))
assert.ok(continuation.includes('安装结果'))
assert.ok(continuation.includes('"exitCode":0'))
assert.ok(continuation.length < 2400)
assert.ok(!continuation.includes('重复结果'))
assert.ok(!continuation.includes(legacy.content))
assert.equal(approvalOutput(JSON.stringify({ taskId: 'task-1', output: '已提交' })).taskId, 'task-1')
assert.equal(approvalOutput('操作未发送').output, '操作未发送')
console.log('审批续跑测试通过：原任务归属、历史兼容、输出去重和长度限制。')
