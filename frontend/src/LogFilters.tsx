import {memo,useEffect,useRef,useState} from 'react';
import {api} from './http';
import type {ParsedLog} from './logParser';
export type FilterRules={keywords:string[];mods:string[];onlyDebug:boolean};
type Preset={id:string;name:string;rules:FilterRules};
export type FilterConfig={enabled:boolean;rules:FilterRules;presets:Preset[];activeId:string|null};
type View={config:FilterConfig;revision:number};
export const defaultRules:FilterRules={keywords:[],mods:[],onlyDebug:true};
const defaults=():View=>({config:{enabled:true,rules:defaultRules,presets:[],activeId:null},revision:0});
export function useLogFilters(id:string){
 const [view,setView]=useState<View>(defaults),[ready,setReady]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');const generation=useRef(0),lock=useRef<number|null>(null);
 useEffect(()=>{const epoch=++generation.current;setView(defaults());setReady(false);setBusy(false);setError('');api<View>('/log-filters/'+id).then(v=>{if(epoch===generation.current){setView(v?.config&&Array.isArray(v.config.presets)?v:defaults());setReady(true);}}).catch(e=>{if(epoch===generation.current){setError(e.message);setReady(true);}});return()=>{generation.current++;};},[id]);
 const change=(config:FilterConfig)=>setView(v=>({...v,config}));
 async function save(config:FilterConfig){const epoch=generation.current;if(lock.current===epoch)return false;lock.current=epoch;setBusy(true);setError('');try{const next=await api<View>('/log-filters/'+id,'PUT',{config,revision:view.revision});if(epoch===generation.current){setView(next);return true;}return false;}catch(e){if(epoch===generation.current)setError((e as Error).message);return false;}finally{if(lock.current===epoch)lock.current=null;if(epoch===generation.current)setBusy(false);}}
 return {config:view.config,ready,busy,error,change,save};
}
export function hiddenByFilter(event:ParsedLog,config:FilterConfig):boolean {
 if(!config.enabled)return false;const {keywords,mods,onlyDebug}=config.rules;
 if(onlyDebug&&!['DEBUG','TRACE'].includes(event.level||''))return false;
 if(keywords.some(k=>event.search.includes(k.toLowerCase())))return true;
 const logger=event.logger?.toLowerCase();if(!logger)return false;
 const tokens=logger.split(/[./:]/);
 return mods.some(m=>{const value=m.toLowerCase();return logger===value||['.','/',':'].some(s=>logger.startsWith(value+s))||tokens.includes(value);});
}
const split=(value:string)=>[...new Set(value.split(/[,\n]/).map(s=>s.trim()).filter(Boolean))];
export const LogFilterEditor=memo(function LogFilterEditor({config,ready,busy,error,onChange,onSave}:{config:FilterConfig;ready:boolean;busy:boolean;error:string;onChange:(value:FilterConfig)=>void;onSave:(value:FilterConfig)=>Promise<boolean>}){
 const [keywords,setKeywords]=useState(''),[mods,setMods]=useState(''),[onlyDebug,setOnlyDebug]=useState(true),[name,setName]=useState(''),[notice,setNotice]=useState('');
 useEffect(()=>{setKeywords(config.rules.keywords.join('\n'));setMods(config.rules.mods.join('\n'));setOnlyDebug(config.rules.onlyDebug);},[config.rules]);
 const draft=():FilterConfig=>{const rules={keywords:split(keywords),mods:split(mods),onlyDebug};return {...config,activeId:JSON.stringify(rules)===JSON.stringify(config.rules)?config.activeId:null,rules};};
 const valid=split(keywords).length<=40&&split(mods).length<=40&&[...split(keywords),...split(mods)].every(s=>s.length<=120);
 return <details className="log-filter-settings"><summary>로그 표시 필터{config.enabled&&(config.rules.keywords.length+config.rules.mods.length)>0?` · ${config.rules.keywords.length+config.rules.mods.length}개 적용 중`:''}</summary><fieldset disabled={!ready||busy}><div className="actions"><label className="check-label"><input type="checkbox" checked={config.enabled} onChange={e=>onChange({...config,enabled:e.target.checked})}/>표시 필터 사용</label><label>저장된 로그 필터<select aria-label="저장된 로그 필터" value={config.activeId||''} onChange={e=>{const p=config.presets.find(p=>p.id===e.target.value);if(p){onChange({...config,rules:p.rules,activeId:p.id});setName(p.name);setNotice('');}else onChange({...config,activeId:null});}}><option value="">직접 설정</option>{config.presets.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label>{config.activeId&&<button type="button" onClick={async()=>{const next={...config,presets:config.presets.filter(p=>p.id!==config.activeId),activeId:null};if(await onSave(next))setNotice('저장된 필터를 삭제했습니다.');}}>선택한 필터 삭제</button>}</div><div className="filter-fields"><label>제외할 키워드<textarea aria-label="제외할 키워드" value={keywords} maxLength={4840} placeholder="반복 메시지의 일부 · 한 줄 또는 쉼표로 구분" onChange={e=>setKeywords(e.target.value)}/></label><label>제외할 모드·로거<textarea aria-label="제외할 모드·로거" value={mods} maxLength={4840} placeholder="create 또는 com.simibubi.create" onChange={e=>setMods(e.target.value)}/></label></div><label className="check-label"><input type="checkbox" checked={onlyDebug} onChange={e=>setOnlyDebug(e.target.checked)}/>DEBUG·TRACE 메시지에만 적용</label><p className="muted">대소문자를 구분하지 않습니다. 모드 필터는 로그의 모드·로거 태그를 사용합니다. 태그가 없는 메시지는 키워드로 제외하세요. 원본 로그와 오류 카운트는 유지됩니다.</p><div className="actions"><button type="button" disabled={!valid} onClick={()=>{onChange(draft());setNotice('화면에 적용했습니다. 유지하려면 설정을 저장하세요.');}}>필터 적용</button><button type="button" disabled={!valid} onClick={async()=>{if(await onSave(draft()))setNotice('로그 필터 설정을 저장했습니다.');}}>필터 설정 저장</button><label>필터 이름<input aria-label="필터 이름" value={name} maxLength={60} placeholder="예: Create 디버그 숨기기" onChange={e=>setName(e.target.value)}/></label><button type="button" disabled={!valid||!name.trim()||config.presets.length>=20} onClick={async()=>{const rules=draft().rules;const old=config.presets.find(p=>p.name===name.trim());const p={id:old?.id||crypto.randomUUID(),name:name.trim(),rules};if(await onSave({...config,rules,presets:[...config.presets.filter(x=>x.id!==p.id),p],activeId:p.id}))setNotice('이름을 붙여 필터를 저장했습니다.');}}>이름으로 필터 저장</button></div></fieldset>{error&&<p role="alert" className="error-banner">{error}</p>}{notice&&<p role="status">{notice}</p>}</details>;
});
