import {memo,useEffect,useState} from 'react';
import {api} from './http';

type Runner={id:string;name:string;platform:string;online:boolean;enabled:boolean;allowedInstanceIds:string[]};

export const RunnerAssignment=memo(function RunnerAssignment({instanceId,runnerId,disabled,onAssigned,onRegister,requireRunner=false}:{instanceId:string;runnerId:string|null;disabled:boolean;onAssigned:()=>Promise<void>;onRegister:()=>void;requireRunner?:boolean}){
 const [runners,setRunners]=useState<Runner[]>([]),[busy,setBusy]=useState(false),[error,setError]=useState('');
 useEffect(()=>{let live=true;let timer:ReturnType<typeof setTimeout>;const poll=async()=>{try{const result=await api<Runner[]>('/runners');if(live){setRunners(Array.isArray(result)?result:[]);setError('');}}catch(e){if(live)setError('실행기 목록을 불러오지 못했습니다. '+(e as Error).message);}finally{if(live)timer=setTimeout(poll,10000);}};poll();return()=>{live=false;clearTimeout(timer);};},[]);
 const selected=runners.find(r=>r.id===runnerId);
 const choices=runners.filter(r=>r.enabled&&r.allowedInstanceIds.includes(instanceId));
 const assign=async(value:string)=>{setBusy(true);setError('');try{await api(`/instances/${instanceId}/runner`,'PUT',{runnerId:value||null});await onAssigned();}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 return <section className="member runner-assignment" aria-label="서버 실행 위치"><div className="actions"><h3>실행 컴퓨터</h3><button onClick={onRegister}>실행기 등록·관리</button></div><p>{disabled?'서버를 완전히 종료한 뒤 실행 위치를 변경할 수 있습니다.':'실행할 컴퓨터를 선택한 뒤 서버를 시작하세요.'}</p>
 <label className="security-label">실행 위치<select aria-label="실행 위치" value={runnerId||''} disabled={disabled||busy} onChange={e=>assign(e.target.value)}><option value="" disabled={requireRunner}>이 관리 Mac{requireRunner?' · 공유 포트 사용 불가':''}</option>{choices.map(r=><option key={r.id} value={r.id}>{r.name} · {r.platform} · {r.online?'온라인':'오프라인'}</option>)}{runnerId&&!choices.some(r=>r.id===runnerId)&&<option value={runnerId}>등록되지 않았거나 접근할 수 없는 실행기</option>}</select></label>
 {!choices.length&&<p className="notice">이 서버에 허용된 원격 실행기가 없습니다. ‘실행기 등록·관리’에서 실행기를 설치·등록하고 이 서버의 실행을 허용하세요. {requireRunner?'공유 포트를 이용하려면 별도 실행기를 등록·선택해야 합니다.':runnerId?'현재 배정된 실행기의 등록 상태와 서버 사용 허용을 확인하세요.':'현재는 관리 Mac에서 실행됩니다.'}</p>}
 {selected&&<p className="muted">현재 실행기: {selected.name} · {selected.online?'연결됨':'오프라인'}. 게임 포트는 관리 Mac에서 공개됩니다. 외부 접속은 이 Mac의 게임 포트를 포워딩하세요.</p>}
 {error&&<p role="alert" className="error-banner">{error}</p>}</section>;
});
