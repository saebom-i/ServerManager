import {test,expect} from '@playwright/test';

test('runner update controls show legacy limitation, progress and registrar permissions',async({page})=>{
 const requests:any[]=[];let phase='IDLE';
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'0.4.4',user:{id:'friend',name:'Friend',owner:false,permissions:{}},csrf:'fixture',origin:'http://127.0.0.1:5175',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[];
  else if(path==='/api/runners')body=[
   {id:'new',name:'내 Mac',platform:'MAC',registeredBy:'friend',version:'0.4.4',remoteUpdateSupported:true,enabled:true,online:true,allowedInstanceIds:[],lastSeen:null,update:{phase,latest:phase==='DOWNLOADING'?'0.4.5':null,downloadedBytes:50,totalBytes:100}},
   {id:'old',name:'구형 Mac',platform:'MAC',registeredBy:'friend',version:'0.4.0-beta.8',enabled:true,online:true,allowedInstanceIds:[],lastSeen:null},
   {id:'other',name:'다른 사람 PC',platform:'WINDOWS',registeredBy:'someone',version:'0.4.4',remoteUpdateSupported:true,enabled:true,online:true,allowedInstanceIds:[],lastSeen:null}];
  else if(path==='/api/runners/new/update'){requests.push(route.request().postDataJSON());phase='DOWNLOADING';}
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:'서버 실행기',exact:true}).click();
 const own=page.getByLabel('내 Mac 업데이트',{exact:true}),old=page.getByLabel('구형 Mac 업데이트',{exact:true}),other=page.getByLabel('다른 사람 PC 업데이트',{exact:true});
 await expect(own.getByRole('button',{name:'원격 업데이트 요청',exact:true})).toBeEnabled();
 await expect(old.getByRole('button',{name:'원격 업데이트 요청',exact:true})).toBeDisabled();await expect(old).toContainText('최초 1회');
 await expect(other.getByRole('button')).toHaveCount(0);
 await own.getByRole('button',{name:'전송 잠시 멈추고 업데이트',exact:true}).click();
 expect(requests).toEqual([{pauseTransfer:true}]);await expect(own).toContainText('50%');await expect(own.getByRole('progressbar')).toHaveAttribute('value','50');
 await expect(own.getByRole('button',{name:'원격 업데이트 요청',exact:true})).toBeDisabled();
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
