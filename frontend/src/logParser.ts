export type RawLog={seq:number;time:string|null;kind:string;text:string};
export type Mixin={modId:string;config:string;className:string;phase:string};
export type Frame={className:string;method:string;source:string;module:string;mixins:Mixin[];metadata:string[];origin:string};
export type ParsedLog=RawLog&{display:string;search:string;frame?:Frame};
export function trimLogTime(text:string){return text.replace(/^\[(?:\d{4}-\d{2}-\d{2}[ T])?\d{2}:\d{2}:\d{2}(?:[.,]\d+)?\]\s*/, '');}
export function parseLog(event:RawLog):ParsedLog {
 const clean=event.text.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g,''),display=trimLogTime(clean);
 const result:ParsedLog={...event,display,search:clean.toLowerCase()};
 const match=/^\s*at\s+([^\s(]+)\(([^)]*)\)(.*)$/.exec(display);
 if(!match)return result;
 const slash=match[1].lastIndexOf('/'),call=match[1].slice(slash+1),dot=call.lastIndexOf('.');
 if(dot<1)return result;
 const suffix=match[3],metadataMatch=/\{(.*)\}\s*$/.exec(suffix),mixins:Mixin[]=[],metadata:string[]=[];
 for(const entry of (metadataMatch?.[1]||'').split(',').map(s=>s.trim()).filter(Boolean)){
  const m=/^pl:mixin:([^:]+):([^:]+):(.+?) from mod (\S+)$/.exec(entry);
  if(m)mixins.push({phase:m[1],config:m[2],className:m[3],modId:m[4]});else metadata.push(entry);
 }
 const frame:Frame={className:call.slice(0,dot),method:call.slice(dot+1),module:slash<0?'':match[1].slice(0,slash),source:match[2],mixins,metadata,origin:(metadataMatch?suffix.slice(0,metadataMatch.index):suffix).trim()};
 result.frame=frame;result.display=`${frame.className.split('.').pop()}.${frame.method}() · ${frame.source}`;
 return result;
}
