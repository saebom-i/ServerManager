import {test,expect} from '@playwright/test';
test('typing stays responsive with a large file list and audit history',async({page,context})=>{
 let savedContent='editable text',revision=0;
 const config={id:'test',name:'Performance server',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:2048,javaPath:'/java'};
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  let result:any={};
  if(path==='/api/auth/session')result={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config,state:'STOPPED',pid:null,directory:'/test',resources:null}];
  else if(path==='/api/memory')result={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,committedHeapMb:0,startableHeapMb:14400};
  else if(path==='/api/health')result={javaPath:'/java'};
  else if(path==='/api/version-catalog')result={versions:['21.1.252']};
  else if(path.endsWith('/files'))result=Array.from({length:1500},(_,i)=>({name:`file-${i}.txt`,path:`file-${i}.txt`,directory:false,size:100}));
  else if(path.endsWith('/text')){if(route.request().method()==='PUT'){savedContent=route.request().postDataJSON().content;revision++;}result={content:savedContent,revision:String(revision)};}
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  else if(path==='/api/admin/discord')result={config:{enabled:false,grants:[],alerts:[]},revision:0,tokenConfigured:false};
  else if(path==='/api/admin/discord/status')result={state:'OFF',message:'꺼짐',inviteUrl:null,guilds:[]};
  else if(path==='/api/runners')result=[];
  else if(path==='/api/admin/webdav')result={config:{enabled:false,url:'',username:''},revision:0,passwordConfigured:false};
  else if(path==='/api/admin/audit')result=Array.from({length:300},(_,i)=>({at:'2026-10-12T00:00:00Z',action:'test '+i,status:200}));
  else if(path==='/api/auth/credentials'||path==='/api/admin/users')result=[];
  await route.fulfill({json:result});
 });
 const cdp=await context.newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
 await page.goto('/');await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await page.getByRole('button',{name:'file-0.txt',exact:true}).click();
 async function measure(label:string){
  const input=page.getByRole('textbox',{name:label,exact:true});await expect(input).toBeEnabled();await input.focus();
  const samples=await input.evaluate(async element=>{
   const samples:number[]=[];const setter=Object.getOwnPropertyDescriptor(element instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value')!.set!;
   for(let i=0;i<15;i++){await new Promise(requestAnimationFrame);const start=performance.now();setter.call(element,(element as HTMLInputElement).value+'x');element.dispatchEvent(new Event('input',{bubbles:true}));await new Promise(requestAnimationFrame);samples.push(performance.now()-start);}return samples.sort((a,b)=>a-b);
  });
  console.log(label,JSON.stringify({median:samples[7],p95:samples[14]}));
  expect.soft(samples[7]).toBeLessThan(100);
 }
 await measure('파일 내용');
 await page.getByRole('button',{name:'저장',exact:true}).click();await expect.poll(()=>savedContent).toBe('editable text'+'x'.repeat(15));
 await page.getByRole('button',{name:'다음 파일',exact:true}).click();await expect(page.getByRole('button',{name:'file-100.txt',exact:true})).toBeVisible();
 expect(await page.locator('.file-row').count()).toBe(100);
 await page.getByRole('button',{name:'계정과 보안',exact:true}).click();await expect(page.locator('.audit-list>div')).toHaveCount(300);await measure('사용자 이름');
});

test('7000 log lines stay virtual and unchanged rows are reused on polling',async({page})=>{
 let append=false;
 await page.addInitScript(()=>{
  const w=window as any;w.timeFormats=0;
  const descriptor=Object.getOwnPropertyDescriptor(Intl.DateTimeFormat.prototype,'format')!;
  Object.defineProperty(Intl.DateTimeFormat.prototype,'format',{...descriptor,get(){const format=descriptor.get!.call(this);return (...args:any[])=>{w.timeFormats++;return format(...args);};}});
  const previous=Date.prototype.toLocaleTimeString;Date.prototype.toLocaleTimeString=function(...args){w.timeFormats++;return previous.apply(this,args);};
 });
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config:{id:'test',name:'Logs',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:2048,javaPath:'/java'},state:'RUNNING',pid:123,directory:'/test',resources:null}];
  else if(path==='/api/version-catalog')result={versions:['21.1.252']};
  else if(path.endsWith('/event-page')){const after=url.searchParams.get('after');result={hasOlder:false,events:!after?Array.from({length:7000},(_,i)=>({seq:i+1,time:'2026-10-12T00:00:00Z',kind:'info',text:`[00:00:00] Mixin test log ${i}`})):append&&after==='7000'?[{seq:7001,time:'2026-10-12T00:00:01Z',kind:'info',text:'new log'}]:[]};}
  await route.fulfill({json:result});
 });
 await page.goto('/');await expect(page.getByText('7,000줄 로드됨')).toBeVisible();
 await page.getByLabel('자동 스크롤',{exact:true}).uncheck();
 await page.getByTestId('log-viewport').evaluate(e=>{e.scrollTop=1000;});
 await expect(page.locator('.virtual-log').first()).toHaveAttribute('data-event-seq','28');
 expect(await page.locator('.virtual-log').count()).toBeLessThan(40);
 await expect(page.locator('.mixins pre')).toHaveCount(0);
 const before=await page.evaluate(()=>(window as any).timeFormats);append=true;
 await expect(page.getByText('7,001줄 로드됨')).toBeVisible();
 const after=await page.evaluate(()=>(window as any).timeFormats);
 expect(after-before).toBe(0);
 await page.getByRole('textbox',{name:'콘솔 명령',exact:true}).pressSequentially('say responsive');
 await expect(page.getByRole('textbox',{name:'콘솔 명령',exact:true})).toHaveValue('say responsive');
 expect(await page.evaluate(()=>(window as any).timeFormats)).toBe(after);
 await page.locator('.virtual-log').first().click();await expect(page.getByRole('heading',{name:'로그 전문'})).toBeVisible();
});
