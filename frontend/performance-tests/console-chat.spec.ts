import {test,expect} from '@playwright/test';
test('console wraps chat with say, strips command slash and sends each submission once',async({page})=>{
 const commands:string[]=[];let fail=false;let release:(()=>void)|undefined;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'0.4.0',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config:{id:'test',name:'Chat',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:512,javaPath:'/java'},state:'RUNNING',pid:123,directory:'/test',resources:null}];
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  else if(path.endsWith('/command')){commands.push(route.request().postDataJSON().command);if(fail){fail=false;await route.fulfill({status:409,json:{message:'전송 실패'}});return;}if(release)await new Promise<void>(resolve=>{release=resolve;});}
  await route.fulfill({json:result});
 });
 await page.goto('/');const input=page.getByRole('textbox',{name:'콘솔 명령',exact:true});
 for(const [typed,expected] of [['안녕하세요 여러분','say 안녕하세요 여러분'],['list','say list'],[' /list ','list'],['/say 안녕','say 안녕']]){
  await input.fill(typed);await input.press('Enter');await expect.poll(()=>commands.at(-1)).toBe(expected);await expect(input).toBeEnabled();
 }
 await input.fill('/');await input.press('Enter');await expect(page.getByRole('alert')).toContainText('/ 뒤에');expect(commands).toHaveLength(4);
 fail=true;await input.fill('다시 보내기');await input.press('Enter');await expect(page.getByRole('alert')).toContainText('전송 실패');await expect(input).toHaveValue('다시 보내기');
 release=()=>{};await input.press('Enter');await expect(input).toBeDisabled();await page.locator('.command-bar').evaluate(form=>{form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));});expect(commands).toHaveLength(6);release!();await expect(input).toBeEnabled();
 expect(commands).toEqual(['say 안녕하세요 여러분','say list','list','say 안녕','say 다시 보내기','say 다시 보내기']);
});

test('auto restart settings save independently while the server runs and expose cancel',async({page})=>{
 let policy={enabled:false,includeManual:false};let canceled=false;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'0.4.0',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config:{id:'test',name:'Restart',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:512,javaPath:'/java'},state:'RUNNING',pid:123,directory:'/test',resources:null,autoRestart:{config:policy,status:policy.enabled?(canceled?'PAUSED':'COUNTDOWN'):'OFF',remainingSeconds:policy.enabled&&!canceled?15:null,attempts:0,message:null}}];
  else if(path==='/api/memory')result={totalMb:16384,freeMb:8192,reservedMb:2048,maxHeapMb:14336,committedHeapMb:512,startableHeapMb:13824};
  else if(path.endsWith('/auto-restart')&&route.request().method()==='PUT')policy=route.request().postDataJSON();
  else if(path.endsWith('/auto-restart/cancel'))canceled=true;
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  await route.fulfill({json:result});
 });
 await page.goto('/');await page.getByRole('button',{name:'서버 설정',exact:true}).click();
 const toggle=page.getByLabel('서버 자동 재시작',{exact:true});await expect(toggle).not.toBeChecked();await expect(toggle).toBeEnabled();await toggle.check();
 await page.getByRole('button',{name:'자동 재시작 설정 저장'}).click();await expect.poll(()=>policy.enabled).toBe(true);expect(policy.includeManual).toBe(false);
 await expect(page.getByText('15초 후 재시작',{exact:true})).toBeVisible();await page.getByRole('button',{name:'이번 재시작 취소'}).click();await expect.poll(()=>canceled).toBe(true);await expect(page.getByRole('button',{name:'이번 재시작 취소'})).toHaveCount(0);
});
