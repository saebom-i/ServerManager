import {test,expect} from '@playwright/test';
import {readFile,writeFile} from 'node:fs/promises';
test('authenticated raw log API and worker operate under production CSP',async({page,context,browser})=>{
 const root=process.env.SM_LOG_TEST||'';expect(root.startsWith('/tmp/server-manager-logs.')).toBe(true);
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 const anonymous=await browser.newContext();expect((await anonymous.request.get('http://localhost:8793/api/instances/unknown/log-files')).status()).toBe(401);await anonymous.close();
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(root+'/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 const session=await (await page.request.get('/api/auth/session')).json();
 const created=await page.request.post('/api/instances',{headers:{'X-CSRF-TOKEN':session.csrf,Origin:'http://localhost:8793'},data:{name:'Log integration',loaderVersion:'21.1.252',port:25993,memoryMb:512}});expect(created.ok()).toBe(true);
 const id=(await created.json()).config.id;
 const discordToken='fake_token_for_isolated_http_test_1234567890';
 const discordRequest={config:{enabled:false,grants:[{guildId:'123456789012345678',roleId:'223456789012345678',instanceId:id,canStart:true}],alerts:[]},revision:0,token:discordToken};
 expect((await page.request.put('/api/admin/discord',{data:discordRequest})).status()).toBe(403);
 const discordSaved=await page.request.put('/api/admin/discord',{headers:{'X-CSRF-TOKEN':session.csrf,Origin:'http://localhost:8793'},data:discordRequest});expect(discordSaved.status()).toBe(200);
 expect(await discordSaved.text()).not.toContain(discordToken);
 expect(await readFile(root+'/security/discord/settings.json','utf8')).not.toContain(discordToken);
 const discordGet=await page.request.get('/api/admin/discord');expect(discordGet.headers()['cache-control']).toContain('no-store');expect((await discordGet.json()).tokenConfigured).toBe(true);
 await page.getByRole('button',{name:'계정과 보안',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Discord 봇 · BETA'})).toBeVisible();
 await expect(page.getByLabel('봇 토큰',{exact:true})).toHaveValue('');
 await expect(page.getByLabel('관리할 Minecraft 서버')).toHaveValue(id);

 const raw='\tat TRANSFORMER/minecraft@1.21.1/net.minecraft.client.gui.screens.Screen.wrapScreenError(Screen.java:451) ~[client.jar] {pl:mixin:APP:balm.mixins.json:ScreenAccessor from mod balm}\n';
 await writeFile(root+'/'+id+'/console.log',raw.repeat(301));
 await page.reload();await expect(page.getByLabel('로그 파일',{exact:true})).toContainText('console.log');await page.getByLabel('로그 파일',{exact:true}).selectOption('console.log');
 await expect(page.getByText('300줄 로드됨')).toBeVisible();await expect(page.locator('.virtual-log').first()).toContainText('Screen.wrapScreenError()');
 await page.locator('.virtual-log').first().click();await expect(page.getByRole('dialog',{name:'로그 상세'}).getByText('balm',{exact:true})).toBeVisible();await page.getByRole('button',{name:'닫기',exact:true}).click();
 await page.getByRole('button',{name:'다음 로그 300줄'}).click();await expect(page.getByText('1줄 로드됨')).toBeVisible();
 const response=await page.request.get(`/api/instances/${id}/log-file-page?path=console.log`);expect(response.status()).toBe(200);expect(response.headers()['cache-control']).toContain('no-store');expect((await response.json()).events[0].text+'\n').toBe(raw);
 expect((await page.request.get(`/api/instances/${id}/log-file-page?path=../security/accounts.json`)).status()).toBe(400);
 expect(await readFile(root+'/'+id+'/console.log','utf8')).toBe(raw.repeat(301));
 const invitation=await page.request.post('/api/admin/invitations',{headers:{'X-CSRF-TOKEN':session.csrf,Origin:'http://localhost:8793'},data:{name:'Log viewer',permissions:{[id]:'VIEWER'}}});expect(invitation.ok()).toBe(true);
 const viewer=await browser.newContext(),viewerPage=await viewer.newPage();
 try {
  const device=await viewer.newCDPSession(viewerPage);await device.send('WebAuthn.enable');await device.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
  await viewerPage.goto('http://localhost:8793/');await viewerPage.getByLabel('등록 코드').fill((await invitation.json()).token);await viewerPage.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(viewerPage.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
  expect((await viewer.request.get(`http://localhost:8793/api/instances/${id}/log-file-page?path=console.log`)).status()).toBe(200);
  expect((await viewer.request.get('http://localhost:8793/api/instances/not-permitted/log-files')).status()).toBe(403);
  expect((await viewer.request.get('http://localhost:8793/api/admin/discord')).status()).toBe(403);
  const viewerSession=await (await viewer.request.get('http://localhost:8793/api/auth/session')).json();
  expect((await viewer.request.put('http://localhost:8793/api/admin/discord',{headers:{'X-CSRF-TOKEN':viewerSession.csrf,Origin:'http://localhost:8793'},data:discordRequest})).status()).toBe(403);
 }finally{await viewer.close();}

});
