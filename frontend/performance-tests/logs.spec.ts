import {test,expect} from '@playwright/test';
import {parseLog} from '../src/logParser';
const raw='\tat TRANSFORMER/minecraft@1.21.1/net.minecraft.client.gui.screens.Screen.wrapScreenError(Screen.java:451) ~[client.jar:1.21.1] {re:mixin,pl:accesstransformer:B,pl:mixin:APP:fabric-screen-api-v1.mixins.json:ScreenMixin from mod fabric_screen_api_v1,pl:mixin:APP:fabric-screen-api-v1.mixins.json:ScreenAccessor from mod fabric_screen_api_v1,pl:mixin:APP:balm.neoforge.mixins.json:ScreenAccessor from mod balm,pl:mixin:A}';
const event={seq:1,time:null,kind:'output',text:raw};
test('runtime parser preserves raw frames, groups identities and tolerates unknown data',()=>{
 const parsed=parseLog(event);expect(parsed.text).toBe(raw);
 expect(parsed.display).toBe('Screen.wrapScreenError() · Screen.java:451');
 expect(parsed.frame?.module).toBe('TRANSFORMER/minecraft@1.21.1');
 expect(parsed.frame?.mixins).toHaveLength(3);
 expect(parsed.frame?.mixins[1]).toEqual({phase:'APP',config:'fabric-screen-api-v1.mixins.json',className:'ScreenAccessor',modId:'fabric_screen_api_v1'});
 expect(parsed.frame?.metadata).toEqual(['re:mixin','pl:accesstransformer:B','pl:mixin:A']);
 expect(parseLog({...event,text:'at java.base/java.lang.Thread.run(Unknown Source)'}).frame?.source).toBe('Unknown Source');
 expect(parseLog({...event,text:'Caused by: java.lang.Exception: test'}).frame).toBeUndefined();
 expect(parseLog({...event,text:'at malformed'}).display).toBe('at malformed');
 expect(parseLog({...event,text:'\u001b[31m[12:00:00] '+raw+'\u001b[0m'}).frame?.method).toBe('wrapScreenError');
});

test('file selection loads raw pages in worker with loading state and frame-specific mixins',async({page})=>{
 const offsets:string[]=[];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config:{id:'test',name:'Logs',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:2048,javaPath:'/java'},state:'STOPPED',pid:null,directory:'/test',resources:null}];
  else if(path==='/api/version-catalog')result={versions:['21.1.252']};
  else if(path.endsWith('/log-files'))result=[{path:'server/logs/old.log.gz',name:'server/logs/old.log.gz',size:8000,compressed:true}];
  else if(path.endsWith('/event-page'))result={hasOlder:false,events:[{...event,text:'Live console',time:'2026-10-13T00:00:00Z'}]};
  else if(path.endsWith('/log-file-page')){
   await new Promise(resolve=>setTimeout(resolve,400));
   expect(url.searchParams.get('path')).toBe('server/logs/old.log.gz');
   const offset=url.searchParams.get('offset')!;offsets.push(offset);
   if(offset==='0')result={events:[event,{...event,seq:2,text:'Caused by: test failure',kind:'error'}],nextOffset:1000,revision:'snapshot'};
   else {expect(url.searchParams.get('revision')).toBe('snapshot');result={events:[{...event,seq:1001,text:'Last line'}],nextOffset:null,revision:'snapshot'};}
  }
  await route.fulfill({json:result});
 });
 await page.goto('/');await expect(page.getByText('Live console',{exact:true})).toBeVisible();
 await page.getByLabel('로그 파일',{exact:true}).selectOption('server/logs/old.log.gz');
 await expect(page.locator('.log-loading')).toHaveCount(0);
 await expect(page.getByText('Screen.wrapScreenError() · Screen.java:451',{exact:true})).toBeVisible();
 await expect(page.locator('.log-loading')).toHaveCount(0);
 await expect(page.getByRole('textbox',{name:'콘솔 명령',exact:true})).toHaveCount(0);
 await page.getByText('Screen.wrapScreenError() · Screen.java:451',{exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'로그 상세'});
 await expect(dialog.getByRole('heading',{name:'Screen.wrapScreenError()',exact:true})).toBeVisible();
 await expect(dialog.locator('.mixin-groups>section')).toHaveCount(2);
 await expect(dialog.getByText('fabric_screen_api_v1',{exact:true})).toBeVisible();
 await dialog.getByText('원문 보기',{exact:true}).click();await expect(dialog.locator('pre').last()).toHaveText(raw);
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();
 await page.getByRole('textbox',{name:'로그 검색'}).fill('balm');await expect(page.locator('.virtual-log')).toHaveCount(1);
 await page.getByRole('textbox',{name:'로그 검색'}).fill('');
 await page.getByRole('button',{name:'다음 로그 300줄'}).click();await expect(page.getByText('Last line',{exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'다음 로그 300줄'})).toBeDisabled();
 await page.getByRole('button',{name:'이전 페이지',exact:true}).click();await expect(page.getByText('Screen.wrapScreenError() · Screen.java:451',{exact:true})).toBeVisible();
 expect(offsets).toEqual(['0','1000','0']);
 await page.getByRole('button',{name:'로그 다시 불러오기'}).click();
 await page.getByLabel('로그 파일',{exact:true}).selectOption('');
 await expect(page.getByText('Live console',{exact:true})).toBeVisible();
 await expect(page.getByRole('textbox',{name:'콘솔 명령',exact:true})).toBeDisabled();
});

test('empty polls skip parsing and only slow parsing shows a layout-stable overlay',async({page})=>{
 let polls=0,next=300;
 await page.addInitScript(()=>{
  const w=window as any;w.parseCalls=0;w.parseDelay=0;w.parseStarted=0;w.loadingTimes=[];
  const NativeWorker=window.Worker;
  window.Worker=class extends NativeWorker {
   postMessage(message:any,options?:any){w.parseCalls++;w.parseStarted=performance.now();setTimeout(()=>super.postMessage(message,options),w.parseDelay);}
  };
  new MutationObserver(records=>{for(const record of records)for(const node of record.addedNodes)if(node instanceof HTMLElement&&node.matches('.log-loading'))w.loadingTimes.push(performance.now());}).observe(document,{childList:true,subtree:true});
 });
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config:{id:'test',name:'Logs',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:2048,javaPath:'/java'},state:'STOPPED',pid:null,directory:'/test',resources:null}];
  else if(path==='/api/version-catalog')result={versions:['21.1.252']};
  else if(path.endsWith('/event-page')){
   polls++;const after=Number(url.searchParams.get('after')||0);
   const events=after===0?Array.from({length:300},(_,i)=>({...event,seq:i+1,text:'log '+i})):after<next?[{...event,seq:next,text:'new log '+next}]:[];
   if(!events.length)await new Promise(resolve=>setTimeout(resolve,650));
   result={hasOlder:false,events};
  }
  await route.fulfill({json:result});
 });
 await page.goto('/');await expect(page.getByText('300줄 로드됨')).toBeVisible();
 await expect.poll(()=>polls).toBeGreaterThanOrEqual(3);
 expect(await page.evaluate(()=>(window as any).parseCalls)).toBe(1);
 expect(await page.evaluate(()=>(window as any).loadingTimes)).toEqual([]);
 await page.getByLabel('자동 스크롤',{exact:true}).uncheck();
 await page.getByTestId('log-viewport').evaluate(e=>{e.scrollTop=1000;});
 await page.evaluate(()=>{(window as any).parseDelay=200;});next=301;
 await expect(page.getByText('301줄 로드됨')).toBeVisible();
 expect(await page.evaluate(()=>(window as any).loadingTimes)).toEqual([]);
 await page.evaluate(()=>{window.scrollTo(0,100);(window as any).parseDelay=1300;});
 async function position(){return page.getByTestId('log-viewport').evaluate(e=>({top:e.getBoundingClientRect().top,height:e.getBoundingClientRect().height,scroll:e.scrollTop,page:window.scrollY,document:document.documentElement.scrollHeight}));}
 const before=await position();next=302;
 await expect(page.locator('.log-loading')).toBeVisible();
 expect(await position()).toEqual(before);
 const times=await page.evaluate(()=>({start:(window as any).parseStarted,shown:(window as any).loadingTimes[0]}));expect(times.shown-times.start).toBeGreaterThanOrEqual(490);
 await expect(page.getByText('302줄 로드됨')).toBeVisible();await expect(page.locator('.log-loading')).toHaveCount(0);
 expect(await position()).toEqual(before);
});
