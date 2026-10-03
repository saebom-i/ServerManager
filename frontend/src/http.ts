let csrf='';
export class ApiError extends Error {constructor(message:string,public status:number){super(message);}}
export function setCsrf(value:string){csrf=value;}
export async function api<T=unknown>(url:string,method='GET',body?:unknown):Promise<T>{
 const headers:Record<string,string>=body instanceof FormData?{}:{'Content-Type':'application/json'};
 if(!['GET','HEAD','OPTIONS'].includes(method))headers['X-CSRF-TOKEN']=csrf;
 const res=await fetch('/api'+url,{method,headers,credentials:'same-origin',body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});
 if(!res.ok){if(res.status===401&&!url.startsWith('/auth/'))window.dispatchEvent(new Event('sm:unauthorized'));const error=await res.json().catch(()=>({message:res.statusText}));throw new ApiError(error.message||'요청 실패',res.status);}
 const text=await res.text();return text?JSON.parse(text):undefined as T;
}
