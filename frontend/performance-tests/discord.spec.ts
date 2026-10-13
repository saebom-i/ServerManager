import {test,expect} from '@playwright/test';
test('Discord drafts survive status polling and save role routes without returning tokens',async({page})=>{
 let saved:any={config:{enabled:false,grants:[],alerts:[]},revision:0,tokenConfigured:false},payload:any;
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'csrf',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[{config:{id:'a',name:'친구 서버',port:25565,memoryMb:512,loaderVersion:'21.1.252'},state:'STOPPED',pid:null}];
  else if(path==='/api/version-catalog')result={versions:['21.1.252']};
  else if(path==='/api/auth/credentials'||path==='/api/admin/users'||path==='/api/admin/audit')result=[];
  else if(path==='/api/admin/discord'){
   if(route.request().method()==='PUT'){payload=route.request().postDataJSON();saved={config:payload.config,revision:saved.revision+1,tokenConfigured:payload.clearToken?false:!!payload.token||saved.tokenConfigured};}
   result=saved;
  } else if(path==='/api/admin/discord/status')result={state:'OFF',message:'연결 준비',inviteUrl:null,guilds:[]};
  else if(path.endsWith('/event-page'))result={events:[],hasOlder:false};
  await route.fulfill({json:result});
 });
 await page.goto('/');await page.getByRole('button',{name:'계정과 보안',exact:true}).click();
 const panel=page.getByRole('region',{name:'Discord 봇 · BETA'});
 await panel.getByLabel('봇 토큰',{exact:true}).fill('fake_secret_for_ui_test_1234567890');
 await panel.getByLabel('Discord 연동 켜기').check();await panel.getByRole('button',{name:'역할 규칙 추가'}).click();
 await panel.getByLabel('Discord 서버 ID',{exact:true}).fill('123456789012345678');await panel.getByLabel('역할 ID',{exact:true}).fill('223456789012345678');await panel.getByLabel('서버 시작 허용').check();
 await panel.getByRole('button',{name:'알림 규칙 추가'}).click();await panel.getByLabel('알림 Discord 서버 ID').fill('123456789012345678');await panel.getByLabel('텍스트 채널 ID').fill('323456789012345678');
 await page.waitForTimeout(5300);
 await expect(panel.getByLabel('봇 토큰',{exact:true})).toHaveValue('fake_secret_for_ui_test_1234567890');
 await panel.getByRole('button',{name:'Discord 설정 저장'}).click();await expect(panel.getByText('Discord 설정을 저장했습니다.',{exact:false})).toBeVisible();
 expect(payload.config.grants[0]).toEqual({guildId:'123456789012345678',roleId:'223456789012345678',instanceId:'a',canStart:true});expect(payload.config.alerts[0].channelId).toBe('323456789012345678');
 await expect(panel.getByLabel('봇 토큰',{exact:true})).toHaveValue('');await expect(panel.getByLabel('봇 토큰',{exact:true})).toHaveAttribute('type','password');
 await panel.getByRole('button',{name:'저장된 토큰 삭제 예약'}).click();await expect(panel.getByLabel('Discord 연동 켜기')).not.toBeChecked();await panel.getByRole('button',{name:'Discord 설정 저장'}).click();await expect.poll(()=>saved.tokenConfigured).toBe(false);expect(payload.clearToken).toBe(true);expect(errors).toEqual([]);
});
