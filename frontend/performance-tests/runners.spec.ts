import {test,expect,Page} from '@playwright/test';

async function fixture(page:Page,owner:boolean,permission='ADMIN'){
 const config={id:'a',name:'테스트 서버',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:512,javaPath:'/java',runnerId:null as string|null};
 let runners:any[]=[],state='STOPPED';const assignments:any[]=[];
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'0.4.0-beta.4',user:{id:'user',name:'테스터',owner,permissions:{a:permission}},csrf:'csrf',origin:'http://127.0.0.1:8792',publicOrigin:'https://mc.example.com',setupRequired:false};
  else if(path==='/api/instances')result=[{config,state,pid:state==='RUNNING'?1:null}];
  else if(path==='/api/version-catalog')result={versions:['21.1.252']};
  else if(path==='/api/memory')result={maxHeapMb:2048,totalMb:8192,freeMb:4096,reservedMb:1024,startableHeapMb:2048};
  else if(path==='/api/runners')result=runners;
  else if(path==='/api/runners/enroll'){expect(route.request().postDataJSON()).toEqual({name:'내 노트북'});result={code:'test-only-single-use-enrollment-code'};}
  else if(path==='/api/instances/a/runner'){config.runnerId=route.request().postDataJSON().runnerId;assignments.push(config.runnerId);}
  else if(path.endsWith('/allow/a')){runners[0].allowedInstanceIds=['a'];}
  else if(path==='/api/auth/credentials'||path==='/api/admin/users'||path==='/api/admin/audit')result=[];
  else if(path==='/api/admin/discord')result={config:{enabled:false,grants:[],alerts:[],superAdmins:[]},revision:0,tokenConfigured:false};
  else if(path==='/api/admin/discord/status')result={state:'OFF',message:'꺼짐',guilds:[]};
  else if(path==='/api/admin/webdav')result={config:{enabled:false,url:'',username:''},revision:0,passwordConfigured:false};
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  await route.fulfill({json:result});
 });
 return {assignments,config,setRunners:(rows:any[])=>runners=rows,setState:(value:string)=>state=value};
}

test('execution selection and registration are visible; server navigation leaves global settings',async({page})=>{
 await fixture(page,true);await page.goto('/');
 await expect(page.getByLabel('실행 위치',{exact:true})).toBeVisible();
 await expect(page.getByText('이 서버에 허용된 원격 실행기가 없습니다.',{exact:false})).toBeVisible();
 await expect(page.getByText('PASSKEY PROTECTED · v0.4.0-beta.4')).toBeVisible();
 await page.getByRole('button',{name:'실행기 등록·관리',exact:true}).click();
 const panel=page.getByRole('region',{name:'서버 실행기',exact:true});
 await expect(panel.getByRole('link',{name:'Mac 실행기 다운로드'})).toHaveAttribute('href',/ServerManager-Runner-0.4.0-beta.4-mac-universal.zip$/);
 await expect(panel.getByText('아직 등록된 실행기가 없습니다.',{exact:false})).toBeVisible();
 await page.getByRole('button',{name:/테스트 서버 NeoForge/}).click();await expect(page.getByLabel('실행 위치',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'계정과 보안',exact:true}).click();await expect(page.getByRole('heading',{name:'계정과 접근 보안'})).toBeVisible();
 await page.getByRole('button',{name:/테스트 서버 NeoForge/}).click();await expect(page.getByRole('textbox',{name:'콘솔 명령'})).toBeVisible();
 await expect(page.getByRole('heading',{name:'계정과 접근 보안'})).toHaveCount(0);
});

test('invited admin can enroll and authorize a device before selecting it',async({page})=>{
 const f=await fixture(page,false);await page.goto('/#runners');
 const panel=page.getByRole('region',{name:'서버 실행기',exact:true});
 await expect(panel.getByLabel('실행기에 입력할 관리 웹 주소')).toHaveValue('https://mc.example.com');
 await panel.getByLabel('새 실행기 이름').fill('내 노트북');await panel.getByRole('button',{name:'패스키로 실행기 등록 코드 발급'}).click();
 await expect(panel.getByLabel('한 번만 표시되는 등록 코드')).toHaveValue('test-only-single-use-enrollment-code');
 f.setRunners([{id:'r',name:'내 노트북',platform:'MAC',registeredBy:'user',version:'0.4.0-beta.4',enabled:true,online:true,allowedInstanceIds:[]}]);
 await page.reload();await panel.getByRole('checkbox',{name:'테스트 서버',exact:true}).click();await expect(panel.getByRole('checkbox',{name:'테스트 서버',exact:true})).toBeChecked();
 await page.getByRole('button',{name:/테스트 서버 NeoForge/}).click();await page.getByLabel('실행 위치',{exact:true}).selectOption('r');
 await expect.poll(()=>f.assignments).toEqual(['r']);await expect(page.getByLabel('실행 위치',{exact:true})).toHaveValue('r');
 f.setState('RUNNING');await expect(page.getByLabel('실행 위치',{exact:true})).toBeDisabled();
 await expect(page.getByRole('button',{name:'실행기 등록·관리',exact:true})).toBeEnabled();
});

test('viewer can register their device but cannot choose a server execution location',async({page})=>{
 await fixture(page,false,'VIEWER');await page.goto('/');
 await expect(page.getByLabel('실행 위치',{exact:true})).toHaveCount(0);
 await page.getByRole('button',{name:'서버 실행기',exact:true}).click();
 await expect(page.getByLabel('새 실행기 이름')).toBeVisible();
});
