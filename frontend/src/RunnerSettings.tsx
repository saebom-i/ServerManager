import {memo,useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';

type Runner={id:string;name:string;platform:string;registeredBy:string;createdAt:string;version:string;enabled:boolean;allowedInstanceIds:string[];online:boolean;lastSeen:string|null};
type Instance={config:{id:string;name:string}};

export const RunnerSettings=memo(function RunnerSettings({instances}:{instances:Instance[]}){
 const auth=useAuth();
 const version=auth.session.appVersion;
 const release='https://github.com/yoonseo6399/ServerManager/releases';
 const validVersion=/^\d+\.\d+\.\d+(?:-beta\.\d+)?$/.test(version);
 const download=(platform:string,ui=false)=>validVersion?`${release}/download/v${version}/ServerManager-Runner-${ui?'UI-':''}${version}-${platform}.zip`:release;
 const [rows,setRows]=useState<Runner[]>([]),[name,setName]=useState(''),[code,setCode]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const load=async()=>{const list=await api<Runner[]>('/runners');setRows(Array.isArray(list)?list:[]);};
 useEffect(()=>{let live=true;let timer:ReturnType<typeof setTimeout>;const poll=async()=>{try{if(!document.hidden){const list=await api<Runner[]>('/runners');if(live)setRows(Array.isArray(list)?list:[]);}}catch(e){if(live)setError('실행기 목록을 불러오지 못했습니다. '+(e as Error).message);}finally{if(live)timer=setTimeout(poll,10000);}};poll();return()=>{live=false;clearTimeout(timer);};},[]);
 async function run(action:()=>Promise<void>,message:string){setBusy(true);setError('');setNotice('');try{try{await action();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await action();}await load();setNotice(message);}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <section className="settings-panel runner-settings" aria-labelledby="runner-heading"><h2 id="runner-heading">서버 실행기</h2><p>Mac·Windows 컴퓨터를 등록해 두면 켜져 있는 실행기에 서버를 배정할 수 있습니다. 초대 사용자도 자기 컴퓨터를 등록할 수 있습니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}{notice&&<div role="status" className="notice alert">{notice}</div>}
 <h3>1. 실행할 컴퓨터에 설치</h3><p><strong>UI 설치판 · 친구에게 추천</strong> — 창에서 관리 주소와 등록 코드를 입력하고 설치합니다. Java가 포함되어 있고 로그인 자동 대기를 선택할 수 있습니다.</p><div className="actions"><a className="button-link" href={download('mac-universal',true)}>Mac UI 설치판</a><a className="button-link" href={download('windows-x64',true)}>Windows UI 설치판</a></div><p className="muted">ZIP 전체를 풀고 Mac은 Server Manager Runner Installer.app, Windows는 Setup.cmd를 여세요. 웹에서 패스키로 발급한 코드를 설치 창에 붙여넣으세요.</p>
 <details><summary>CLI 설치판 · 터미널 사용자</summary><div className="actions"><a className="button-link" href={download('mac-universal')}>Mac CLI 설치판</a><a className="button-link" href={download('windows-x64')}>Windows CLI 설치판</a></div><p className="muted">압축을 풀고 터미널에서 Mac은 ./install.command, Windows는 install.cmd를 실행하세요. --help로 옵션을 확인할 수 있습니다. --no-autostart는 로그인 자동 대기를 끕니다. 두 설치판은 같은 실행기와 등록 정보를 사용합니다.</p></details>
 <h3>2. 패스키로 등록 코드 발급</h3><label className="security-label">실행기에 입력할 관리 웹 주소<input readOnly value={auth.session.publicOrigin||window.location.origin} onFocus={e=>e.target.select()}/></label><p className="muted">아래 코드를 발급한 뒤 실행기에 관리 웹 주소와 코드를 입력하세요. 패스키 확인이 필요한 경우 인증창이 열립니다.</p>
 <label className="security-label">새 실행기 이름<input value={name} maxLength={80} onChange={e=>setName(e.target.value)}/></label>
 <button disabled={busy||!name.trim()} onClick={()=>run(async()=>{const result=await api<{code:string}>('/runners/enroll','POST',{name:name.trim()});setCode(result.code);},'5분 동안 유효한 등록 코드를 발급했습니다.')}>패스키로 실행기 등록 코드 발급</button>
 {code&&<label className="security-label">한 번만 표시되는 등록 코드<input readOnly value={code} onFocus={e=>e.target.select()}/></label>}
 <h3>3. 등록된 실행기에 서버 허용</h3><p>기기 등록자가 서버별 사용을 허용하면 해당 서버의 관리자와 소유자가 서버 화면에서 실행 위치를 선택할 수 있습니다.</p>
 <details><summary>기존 마크 서버 폴더 연결하기</summary><p>웹에서 기존 파일과 같은 종류·Minecraft·로더 버전으로 서버를 생성하세요. 런처 설치는 생략하고 아래 서버 ID를 실행기 설치 창의 ‘기존 마크 서버 폴더’에 입력한 뒤 실제 폴더를 선택하세요. 폴더는 원래 위치에서 실행됩니다. 등록 후 이 서버의 사용을 허용하고 실행 위치를 배정하면 시작할 수 있습니다.</p>{instances.map(({config:i})=><label className="security-label" key={i.id}>{i.name} · 서버 ID<input readOnly value={i.id} onFocus={e=>e.target.select()}/></label>)}<p className="muted">첫 실행은 선택한 폴더를 원본으로 사용합니다. 이후 웹 파일 관리와 실행기 간 동기화가 적용되며 외부에서 폴더를 수정하면 덮어쓰기 대신 재등록을 안내합니다. 기존 서버를 별도 프로그램으로 동시에 실행하지 마세요.</p></details>
 {!rows.length&&<p className="notice">아직 등록된 실행기가 없습니다. 위 단계로 컴퓨터를 등록하면 연결 상태가 여기에 표시됩니다.</p>}
 {rows.map(row=><div className="member" key={row.id}><strong>{row.name} · {row.platform} · {row.online?'온라인':row.enabled?'오프라인':'폐기됨'}</strong><p className="muted">버전 {row.version} · 마지막 연결 {row.lastSeen?new Date(row.lastSeen).toLocaleString():'없음'}</p>
 {row.registeredBy===auth.session.user?.id&&<><p>이 컴퓨터에서 실행해도 되는 서버를 한 번씩 허용하세요.</p><div className="permission-grid">{instances.map(({config:i})=><label key={i.id} className="check-label"><input type="checkbox" disabled={busy||!row.enabled} checked={row.allowedInstanceIds.includes(i.id)} onChange={e=>{const allowed=e.target.checked;run(()=>api(`/runners/${row.id}/allow/${i.id}`,allowed?'PUT':'DELETE'),allowed?'이 서버의 실행을 허용했습니다.':'이 서버의 새 배정을 막았습니다.');}}/>{i.name}</label>)}</div></>}
 {(row.registeredBy===auth.session.user?.id||auth.session.user?.owner)&&<button disabled={busy||!row.enabled} onClick={()=>{if(confirm(`${row.name} 실행기 등록을 폐기할까요?`))run(()=>api(`/runners/${row.id}`,'DELETE'),'실행기를 폐기했습니다.');}}>기기 등록 폐기</button>}
 </div>)}
 </section>;
});
