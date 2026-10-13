import {parseLog,type RawLog} from './logParser';
self.onmessage=({data}:{data:{request:number;events:RawLog[]}})=>{
 try {
  const parsed=[];
  for(let i=0;i<data.events.length;i++){parsed.push(parseLog(data.events[i]));if(i%500===499)self.postMessage({request:data.request,progress:i+1,total:data.events.length});}
  self.postMessage({request:data.request,events:parsed});
 }catch(error){self.postMessage({request:data.request,error:String(error)});}
};
