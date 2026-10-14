import {test,expect} from '@playwright/test';
import {parseLog} from '../src/logParser';
import {hiddenByFilter,type FilterConfig} from '../src/LogFilters';
const config:FilterConfig={enabled:true,rules:{keywords:[],mods:['create'],onlyDebug:true},presets:[],activeId:null};
const line=(text:string,seq=1)=>({seq,time:null,kind:'output',text});
test('mod and keyword filters use log emitter tags, respect debug-only and never confuse mixin references',()=>{
 const a=parseLog(line('[12:30:00] [Server thread/DEBUG] [create/NETWORK]: ticking motor'));
 expect(a.logger).toBe('create/NETWORK');expect(a.level).toBe('DEBUG');expect(hiddenByFilter(a,config)).toBe(true);
 expect(hiddenByFilter(parseLog(line('[12:30:00] [Server thread/WARN] [create/NETWORK]: warning')),config)).toBe(false);
 expect(hiddenByFilter(parseLog(line('[12:30:00] [Server thread/DEBUG] [recreate/NETWORK]: message')),config)).toBe(false);
 expect(hiddenByFilter(parseLog(line('[12:30:00] [Server thread/TRACE] [com.simibubi.create/]: trace')),config)).toBe(true);
 expect(hiddenByFilter(parseLog(line('at mod.Class.method(File.java:1) {pl:mixin:APP:create.mixins.json:Mixin from mod create}')),config)).toBe(false);
 const keywords={...config,rules:{keywords:['REPEATED noise'],mods:[],onlyDebug:false}};
 expect(hiddenByFilter(parseLog(line('this is repeated NOISE')),keywords)).toBe(true);
 expect(hiddenByFilter(a,{...config,enabled:false})).toBe(false);
 expect(parseLog(line('ordinary text [DEBUG] [create]')).level).toBeUndefined();
});

test('viewer can hide debug spam, save per-server named presets and restore after reload without losing raw logs',async({page})=>{
 let saved:any={revision:0,config:{enabled:true,rules:{keywords:[],mods:[],onlyDebug:true},presets:[],activeId:null}};let saves=0;
 const events=[line('[12:00:00] [Server thread/DEBUG] [create/NETWORK]: noisy debug',1),line('[12:00:01] [Server thread/INFO] [create/NETWORK]: startup info',2),line('[12:00:02] [Server thread/DEBUG] [other/LOGGER]: repeated heartbeat',3),line('[12:00:03] [Server thread/ERROR] [create/NETWORK]: important error',4)];
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'test',user:{id:'viewer',name:'Viewer',owner:false,permissions:{test:'VIEWER'}},csrf:'csrf',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config:{id:'test',name:'Filters',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.249',port:25565,memoryMb:512,javaPath:'/java'},state:'RUNNING',pid:1,resources:null}];
  else if(path==='/api/log-filters/test'){
   if(route.request().method()==='PUT'){const payload=route.request().postDataJSON();expect(payload.revision).toBe(saved.revision);saved={config:payload.config,revision:saved.revision+1};saves++;}
   result=saved;
  }
  else if(path.endsWith('/event-page')){const after=Number(new URL(route.request().url()).searchParams.get('after')||0);result={events:events.filter(e=>e.seq>after),hasOlder:false};}
  await route.fulfill({json:result});
 });
 await page.goto('/');await expect(page.locator('.virtual-log')).toHaveCount(4);await page.getByText('로그 표시 필터',{exact:true}).click();
 const editor=page.locator('.log-filter-settings');await expect(editor.getByLabel('제외할 모드·로거')).toBeEnabled();await editor.getByLabel('제외할 모드·로거').fill('create');await editor.getByLabel('제외할 키워드').fill('repeated heartbeat');await editor.getByRole('button',{name:'필터 적용',exact:true}).click();
 await expect(page.locator('.virtual-log')).toHaveCount(2);await expect(page.getByText('important error',{exact:false})).toBeVisible();
 await editor.getByLabel('필터 이름',{exact:true}).fill('디버그 소음 숨기기');await editor.getByRole('button',{name:'이름으로 필터 저장'}).click();await expect.poll(()=>saves).toBe(1);
 await page.reload();await expect(page.locator('.virtual-log')).toHaveCount(2);await page.locator('.log-filter-settings summary').click();
 await expect(page.getByLabel('저장된 로그 필터')).toHaveValue(saved.config.activeId);
 await page.getByLabel('표시 필터 사용').uncheck();await expect(page.locator('.virtual-log')).toHaveCount(4);expect(events).toHaveLength(4);
 await page.getByLabel('표시 필터 사용').check();await expect(page.locator('.virtual-log')).toHaveCount(2);
 await page.screenshot({path:'../build/log-filter-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:1000});await page.screenshot({path:'../build/log-filter-mobile.png',fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);
});
