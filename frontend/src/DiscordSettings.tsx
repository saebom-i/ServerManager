import {memo,useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
type Grant={guildId:string;roleId:string;instanceId:string;canStart:boolean};
type Route={guildId:string;channelId:string;instanceId:string;errors:boolean;stops:boolean};
type Config={enabled:boolean;grants:Grant[];alerts:Route[]};
type View={config:Config;revision:number;tokenConfigured:boolean};
type Status={state:string;message:string;inviteUrl:string|null;guilds:{id:string;name:string}[]};
type Props={instances:{config:{id:string;name:string}}[]};
export const DiscordSettings=memo(function DiscordSettings({instances}:Props){
 const auth=useAuth();
 const [view,setView]=useState<View|null>(null),[config,setConfig]=useState<Config>({enabled:false,grants:[],alerts:[]});
 const [status,setStatus]=useState<Status|null>(null),[token,setToken]=useState(''),[clearToken,setClearToken]=useState(false);
 const [busy,setBusy]=useState(false),[dirty,setDirty]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function load(){const value=await api<View>('/admin/discord');setView(value);setConfig(value.config);setToken('');setClearToken(false);setDirty(false);}
 useEffect(()=>{let stopped=false;let timer:ReturnType<typeof setTimeout>;load().catch(e=>setError(e.message));const poll=async()=>{try{if(!document.hidden){const value=await api<Status>('/admin/discord/status');if(!stopped)setStatus(value);}}catch{}finally{if(!stopped)timer=setTimeout(poll,5000);}};poll();return()=>{stopped=true;clearTimeout(timer);};},[]);
 function change(value:Config){setConfig(value);setDirty(true);setNotice('');}
 async function run(fn:()=>Promise<void>){setBusy(true);setError('');setNotice('');try{try{await fn();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await fn();}}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 const serverSelect=(id:string,onChange:(id:string)=>void,label:string)=><label>{label}<select value={id} onChange={e=>onChange(e.target.value)} required><option value="">서버 선택</option>{id&&!instances.some(i=>i.config.id===id)&&<option value={id}>삭제된 서버 · 규칙을 삭제하세요</option>}{instances.map(({config:i})=><option key={i.id} value={i.id}>{i.name}</option>)}</select></label>;
 return <section className="discord-settings" aria-labelledby="discord-heading"><h2 id="discord-heading">Discord 봇 · BETA</h2><p>친구들은 <code>/mc start</code>로 서버를 켜고 <code>/mc status</code>로 상태를 확인할 수 있습니다. 역할에 허용된 서버만 자동 완성에 나타납니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}{notice&&<div role="status" className="notice alert">{notice}</div>}
 <div className="discord-status"><strong>{status?.state||'확인 중'}</strong><span>{status?.message}</span></div>
 {status?.guilds.length!==0&&<p className="muted">연결된 Discord 서버: {status?.guilds.map(g=>`${g.name} (${g.id})`).join(', ')}</p>}
 <form onSubmit={e=>{e.preventDefault();if(!view)return;run(async()=>{const saved=await api<View>('/admin/discord','PUT',{config,revision:view.revision,token:token.trim()||null,clearToken});setView(saved);setConfig(saved.config);setToken('');setClearToken(false);setDirty(false);setNotice('Discord 설정을 저장했습니다. 연결 상태는 잠시 후 갱신됩니다.');setStatus(await api('/admin/discord/status'));});}}>
 <fieldset disabled={busy||!view} className="discord-fieldset">
 <label className="check-label"><input type="checkbox" checked={config.enabled} onChange={e=>change({...config,enabled:e.target.checked})}/>Discord 연동 켜기</label>
 <label className="security-label">봇 토큰<input type="password" autoComplete="new-password" spellCheck={false} maxLength={512} value={token} placeholder={view?.tokenConfigured&&!clearToken?'저장된 토큰 있음 · 비워두면 유지':'Discord Developer Portal의 Bot 토큰'} onChange={e=>{setToken(e.target.value);setClearToken(false);setDirty(true);}}/></label>
 <div className="actions"><button type="button" disabled={!view?.tokenConfigured||clearToken} onClick={()=>{setToken('');setClearToken(true);change({...config,enabled:false});}}>저장된 토큰 삭제 예약</button>{clearToken&&<span>저장하면 토큰이 삭제되고 연결이 종료됩니다.</span>}</div>
 <h3>역할별 서버 권한</h3><p className="muted">규칙이 없으면 접근할 수 없습니다. 조회 권한은 기본이며 시작 권한은 별도로 선택합니다. @everyone은 지원하지 않습니다.</p>
 {config.grants.map((g,index)=><div className="discord-rule" key={index}>
 <label>Discord 서버 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={g.guildId} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,guildId:e.target.value.trim()}:x)})}/></label>
 <label>역할 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={g.roleId} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,roleId:e.target.value.trim()}:x)})}/></label>
 {serverSelect(g.instanceId,id=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,instanceId:id}:x)}),'관리할 Minecraft 서버')}
 <label className="check-label"><input type="checkbox" checked={g.canStart} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,canStart:e.target.checked}:x)})}/>서버 시작 허용</label>
 <button type="button" onClick={()=>change({...config,grants:config.grants.filter((_,n)=>n!==index)})}>역할 규칙 삭제</button></div>)}
 <button type="button" onClick={()=>change({...config,grants:[...config.grants,{guildId:status?.guilds[0]?.id||'',roleId:'',instanceId:instances[0]?.config.id||'',canStart:false}]})}>역할 규칙 추가</button>
 <h3>종료·오류 알림</h3><p className="muted">이 채널을 볼 수 있는 사람 모두가 알림을 읽을 수 있습니다. 로그 원문은 전송하지 않으며 반복 오류는 1분 단위로 묶습니다.</p>
 {config.alerts.map((a,index)=><div className="discord-rule" key={index}>
 <label>알림 Discord 서버 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={a.guildId} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,guildId:e.target.value.trim()}:x)})}/></label>
 <label>텍스트 채널 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={a.channelId} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,channelId:e.target.value.trim()}:x)})}/></label>
 {serverSelect(a.instanceId,id=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,instanceId:id}:x)}),'알림 대상 Minecraft 서버')}
 <label className="check-label"><input type="checkbox" checked={a.errors} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,errors:e.target.checked}:x)})}/>오류 알림</label>
 <label className="check-label"><input type="checkbox" checked={a.stops} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,stops:e.target.checked}:x)})}/>종료 알림</label>
 <button type="button" onClick={()=>change({...config,alerts:config.alerts.filter((_,n)=>n!==index)})}>알림 규칙 삭제</button></div>)}
 <button type="button" onClick={()=>change({...config,alerts:[...config.alerts,{guildId:status?.guilds[0]?.id||'',channelId:'',instanceId:instances[0]?.config.id||'',errors:true,stops:true}]})}>알림 규칙 추가</button>
 <div className="actions spaced"><button className="primary" type="submit" disabled={!dirty}>Discord 설정 저장</button><button type="button" onClick={()=>run(load)}>저장된 설정 다시 불러오기</button><button type="button" disabled={dirty||!view?.config.enabled} onClick={()=>run(async()=>{await api('/admin/discord/reconnect','POST');setNotice('다시 연결을 요청했습니다.');})}>다시 연결</button></div>
 </fieldset></form>
 {status?.inviteUrl&&<p><a href={status.inviteUrl} target="_blank" rel="noreferrer">Discord 서버에 봇 초대하기</a></p>}
 <details><summary>처음 연결하는 방법</summary><ol><li><a href="https://discord.com/developers/applications" target="_blank" rel="noreferrer">Discord Developer Portal</a>에서 앱과 봇을 만들고 Bot 토큰을 이 화면에 저장하세요.</li><li>연동을 켠 뒤 위의 초대 링크로 봇을 초대하세요. 채널 보기와 메시지 보내기 권한이 필요합니다.</li><li>Discord 설정 → 고급 → 개발자 모드를 켜고 서버·역할·텍스트 채널 ID를 복사해 규칙에 넣으세요.</li><li>Interactions Endpoint URL은 비워 두세요. 추가 포트 개방과 Message Content / Server Members 특권 인텐트는 필요하지 않습니다.</li><li>관리 앱과 Mac이 켜져 있어야 명령과 알림이 작동합니다. Mac 자체를 켜는 기능은 아닙니다.</li></ol></details></section>;
},(a,b)=>JSON.stringify(a.instances.map(i=>i.config.id+':'+i.config.name))===JSON.stringify(b.instances.map(i=>i.config.id+':'+i.config.name)));
