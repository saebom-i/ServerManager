import {test,expect} from '@playwright/test';
test('initial folder import shows 20 GiB progress without starting Minecraft and refreshes files on completion',async({page})=>{
 let state='STOPPED',progress:any=null,done=false;let imports=0,starts=0;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'0.4.0-beta.8',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'csrf',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/memory')result={maxHeapMb:4096,totalMb:8192,freeMb:4096,reservedMb:1024,startableHeapMb:4096};
  else if(path==='/api/instances')result=[{config:{id:'a',name:'Existing server',loader:'Vanilla',minecraft:'1.21.1',loaderVersion:'1.21.1',port:25565,memoryMb:1024,javaPath:'java',runnerId:'r'},state,pid:null,folderSource:{state:done?'SYNCED':'PENDING',path:'/Users/friend/My server'},folderImport:progress}];
  else if(path==='/api/runners')result=[{id:'r',name:'Friend PC',enabled:true,online:true,allowedInstanceIds:['a']}];
  else if(path==='/api/instances/a/files')result=done?[{name:'world',path:'world',directory:true,size:0}]:[];
  else if(path==='/api/instances/a/import-folder'){
   expect(route.request().headers()['x-csrf-token']).toBe('csrf');imports++;state='IMPORTING';progress={phase:'UPLOADING',files:800,bytes:12*1024**3,totalFiles:1200,totalBytes:20*1024**3,message:''};
  } else if(path==='/api/instances/a/start')starts++;
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  await route.fulfill({json:result});
 });
 page.on('dialog',dialog=>dialog.accept());await page.goto('/');await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await expect(page.getByText('/Users/friend/My server',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'실행기의 기존 폴더 가져오기',exact:true}).click();
 await expect(page.getByText('변경 파일 전송 중',{exact:true})).toBeVisible();
 await expect(page.getByText('800 / 1,200개 · 12.00 GiB / 20.00 GiB',{exact:true})).toBeVisible();
 await expect(page.getByRole('progressbar',{name:'서버 파일 전송 진행률'})).toHaveAttribute('max',String(20*1024**3));
 await expect(page.getByRole('button',{name:'시작',exact:true})).toBeDisabled();expect(imports).toBe(1);expect(starts).toBe(0);
 done=true;state='STOPPED';progress={...progress,phase:'COMPLETE',files:1200,bytes:20*1024**3,message:'가져왔습니다'};
 await expect(page.getByText('가져오기 완료',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'world',exact:true})).toBeVisible();
 await expect(page.getByRole('progressbar')).toHaveCount(0);await expect(page.getByRole('status',{name:'서버 파일 전송 상태'})).toHaveCount(0,{timeout:6500});
 await expect(page.getByRole('button',{name:'시작',exact:true})).toBeEnabled();expect(starts).toBe(0);
});

test('regular transfer progress stays visible on console with direction percent and failure',async({page})=>{
 let value:any={phase:'UPLOADING',files:500,bytes:12*1024**3,totalFiles:1000,totalBytes:20*1024**3,direction:'TO_RUNNER',message:'변경된 파일만 다운로드합니다'};
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'csrf',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/memory')result={maxHeapMb:4096,totalMb:8192,freeMb:4096,reservedMb:1024,startableHeapMb:4096};
  else if(path==='/api/instances')result=[{config:{id:'a',name:'Transfer fixture',loader:'Vanilla',minecraft:'1.21.1',loaderVersion:'1.21.1',port:25565,memoryMb:1024,javaPath:'java',runnerId:'r'},state:'TRANSFERRING',pid:null,fileTransfer:value}];
  else if(path==='/api/runners')result=[{id:'r',name:'Friend PC',enabled:true,online:true,allowedInstanceIds:['a']}];
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  await route.fulfill({json:result});
 });
 await page.goto('/');const status=page.getByRole('status',{name:'서버 파일 전송 상태'});
 await expect(status).toContainText('관리 서버 → 실행기');await expect(status).toContainText('500 / 1,000개 · 12.00 GiB / 20.00 GiB');await expect(status).toContainText('60.0%');
 await expect(status.getByRole('progressbar')).toHaveAttribute('value',String(12*1024**3));
 value={...value,direction:'TO_MANAGER',phase:'VERIFYING',message:'파일 검증 중'};
 await expect(status).toContainText('실행기 → 관리 서버');await expect(status).toContainText('검증·반영 중');await expect(status.getByRole('progressbar')).toHaveCount(0);
 value={...value,phase:'FAILED',message:'연결을 확인하세요'};
 await expect(status).toContainText('전송 실패');await expect(status).toContainText('연결을 확인하세요');
});


test('transfer completion survives polling, spacious cards fit mobile and reduced motion disables animation',async({page})=>{
 let phase='UPLOADING',state='SYNCING';let reads=0;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'csrf',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/memory')result={maxHeapMb:4096,totalMb:8192,freeMb:4096,reservedMb:1024,startableHeapMb:4096};
  else if(path==='/api/instances'){
   reads++;result=[{config:{id:'a',name:'Create server',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.249',port:6399,memoryMb:2048,javaPath:'java',runnerId:'r'},state,pid:null,fileTransfer:{phase,files:1200,bytes:20*1024**3,totalFiles:1200,totalBytes:20*1024**3,direction:'TO_MANAGER',message:phase==='COMPLETE'?'서버 파일 인계를 완료했습니다':'변경된 서버 파일을 관리 서버로 전송합니다'}}];
  }else if(path==='/api/runners')result=[{id:'r',name:'Mac Mini',enabled:true,online:true,allowedInstanceIds:['a']}];
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};else if(path.endsWith('/files'))result=[];
  await route.fulfill({json:result});
 });
 await page.goto('/');const status=page.getByRole('status',{name:'서버 파일 전송 상태'});
 await expect(status.getByRole('progressbar')).toBeVisible();
 const layout=await status.evaluate(el=>{const p=el.querySelector('progress')!,box=el.getBoundingClientRect(),bar=p.getBoundingClientRect(),style=getComputedStyle(el);return {padding:parseFloat(style.padding),width:bar.width,cardWidth:box.width,bottom:box.bottom-bar.bottom};});
 expect(layout.padding).toBeGreaterThanOrEqual(20);expect(layout.width).toBeGreaterThan(400);expect(layout.bottom).toBeGreaterThan(24);
 await page.screenshot({path:'../build/ui-transfer-desktop.png',fullPage:true});
 await page.emulateMedia({reducedMotion:'reduce'});await page.setViewportSize({width:390,height:844});
 await expect(status).toBeVisible();expect(await status.evaluate(el=>getComputedStyle(el).animationName)).toBe('none');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 await page.screenshot({path:'../build/ui-transfer-mobile.png',fullPage:true});
 await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await expect(page.getByText('서버 실행·파일 작업 중에는 파일을 변경할 수 없습니다.',{exact:true})).toHaveCount(1);
 await expect(page.getByRole('progressbar')).toHaveCount(1);
 // Bytes may reach 100% before validation/commit. Keep the busy state until COMPLETE.
 phase='VERIFYING';await expect(status).toContainText('검증·반영 중');await expect(status).toHaveAttribute('aria-busy','true');
 phase='COMPLETE';state='STOPPED';await expect(status).toContainText('전송 완료');await expect(status).toHaveAttribute('aria-busy','false');await expect(page.getByRole('progressbar')).toHaveCount(0);
 const completedReads=reads;await expect(status).toHaveCount(0,{timeout:6500});
 await expect.poll(()=>reads,{timeout:5000}).toBeGreaterThan(completedReads+1);await expect(status).toHaveCount(0);
 await expect(page.getByRole('button',{name:'시작',exact:true})).toBeEnabled();
});
