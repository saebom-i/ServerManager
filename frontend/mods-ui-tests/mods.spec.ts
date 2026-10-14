import {test,expect} from '@playwright/test';

test('large mod list keeps filtering responsive, pages rows and does not rescan on status polls',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 const mods=Array.from({length:1200},(_,n)=>({filename:`mod-${n}.jar`,enabled:true,size:100,sha256:'a'.repeat(64),warning:null,metadata:{mods:[{id:`mod_${n}`,name:`Mod ${n}`,version:'1.0',provides:[]}],complete:true,warnings:[],requirements:[]}}));
 let inventoryReads=0,refreshes=0,preview:any=null;const calls:string[]=[];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};calls.push(route.request().method()+' '+path);
  if(path==='/api/auth/session')body={appVersion:'0.4.3',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'모드 검증',loader:'Fabric',minecraft:'1.21.1',loaderVersion:'0.19.5',port:25565,memoryMb:1024,javaPath:'java',runnerId:null},state:'STOPPED',fileTransfer:{phase:'UPLOADING',files:2500,totalFiles:5000,bytes:10485760,totalBytes:41943040,bytesPerSecond:1048576,remainingSeconds:30,message:'이어받기'},pid:null,directory:'/fixture/server',eulaAccepted:true,resources:null}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,committedHeapMb:0,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path==='/api/runners')body=[];
  else if(path.endsWith('/mods')){if(url.searchParams.has('inventory'))inventoryReads++;body={inventory:url.searchParams.has('inventory')?{revision:'revision',mods,warnings:[],dependencyFiles:['mod-1.jar'],dependencyReasons:{'mod-1.jar':['mod-0.jar']}}:null,inventoryRevision:'revision',job:null,diagnostic:null,next:null,active:false};}
  else if(path.endsWith('/mods/refresh'))refreshes++;
  else if(path.endsWith('/toggle-preview')){preview=route.request().postDataJSON();body={...preview,selected:preview.files,affected:[...preview.files,'dependent.jar']};}
  else if(path.endsWith('/event-page'))body={events:[],hasMore:false};
  else if(path.endsWith('/log-files'))body=[];
  else if(path.endsWith('/log-display'))body={revision:0,enabled:true,rules:[],presets:[]};
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
 await expect(page.getByLabel('전송 속도와 예상 시간')).toContainText('1.0 MiB/s');await expect(page.getByLabel('전송 속도와 예상 시간')).toContainText('30초');await expect(page.getByLabel('mod-0.jar 선택',{exact:true})).toBeVisible();
 expect(await page.locator('.mod-table tbody tr').count()).toBe(75);await expect(page.getByLabel('mod-1.jar 선택',{exact:true})).toBeDisabled();await expect(page.getByLabel('mod-1.jar 의심 모드',{exact:true})).toBeDisabled();await expect(page.getByLabel('전송 속도와 예상 시간')).toContainText('1.0 MiB/s');await expect(page.getByLabel('전송 속도와 예상 시간')).toContainText('30초');await expect(page.getByLabel('mod-0.jar 선택',{exact:true})).toBeEnabled();const lockedRow=page.locator('.mod-table tbody tr').filter({hasText:'mod-1.jar'}).first();await lockedRow.getByText('필수 의존성 · 비활성화 불가',{exact:true}).click();await expect(lockedRow).toContainText('mod-0.jar에서 필수로 사용하는 모드');
 const input=page.getByRole('textbox',{name:'모드 검색'}),times:number[]=[];
 for(const value of ['Mod 100','Mod 1001','Mod 100','Mod 1002','Mod 100']){const now=performance.now();await input.fill(value);await expect(page.locator('.mod-table tbody tr').first()).toContainText('100');times.push(performance.now()-now);}
 expect(Math.max(...times)).toBeLessThan(350);
 await input.fill('mod-1001.jar');await page.getByLabel('mod-1001.jar 선택',{exact:true}).check();
 await page.getByRole('button',{name:'선택 모드 비활성화',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'모드 변경 확인'});await expect(dialog).toContainText('dependent.jar');
 expect(preview.files).toEqual(['mod-1001.jar']);await dialog.getByRole('button',{name:'취소',exact:true}).click();
 await page.waitForTimeout(3300);expect(inventoryReads).toBe(1);expect(refreshes).toBe(0);
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:'test-results/mod-list-mobile.png',fullPage:true});
 for(let n=0;n<4;n++){await page.getByRole('button',{name:'콘솔',exact:true}).click();await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();await expect(page.locator('.runner-assignment')).toHaveCount(1);}
 expect(errors).toEqual([]);expect(calls.filter(c=>c.endsWith('/mods/diagnostic')).length).toBe(0);
 console.log('1200 mods, typing times ms:',times.map(n=>n.toFixed(0)).join(','));
});
