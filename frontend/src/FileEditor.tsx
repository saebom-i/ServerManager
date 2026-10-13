import {memo,useState} from 'react';
export type EditableFile={path:string;content:string;revision?:string};
// Keep keystrokes local: typing must not reconcile the directory or dashboard.
export const FileEditor=memo(function FileEditor({file,disabled,busy,onClose,onSave}:{file:EditableFile;disabled:boolean;busy:boolean;onClose:()=>void;onSave:(file:EditableFile)=>void}){
 const [content,setContent]=useState(file.content);
 return <div className="editor"><div><strong>{file.path}</strong><button onClick={onClose}>닫기</button></div><textarea aria-label="파일 내용" spellCheck={false} disabled={disabled} value={content} onChange={e=>setContent(e.target.value)}/><footer><span>저장 전 원본을 자동 백업합니다.</span><button className="primary" disabled={busy||disabled} onClick={()=>onSave({...file,content})}>저장</button></footer></div>;
});
