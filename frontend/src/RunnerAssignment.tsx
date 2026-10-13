import {memo,useEffect,useState} from 'react';
import {api} from './http';

type Runner={id:string;name:string;platform:string;online:boolean;enabled:boolean;allowedInstanceIds:string[]};

export const RunnerAssignment=memo(function RunnerAssignment({instanceId,runnerId,disabled,onAssigned}:{instanceId:string;runnerId:string|null;disabled:boolean;onAssigned:()=>Promise<void>}){
 const [runners,setRunners]=useState<Runner[]>([]),[busy,setBusy]=useState(false),[error,setError]=useState('');
 useEffect(()=>{let live=true;let timer:ReturnType<typeof setTimeout>;const poll=async()=>{try{const result=await api<Runner[]>('/runners');if(live)setRunners(Array.isArray(result)?result:[]);}catch{}finally{if(live)timer=setTimeout(poll,10000);}};poll();return()=>{live=false;clearTimeout(timer);};},[]);
 const selected=runners.find(r=>r.id===runnerId);
 const choices=runners.filter(r=>r.enabled&&r.allowedInstanceIds.includes(instanceId));
 const assign=async(value:string)=>{setBusy(true);setError('');try{await api(`/instances/${instanceId}/runner`,'PUT',{runnerId:value||null});await onAssigned();}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 return <div className="member"><h3>실행 컴퓨터</h3><p>서버를 완전히 종료한 뒤 실행기를 변경할 수 있습니다. 기기 등록자가 이 서버의 실행을 허용해야 선택할 수 있습니다.</p>
 <label className="security-label">실행 위치<select value={runnerId||''} disabled={disabled||busy} onChange={e=>assign(e.target.value)}><option value="">이 관리 Mac</option>{choices.map(r=><option key={r.id} value={r.id}>{r.name} · {r.platform} · {r.online?'온라인':'오프라인'}</option>)}{runnerId&&selected==null&&<option value={runnerId}>등록되지 않았거나 접근할 수 없는 실행기</option>}</select></label>
 {selected&&<p className="muted">현재 실행기: {selected.name} · {selected.online?'연결됨':'오프라인'}. 게임 포트는 관리 Mac에서 공개됩니다. 외부 접속은 이 Mac의 게임 포트를 포워딩하세요.</p>}
 {error&&<p role="alert" className="error-banner">{error}</p>}</div>;
});
