import {memo,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';

export type FolderProgress={phase:string;files:number;bytes:number;totalFiles:number;totalBytes:number;message:string;direction?:string};
export type FolderSource={state:string;path:string};
const size=(bytes:number)=>bytes>=1024**3?(bytes/1024**3).toFixed(2)+' GiB':(bytes/1024**2).toFixed(1)+' MiB';
export const TransferStatus=memo(function TransferStatus({value}:{value:FolderProgress}){
 return <div className="file-note" role="status" aria-label="서버 파일 전송 상태"><strong>{value.direction==='TO_RUNNER'?'관리 서버 → 실행기':'실행기 → 관리 서버'} · {({SCANNING:'파일·해시 검사 중',UPLOADING:'변경 파일 전송 중',VERIFYING:'검증·반영 중',COMPLETE:'전송 완료',FAILED:'전송 실패'} as Record<string,string>)[value.phase]}</strong>
 {value.phase==='SCANNING'?<p>{value.files.toLocaleString()}개 검사 · {size(value.bytes)} 확인</p>:<p>{value.files.toLocaleString()} / {value.totalFiles.toLocaleString()}개 · {size(value.bytes)} / {size(value.totalBytes)}{value.totalBytes>0?' · '+Math.min(100,value.bytes/value.totalBytes*100).toFixed(1)+'%':''}</p>}
 {value.totalBytes>0&&value.phase==='UPLOADING'&&<progress aria-label="서버 파일 전송 진행률" max={value.totalBytes} value={value.bytes}/>}
 <p>{value.message}</p></div>;
});
export const FolderImportPanel=memo(function FolderImportPanel({id,source,progress,canManage,disabled,onRequested}:{id:string;source?:FolderSource|null;progress?:FolderProgress|null;canManage:boolean;disabled:boolean;onRequested:()=>Promise<void>}){
 const auth=useAuth();const[busy,setBusy]=useState(false),[error,setError]=useState('');
 const working=!!progress&&['SCANNING','UPLOADING','VERIFYING'].includes(progress.phase);
 const start=async()=>{
  if(!confirm('실행기에 등록한 기존 폴더를 가져올까요?\n서버를 실행하지 않고 파일을 검증·전송합니다.\n관리 웹에 있던 파일은 백업한 뒤 기존 폴더의 파일로 교체합니다.\n20GB 같은 대용량 폴더는 시간이 걸릴 수 있습니다.'))return;
  setBusy(true);setError('');
  try{try{await api(`/instances/${id}/import-folder`,'POST');}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await api(`/instances/${id}/import-folder`,'POST');}await onRequested();}
  catch(e){setError((e as Error).message);}finally{setBusy(false);}
 };
 return <div className="file-note" aria-label="기존 서버 폴더 가져오기">
  <strong>실행기의 기존 폴더</strong><p>{source?.path||'기존 폴더 등록만으로 파일이 자동 전송되지는 않습니다. 실행기에서 폴더를 등록하고 아래 가져오기를 실행하세요.'}</p>
  {source?.state==='SYNCED'&&!working&&<p>기존 폴더 연결 완료 · 파일은 정상 종료 시 동기화됩니다.</p>}
  {source?.state==='INVALID'&&<p role="alert">실행기에 등록한 폴더 경로를 확인하세요.</p>}
  {progress&&<div role="status" aria-live="polite"><strong>{({SCANNING:'파일·해시 검사 중',UPLOADING:'변경 파일 전송 중',VERIFYING:'관리 서버 검증·반영 중',COMPLETE:'가져오기 완료',FAILED:'가져오기 실패'} as Record<string,string>)[progress.phase]}</strong>
   {progress.phase==='SCANNING'?<p>{progress.files.toLocaleString()}개 검사 · {size(progress.bytes)} 확인</p>:<p>{progress.files.toLocaleString()} / {progress.totalFiles.toLocaleString()}개 · {size(progress.bytes)} / {size(progress.totalBytes)}</p>}
   {progress.totalBytes>0&&progress.phase==='UPLOADING'&&<progress aria-label="기존 폴더 전송 진행률" value={progress.bytes} max={progress.totalBytes}/>}
   <p>{progress.message}</p></div>}
  {canManage&&source?.state!=='SYNCED'&&<button disabled={busy||disabled||working||source?.state==='INVALID'} onClick={start}>{progress?.phase==='FAILED'?'기존 폴더 다시 가져오기':'실행기의 기존 폴더 가져오기'}</button>}
  {!source&&!working&&<p className="muted">서버 사용 허용과 실행 위치 배정이 필요합니다. 이 기능은 실행기 beta.8 이상을 사용합니다.</p>}
  {error&&<p role="alert">{error}</p>}
 </div>;
});
