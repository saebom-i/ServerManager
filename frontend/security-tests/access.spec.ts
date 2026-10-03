import {test,expect,Page,BrowserContext} from '@playwright/test';
import {readFile,readdir} from 'node:fs/promises';

async function authenticator(context:BrowserContext,page:Page){
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');
 await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 return cdp;
}
async function csrf(page:Page){return (await (await page.request.get('/api/auth/session')).json()).csrf;}
async function post(page:Page,path:string,body:unknown={},method='POST'){
 return page.request.fetch(path,{method,data:body,headers:{'X-CSRF-TOKEN':await csrf(page),'Origin':'http://localhost:8789'}});
}

test('passkeys, sessions, server permissions and real CPU display',async({browser,page,context,request})=>{
 const root=process.env.SM_E2E_DATA||'';expect(root.startsWith('/tmp/server-manager-security.')).toBe(true);
 expect((await request.get('/api/instances')).status()).toBe(401);
 expect((await request.post('/api/instances',{data:{}})).status()).toBe(403);
 expect((await request.get('/api/health')).status()).toBe(401);
 await authenticator(context,page);
 await page.goto('/');
 const code=(await readFile(root+'/security/setup-code.txt','utf8')).trim();
 let registration:any;
 page.on('request',req=>{if(req.url().endsWith('/auth/register/finish'))registration=req.postDataJSON();});
 await page.getByLabel('등록 코드').fill(code);
 await page.getByRole('button',{name:'패스키 등록',exact:true}).click();
 await expect(page.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 expect((await post(page,'/api/auth/register/finish',registration)).status()).toBe(400);
 const raw=await page.request.post('/api/auth/login/options',{data:{},headers:{'Origin':'https://evil.example','X-CSRF-TOKEN':await csrf(page)}});
 expect(raw.status()).toBe(403);
 expect((await page.request.post('/api/auth/login/options',{data:{}})).status()).toBe(403);
 const health=await (await page.request.get('/api/health')).json();
 const created=await post(page,'/api/instances',{name:'보안·CPU 검증',loaderVersion:'21.1.252',port:25771,memoryMb:1536,javaPath:health.javaPath});
 expect(created.ok()).toBe(true);const instance=(await created.json()).config;
 const other=await post(page,'/api/instances',{name:'비공개 인스턴스',loaderVersion:'21.1.252',port:25772,memoryMb:512,javaPath:health.javaPath});
 const hidden=(await other.json()).config;
 // Real server metrics, not an API fixture.
 expect((await post(page,`/api/instances/${instance.id}/install`)).ok()).toBe(true);
 await expect.poll(async()=>{const rows=await (await page.request.get('/api/instances')).json();return rows.find((r:any)=>r.config.id===instance.id).state;},{timeout:180000,intervals:[2000]}).toBe('STOPPED');
 await post(page,`/api/instances/${instance.id}/start`);
 try {
  await expect.poll(async()=>{const rows=await (await page.request.get('/api/instances')).json();return rows.find((r:any)=>r.config.id===instance.id).state;},{timeout:90000,intervals:[2000]}).toBe('RUNNING');
  await page.reload();
  await page.getByRole('button',{name:/보안·CPU 검증 NeoForge/}).click();
  await expect(page.getByTestId('cpu-value')).toContainText('%');
  await expect(page.getByTestId('rss-value')).toContainText('MiB');
  expect(await page.locator('[data-event-seq]').count()).toBeLessThan(60);
  const line=page.locator('.virtual-log .log-text').last();
  await expect(line).not.toContainText(/^\[\d{2}:\d{2}:\d{2}\]/);
  await page.getByRole('button',{name:'이전 로그 300줄 불러오기'}).click();
  await expect(page.getByRole('button',{name:'이전 로그 300줄 불러오기'})).toBeEnabled();
  expect(await page.locator('[data-event-seq]').count()).toBeLessThan(60);
  const input=page.getByRole('textbox',{name:'콘솔 명령'});
  const started=Date.now();await input.pressSequentially('say responsive console input',{delay:0});
  expect(Date.now()-started).toBeLessThan(5000);await expect(input).toHaveValue('say responsive console input');await input.fill('');
  expect((await post(page,`/api/instances/${instance.id}`,{},'DELETE')).status()).toBe(409);
  await page.screenshot({path:'test-results/security-cpu.png',fullPage:true});
 } finally {await post(page,`/api/instances/${instance.id}/stop`);}
 await expect.poll(async()=>{const rows=await (await page.request.get('/api/instances')).json();return rows.find((r:any)=>r.config.id===instance.id).state;},{timeout:60000,intervals:[1000]}).toBe('STOPPED');
 await expect(page.getByTestId('cpu-value')).toHaveText('정지됨');
 // Reauthenticate before security-sensitive administration.
 await page.getByRole('button',{name:'계정과 보안',exact:true}).click();
 await page.getByRole('button',{name:'패스키 다시 인증',exact:true}).click();
 await expect(page.getByText(/본인 확인을 완료했습니다/)).toBeVisible();
 const startup=await (await page.request.get('/api/admin/startup')).json();
 if(startup.supported){
  await page.getByLabel('Mac 로그인 시 자동 실행',{exact:true}).check();
  await expect(page.getByLabel('Mac 로그인 시 자동 실행',{exact:true})).toBeChecked();
  await expect(page.getByLabel('Mac 로그인 시 자동 실행',{exact:true})).toBeEnabled();
  const plist=await readFile(root+'/home/Library/LaunchAgents/local.servermanager.plist','utf8');expect(plist).toContain('--service');
  await page.getByLabel('Mac 로그인 시 자동 실행',{exact:true}).uncheck();
  await expect(page.getByLabel('Mac 로그인 시 자동 실행',{exact:true})).not.toBeChecked();
  await expect(page.getByLabel('Mac 로그인 시 자동 실행',{exact:true})).toBeEnabled();
 }

 await page.getByLabel('사용자 이름',{exact:true}).fill('조회 사용자');
 await page.getByRole('button',{name:'초대 코드 발급',exact:true}).click();
 const codeInput=page.getByLabel('한 번만 표시되는 초대 코드',{exact:true});await expect(codeInput).toBeVisible();
 const invitation=await codeInput.inputValue();
 const pending=(await (await page.request.get('/api/admin/users')).json()).find((u:any)=>u.name==='조회 사용자');
 const member=page.getByTestId('member-'+pending.id);
 await expect(member).toContainText('초대 대기');
 await member.getByLabel('조회 사용자 · 보안·CPU 검증 권한',{exact:true}).selectOption('ADMIN');
 await member.getByRole('button',{name:'권한 저장',exact:true}).click();
 await expect.poll(async()=>{const users=await (await page.request.get('/api/admin/users')).json();return users.find((u:any)=>u.id===pending.id).permissions[instance.id];}).toBe('ADMIN');
 await member.getByLabel('조회 사용자 · 보안·CPU 검증 권한',{exact:true}).selectOption('VIEWER');
 await member.getByRole('button',{name:'권한 저장',exact:true}).click();
 await expect.poll(async()=>{const users=await (await page.request.get('/api/admin/users')).json();return users.find((u:any)=>u.id===pending.id).permissions[instance.id];}).toBe('VIEWER');
 const guestContext=await browser.newContext({baseURL:'http://localhost:8789'});const guest=await guestContext.newPage();await authenticator(guestContext,guest);
 await guest.goto('/');await guest.getByLabel('등록 코드').fill(invitation);await guest.getByRole('button',{name:'패스키 등록',exact:true}).click();
 await expect(guest.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 const visible=await (await guest.request.get('/api/instances')).json();expect(visible.map((r:any)=>r.config.id)).toEqual([instance.id]);
 expect((await guest.request.get(`/api/instances/${hidden.id}/files`)).status()).toBe(403);
 expect((await post(guest,`/api/instances/${instance.id}/command`,{command:'stop'})).status()).toBe(403);
 expect((await post(guest,`/api/instances/${instance.id}`,{},'DELETE')).status()).toBe(403);
 expect((await post(guest,`/api/instances/${instance.id}/text`,{path:'test.txt',content:'not permitted'},'PUT')).status()).toBe(403);
 expect((await guest.request.get('/api/admin/users')).status()).toBe(403);
 expect((await post(guest,'/api/instances',{})).status()).toBe(403);
 expect((await guest.request.get(`/api/instances/${instance.id}/download?path=../../security/accounts.json`)).status()).toBe(400);
 const user=(await (await page.request.get('/api/admin/users')).json()).find((u:any)=>u.name==='조회 사용자');
 expect((await post(page,'/api/admin/users/'+user.id,{enabled:false,permissions:{}},'PUT')).ok()).toBe(true);
 expect((await guest.request.get('/api/instances')).status()).toBe(401);
 await guestContext.close();
 // Logout invalidates the session; an altered WebAuthn signature is rejected.
 await page.getByRole('button',{name:/로그아웃/}).click();
 await expect(page.getByRole('button',{name:'패스키로 로그인',exact:true})).toBeVisible();
 expect((await page.request.get('/api/instances')).status()).toBe(401);
 await page.route('**/auth/login/finish',async route=>{const body=route.request().postDataJSON();body.credential.response.signature='AA';await route.continue({postData:JSON.stringify(body)});});
 await page.getByRole('button',{name:'패스키로 로그인',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('패스키 인증에 실패했습니다');
 await page.unroute('**/auth/login/finish');
 await page.getByRole('button',{name:'패스키로 로그인',exact:true}).click();
 await expect(page.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 await page.getByRole('button',{name:/보안·CPU 검증 NeoForge/}).click();
 await page.getByRole('button',{name:'서버 설정',exact:true}).click();
 page.once('dialog',dialog=>dialog.accept('잘못된 이름'));
 await page.getByRole('button',{name:'서버 삭제',exact:true}).click();
 await expect(page.getByText('서버 이름이 일치하지 않아 삭제하지 않았습니다.')).toBeVisible();
 expect((await (await page.request.get('/api/instances')).json()).some((r:any)=>r.config.id===instance.id)).toBe(true);
 page.once('dialog',dialog=>dialog.accept(instance.name));
 await page.getByRole('button',{name:'서버 삭제',exact:true}).click();
 await expect(page.getByRole('button',{name:/보안·CPU 검증 NeoForge/})).toHaveCount(0);
 const archives=await readdir(root+'/deleted-instances');
 const archived=archives.find(name=>name.startsWith(instance.id+'-'));expect(archived).toBeTruthy();
 expect((await readFile(root+'/deleted-instances/'+archived+'/server/world/level.dat')).length).toBeGreaterThan(0);
 expect((await (await page.request.get('/api/instances')).json()).map((r:any)=>r.config.id)).toEqual([hidden.id]);
 const audit=await (await page.request.get('/api/admin/audit')).json();expect(audit.length).toBeGreaterThan(0);
 const auditText=await readFile(root+'/security/audit.jsonl','utf8');expect(auditText).not.toContain(code);expect(auditText).not.toContain(invitation);
 const response=await page.request.get('/');expect(response.headers()['content-security-policy']).toContain("frame-ancestors 'none'");
});
