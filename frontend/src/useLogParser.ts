import {useCallback,useEffect,useRef,useState} from 'react';
import type {ParsedLog,RawLog} from './logParser';
export function useLogParser(){
 const worker=useRef<Worker|null>(null),counter=useRef(0),pending=useRef(new Map<number,{resolve:(events:ParsedLog[])=>void;reject:(error:Error)=>void}>());
 const [progress,setProgress]=useState('');
 useEffect(()=>{
  const w=new Worker(new URL('./logParser.worker.ts',import.meta.url),{type:'module'});worker.current=w;
  w.onmessage=({data})=>{const job=pending.current.get(data.request);if(!job)return;if(data.progress){setProgress(`로그 파싱 중… ${data.progress.toLocaleString()} / ${data.total.toLocaleString()}줄`);return;}pending.current.delete(data.request);setProgress('');if(data.error)job.reject(new Error(data.error));else job.resolve(data.events);};
  w.onerror=()=>{w.terminate();worker.current=null;for(const job of pending.current.values())job.reject(new Error('로그 파서를 실행하지 못했습니다. 다시 불러오세요.'));pending.current.clear();setProgress('');};
  return()=>{w.terminate();worker.current=null;for(const job of pending.current.values())job.reject(new Error('로그 조회 취소'));pending.current.clear();};
 },[]);
 const parse=useCallback((events:RawLog[])=>new Promise<ParsedLog[]>((resolve,reject)=>{
  if(!events.length){resolve([]);return;}
  if(!worker.current){reject(new Error('로그 파서가 준비되지 않았습니다'));return;}
  const request=++counter.current;pending.current.set(request,{resolve,reject});worker.current.postMessage({request,events});
 }),[]);
 return {parse,progress};
}
