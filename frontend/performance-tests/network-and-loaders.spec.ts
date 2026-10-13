import {test,expect,Page} from '@playwright/test';

async function fixture(page:Page){
 const created:any[]=[],payloads:any[]=[];
 let network:any={enabled:true,port:6399,baseDomain:'server.example.com',targetHost:'gateway.example.com',defaultInstanceId:null,defaultHostname:'',labels:{},cloudflareEnabled:false,zoneId:''},revision=0,tokenConfigured=false;
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),path=url.pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'0.4.0-beta.5',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'https://server.example.com',setupRequired:false};
  else if(path==='/api/memory')result={totalMb:8192,freeMb:4096,reservedMb:1024,maxHeapMb:7168,startableHeapMb:7168};
  else if(path==='/api/health')result={javaPath:'/java'};
  else if(path==='/api/minecraft-versions')result={versions:['1.21.1','1.21.4']};
  else if(path==='/api/version-catalog'){const loader=url.searchParams.get('loader'),mc=url.searchParams.get('minecraft');result={versions:[loader==='Paper'?'133':loader==='Fabric'?'0.16.10':loader==='Forge'?'52.1.0':loader==='Vanilla'?mc:mc==='1.21.4'?'21.4.111':'21.1.252']};}
  else if(path==='/api/instances'){
   if(req.method()==='POST'){const config={...req.postDataJSON(),id:'server-'+created.length,runnerId:null};created.push({config,state:'NEW',pid:null});result=created.at(-1);}else result=created;
  } else if(path==='/api/runners')result=[];
  else if(path==='/api/game-addresses')result={enabled:network.enabled,port:network.port,baseDomain:network.baseDomain,labels:network.labels,hosts:Object.fromEntries(created.map(({config:i})=>[i.id,`${network.labels[i.id]||'s-'+i.id.slice(0,8)}.${network.baseDomain}`]))};
  else if(path==='/api/admin/game-network'){
   if(req.method()==='PUT'){const payload=req.postDataJSON();payloads.push(payload);network=payload.config;revision++;tokenConfigured=!payload.clearToken&&(!!payload.token||tokenConfigured);result={config:network,revision,tokenConfigured};}
   else result={settings:{config:network,revision,tokenConfigured},dnsMessage:'DNS 설정 대기'};
  } else if(path.endsWith('/game-domain')){network.labels[path.split('/')[3]]=req.postDataJSON().label;revision++;}
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  await route.fulfill({json:result});
 });
 return {created,payloads};
}

test('create dialog selects all five software types without stale version choices',async({page})=>{
 const f=await fixture(page);await page.goto('/');
 for(const loader of ['NeoForge','Forge','Fabric','Paper','Vanilla']){
  await page.getByRole('button',{name:'서버 만들기',exact:true}).click();
  const dialog=page.locator('.modal');await dialog.getByLabel('서버 종류',{exact:true}).selectOption(loader);
  const expected=loader==='Paper'?'133':loader==='Fabric'?'0.16.10':loader==='Forge'?'52.1.0':loader==='Vanilla'?'1.21.1':'21.1.252';
  await expect(dialog.getByLabel(loader+' 버전 선택',{exact:true})).toHaveValue(expected);
  if(loader==='NeoForge'){await dialog.getByLabel('Minecraft 버전',{exact:true}).selectOption('1.21.4');await expect(dialog.getByLabel('NeoForge 버전 선택',{exact:true})).toHaveValue('21.4.111');}
  await dialog.getByLabel('서버 이름',{exact:true}).fill(loader+' server');await dialog.getByRole('button',{name:'서버 만들기',exact:true}).click();
  await expect(dialog).toHaveCount(0);expect(f.created.at(-1).config.loader).toBe(loader);
  await expect(page.getByRole('button',{name:loader+' 설치',exact:true})).toBeVisible();
 }
 expect(f.created[0].config.minecraft).toBe('1.21.4');expect(f.created[0].config.loaderVersion).toBe('21.4.111');
});

test('network token stays masked and per-server domain editing leaves shared port fixed',async({page})=>{
 const f=await fixture(page);f.created.push({config:{id:'a',name:'서버 A',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',memoryMb:512,port:25565,runnerId:null},state:'STOPPED',pid:null});
 await page.goto('/#network');const panel=page.getByRole('region',{name:'게임 주소·TCP 중계'});
 await panel.getByLabel('서버별 A·SRV 레코드 자동 등록').check();await panel.getByLabel('Cloudflare Zone ID',{exact:true}).fill('a'.repeat(32));
 const input=panel.getByLabel('Cloudflare DNS 편집 API 토큰',{exact:true});await input.fill('fake_cloudflare_secret_for_ui_tests');
 await panel.getByRole('button',{name:'중계·DNS 설정 저장',exact:true}).click();await expect(panel.getByText('공유 중계 설정을 저장했습니다.',{exact:false})).toBeVisible();
 await expect(input).toHaveValue('');await expect(input).toHaveAttribute('type','password');expect(f.payloads[0].config.port).toBe(6399);
 await page.getByRole('button',{name:/서버 A NeoForge/}).click();await expect(page.getByRole('textbox',{name:'콘솔 명령',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'서버 설정',exact:true}).click();await page.getByLabel('이 서버의 하위 도메인 이름').fill('adventure');await page.getByRole('button',{name:'서버 도메인 저장',exact:true}).click();
 await expect(page.getByText('adventure.server.example.com',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'게임 주소·중계',exact:true}).click();await expect(panel.getByLabel('공유 게임 포트',{exact:true})).toHaveValue('6399');
});
