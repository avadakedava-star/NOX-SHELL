import { useEffect, useState } from 'react'
import { listTasks, taskAction, taskDetail, taskEnded, type AgentTask } from '../api/tasks'
const labels={PENDING:'等待执行',RUNNING:'执行中',WAITING:'等待检查',CANCELLING:'正在取消',SUCCEEDED:'已完成',FAILED:'未完成',UNKNOWN:'结果待核查',CANCELLED:'已取消'}
export function TaskProgressPanel({sessionId,chatRunning}:{sessionId?:string;chatRunning:boolean}){
  const [tasks,setTasks]=useState<AgentTask[]>([])
  const [error,setError]=useState('')
  const [busy,setBusy]=useState('')
  const [detail,setDetail]=useState<AgentTask>()
  useEffect(()=>{
    setTasks([]);setDetail(undefined);setError('')
    if(!sessionId)return
    let stopped=false
    let timer:ReturnType<typeof setTimeout>
    const controller=new AbortController()
    async function refresh(){
      let next=8000
      try{const items=await listTasks(sessionId!,controller.signal);if(stopped)return;setTasks(items);setError('');if(items.some(t=>!taskEnded(t.status)) || chatRunning)next=2000}
      catch(e){if(!stopped)setError((e as Error).message)}
      finally{if(!stopped)timer=setTimeout(refresh,next)}
    }
    void refresh()
    return()=>{stopped=true;clearTimeout(timer);controller.abort()}
  },[sessionId,chatRunning])
  async function action(task:AgentTask,kind:'cancel'|'resume'){
    if(!sessionId)return
    setBusy(task.id);setError('')
    try{await taskAction(kind,task.id,sessionId);setTasks(await listTasks(sessionId));if(detail?.id===task.id)setDetail(await taskDetail(task.id,sessionId))}
    catch(e){setError((e as Error).message)}finally{setBusy('')}
  }
  if(!sessionId || (!tasks.length && !error))return null
  return <section className="mx-4 my-3 space-y-2" aria-label="后台任务">
    <p className="text-xs opacity-70">后台任务 · 关闭聊天后仍会继续执行</p>
    {tasks.slice(0,6).map(task=><article key={task.id} className="rounded-xl border border-current/15 p-3 text-xs space-y-2">
      <div className="flex justify-between gap-2"><strong>{task.title}</strong><span role="status">{labels[task.status]}</span></div>
      <p className="whitespace-pre-wrap break-words">{task.message}</p>
      <p className="opacity-60">目标：{task.target}</p>
      {task.port && <p className="opacity-70">{task.image} · {task.port}</p>}
      <ol className="space-y-1 opacity-80">{task.steps.map(step=><li key={step.key}>{step.status==='SUCCEEDED'?'✓':step.status==='FAILED'?'×':step.status==='RUNNING'?'◌':'·'} {step.title}</li>)}</ol>
      <div className="flex gap-3">
        {!taskEnded(task.status) && <button className="underline disabled:opacity-50" disabled={busy===task.id || task.cancelRequested || task.status==='CANCELLING'} onClick={()=>void action(task,'cancel')}>{task.cancelRequested?'取消请求已提交':'取消任务'}</button>}
        {task.status==='UNKNOWN' && <button className="underline disabled:opacity-50" disabled={busy===task.id} onClick={()=>void action(task,'resume')}>重新核查已有作业</button>}
        <button className="underline" onClick={()=>{if(detail?.id===task.id)setDetail(undefined);else void taskDetail(task.id,sessionId).then(setDetail).catch(e=>setError(e.message))}}>执行详情</button>
      </div>
      {detail?.id===task.id && <div className="max-h-64 overflow-auto space-y-2">{detail.details?.map(step=><div key={step.key}><p>{step.title}{step.exitCode!==null?` · 退出码 ${step.exitCode}`:''}</p><pre className="whitespace-pre-wrap break-all opacity-70">{step.output || '暂无输出'}</pre></div>)}</div>}
      <p className="text-[10px] opacity-50">编号：{task.id}</p>
    </article>)}
    {error && <p className="text-xs text-red-400" role="alert">任务进度暂时无法读取：{error}。这不表示远端任务已经停止。</p>}
  </section>
}
