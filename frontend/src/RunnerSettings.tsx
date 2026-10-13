import {memo,useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';

type Runner={id:string;name:string;platform:string;registeredBy:string;createdAt:string;version:string;enabled:boolean;allowedInstanceIds:string[];online:boolean;lastSeen:string|null};
type Instance={config:{id:string;name:string}};

export const RunnerSettings=memo(function RunnerSettings({instances}:{instances:Instance[]}){
 const auth=useAuth();
 const [rows,setRows]=useState<Runner[]>([]),[name,setName]=useState(''),[code,setCode]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const load=async()=>{const list=await api<Runner[]>('/runners');setRows(Array.isArray(list)?list:[]);};
 useEffect(()=>{let live=true;let timer:ReturnType<typeof setTimeout>;const poll=async()=>{try{if(!document.hidden){const list=await api<Runner[]>('/runners');if(live)setRows(Array.isArray(list)?list:[]);}}catch{}finally{if(live)timer=setTimeout(poll,10000);}};poll();return()=>{live=false;clearTimeout(timer);};},[]);
 async function run(action:()=>Promise<void>,message:string){setBusy(true);setError('');setNotice('');try{try{await action();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await action();}await load();setNotice(message);}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <section className="runner-settings"><h2>서버 실행기</h2><p>Mac·Windows 컴퓨터를 등록해 두면 켜져 있는 실행기에 서버를 배정할 수 있습니다. 초대 사용자도 자기 컴퓨터를 등록할 수 있습니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}{notice&&<div role="status" className="notice alert">{notice}</div>}
 <label className="security-label">새 실행기 이름<input value={name} maxLength={80} onChange={e=>setName(e.target.value)}/></label>
 <button disabled={busy||!name.trim()} onClick={()=>run(async()=>{const result=await api<{code:string}>('/runners/enroll','POST',{name:name.trim()});setCode(result.code);},'5분 동안 유효한 등록 코드를 발급했습니다.')}>패스키로 실행기 등록 코드 발급</button>
 {code&&<label className="security-label">한 번만 표시되는 등록 코드<input readOnly value={code} onFocus={e=>e.target.select()}/></label>}
 {rows.map(row=><div className="member" key={row.id}><strong>{row.name} · {row.platform} · {row.online?'온라인':row.enabled?'오프라인':'폐기됨'}</strong><p className="muted">버전 {row.version} · 마지막 연결 {row.lastSeen?new Date(row.lastSeen).toLocaleString():'없음'}</p>
 {row.registeredBy===auth.session.user?.id&&<><p>이 컴퓨터에서 실행해도 되는 서버를 한 번씩 허용하세요.</p><div className="permission-grid">{instances.map(({config:i})=><label key={i.id} className="check-label"><input type="checkbox" disabled={busy||!row.enabled} checked={row.allowedInstanceIds.includes(i.id)} onChange={e=>{const allowed=e.target.checked;run(()=>api(`/runners/${row.id}/allow/${i.id}`,allowed?'PUT':'DELETE'),allowed?'이 서버의 실행을 허용했습니다.':'이 서버의 새 배정을 막았습니다.');}}/>{i.name}</label>)}</div></>}
 {(row.registeredBy===auth.session.user?.id||auth.session.user?.owner)&&<button disabled={busy||!row.enabled} onClick={()=>{if(confirm(`${row.name} 실행기 등록을 폐기할까요?`))run(()=>api(`/runners/${row.id}`,'DELETE'),'실행기를 폐기했습니다.');}}>기기 등록 폐기</button>}
 </div>)}
 </section>;
});
