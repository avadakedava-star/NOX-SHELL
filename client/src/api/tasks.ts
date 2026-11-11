import { getRequestBaseUrl } from './request'
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING' | 'CHECKING' | 'WAITING_CONNECTION' | 'NEEDS_ATTENTION' | 'CANCELLING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'CANCELLED'
export interface TaskStep { key:string; title:string; status:string; exitCode:number|null; startedAt:number; finishedAt:number; output?:string }
export interface AgentTask { id:string; target:string; title:string; kind:string; status:TaskStatus; stage:string; message:string; resourceId?:string; port?:string; image?:string; containerName?:string; createdAt:number; updatedAt:number; deadline:number; lastEvidenceAt?:number; lastActivityAt?:number; progressEvidence?:string; cancelRequested:boolean; steps:TaskStep[]; details?:TaskStep[] }
const userId='walissh-user'
const base=()=>`${getRequestBaseUrl()}/api/v1/agent_task`
async function decode<T>(response:Response):Promise<T>{const body=await response.json();if(!response.ok || body.code!=='0000')throw new Error(body.info || '读取任务失败');return body.data as T}
export async function listTasks(sessionId:string,signal?:AbortSignal):Promise<AgentTask[]>{return decode(await fetch(`${base()}/list?${new URLSearchParams({userId,sessionId})}`,{signal}))}
export async function taskDetail(taskId:string,sessionId:string):Promise<AgentTask>{return decode(await fetch(`${base()}/detail?${new URLSearchParams({userId,sessionId,taskId})}`))}
export async function taskAction(action:'cancel'|'resume',taskId:string,sessionId:string):Promise<void>{await decode(await fetch(`${base()}/${action}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({taskId,userId,sessionId})}))}
export const taskEnded=(status:TaskStatus)=>['SUCCEEDED','FAILED','UNKNOWN','CANCELLED'].includes(status)
