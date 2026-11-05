import { useEffect, useState } from 'react'
import { decideApproval, listApprovals, type CommandApproval } from '../api/agent'
import { useAgentStore } from '../stores/agentStore'

const USER_ID='walissh-user'
export function CommandApprovalCard({approvalId}: {approvalId:string}) {
  const currentSessionId=useAgentStore(s=>s.currentSessionId)
  const session=useAgentStore(s=>currentSessionId ? s.sessions.get(currentSessionId):undefined)
  const isLoading=useAgentStore(s=>s.isLoading)
  const [approval,setApproval]=useState<CommandApproval>()
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  useEffect(()=>{
    let active=true
    if(session?.backendSessionId) listApprovals(USER_ID,session.backendSessionId).then(items=>{
      if(active) {
        const record=items.find(item=>item.id===approvalId);setApproval(record)
        if(record && record.status!=='PENDING') useAgentStore.getState().setApprovalResult(approvalId,record.status)
      }
    }).catch(e=>{if(active)setError(String(e.message))})
    return ()=>{active=false}
  },[approvalId,session?.backendSessionId])
  async function decide(approve:boolean) {
    if(!approval || busy) return
    setBusy(true);setError('')
    try {
      const result=await decideApproval(approval.id,USER_ID,approval.sessionId,approve)
      setApproval(result)
      useAgentStore.getState().setApprovalResult(approvalId,result.status)
      if(result.status==='SUCCEEDED' && useAgentStore.getState().currentSessionId===currentSessionId && !useAgentStore.getState().isLoading) {
        const task=[...(session?.messages || [])].reverse().find(message=>message.role==='user')?.content || ''
        await useAgentStore.getState().sendMessage(`用户已通过确认卡批准并执行操作，审批编号 ${result.id}。操作：${result.command}。执行结果：${result.result}。该操作已经执行，请勿重复。请基于结果继续完成原任务；新的变更仍须单独确认。原任务：${task}`,result.terminalSessionId)
      }
    } catch(e) {
      setError((e as Error).message)
      // 响应丢失可能发生在执行之后，只查询保存状态，不自动再次批准或执行。
      try {
        const saved=(await listApprovals(USER_ID,approval.sessionId)).find(item=>item.id===approvalId)
        if(saved){setApproval(saved);useAgentStore.getState().setApprovalResult(approvalId,saved.status)}
      }catch{}
    } finally {setBusy(false)}
  }
  return <section className="m-3 rounded-xl border border-amber-400/50 bg-amber-50/5 p-3 text-xs" aria-label="变更确认">
    <div className="mb-2 font-semibold">{approval?.status==='PENDING' ? '等待你确认' : '变更确认记录'}</div>
    {approval && <>
      <p className="mb-2">目标：{approval.host} · 账号：{approval.username}</p>
      <p className="mb-2">{approval.command}</p>
      {approval.kind==='CONFIG' ? <>
        <p>文件：{approval.path}。使用 sudo -n；先核对旧内容、生成备份，再替换。</p>
        <details className="my-2"><summary>查看完整旧配置</summary><pre className="max-h-72 overflow-auto whitespace-pre-wrap p-2">{approval.expectedContent}</pre></details>
        <details className="my-2" open><summary>查看完整新配置</summary><pre className="max-h-72 overflow-auto whitespace-pre-wrap p-2">{approval.content}</pre></details>
      </> : <pre className="mb-2 max-h-64 overflow-auto whitespace-pre-wrap break-all p-2">{approval.executionCommand}</pre>}
      <p className="mb-2 opacity-75">批准只执行上面的操作一次；账号权限不足时会报错，不会等待密码。确认有效期至 {new Date(approval.expiresAt).toLocaleTimeString()}。</p>
      {approval.status==='PENDING' ? <div className="flex gap-2">
        <button className="rounded bg-amber-500 px-3 py-2 text-black disabled:opacity-50" disabled={busy || isLoading || approval.expiresAt<=Date.now()} onClick={()=>decide(true)}>{busy ? '处理中…':'批准并执行一次'}</button>
        <button className="rounded border px-3 py-2 disabled:opacity-50" disabled={busy} onClick={()=>decide(false)}>拒绝</button>
      </div> : <p>状态：{({RUNNING:'执行中或结果尚未保存，请核查，勿重复执行',SUCCEEDED:'执行成功',FAILED:'执行失败',UNKNOWN:'结果未知，请核查服务器',REJECTED:'已拒绝',EXPIRED:'已过期'})[approval.status]}</p>}
      {approval.result && <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-all">{approval.result}</pre>}
    </>}
    {!approval && !error && <p>正在读取保存的操作方案…</p>}
    {error && <p className="mt-2 text-red-500" role="alert">{error}</p>}
  </section>
}
