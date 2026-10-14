import {test,expect,type Page,type BrowserContext} from '@playwright/test';
import {readFile} from 'node:fs/promises';
async function auth(context:BrowserContext,page:Page){const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});}
async function write(page:Page,path:string,data:unknown,method='POST'){const session=await (await page.request.get('/api/auth/session')).json();return page.request.fetch(path,{method,data,headers:{'X-CSRF-TOKEN':session.csrf,Origin:'http://localhost:8797'}});}
test('real passkey viewer stores only own filter with CSRF and instance authorization enforced',async({page,context,browser,request})=>{
 const root=process.env.SM_FILTERS_HOME||'';expect(root.startsWith('/tmp/servermanager-filters.')).toBe(true);
 expect((await request.get('/api/log-filters/anything')).status()).toBe(401);
 await auth(context,page);await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(root+'/profiles/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 const health=await (await page.request.get('/api/health')).json();const ids:string[]=[];
 for(const [n,port] of [['Visible',31008],['Private',31009]] as const){const response=await write(page,'/api/instances',{name:n,loaderVersion:'21.1.249',port,memoryMb:512,javaPath:health.javaPath});expect(response.ok()).toBe(true);ids.push((await response.json()).config.id);}
 const invite=await write(page,'/api/admin/invitations',{name:'Filter viewer',permissions:{[ids[0]]:'VIEWER'}});expect(invite.ok()).toBe(true);const token=(await invite.json()).token;
 const guestContext=await browser.newContext({baseURL:'http://localhost:8797'});const guest=await guestContext.newPage();await auth(guestContext,guest);
 try {
  await guest.goto('/');await guest.getByLabel('등록 코드').fill(token);await guest.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(guest.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
  const rules={keywords:['noise'],mods:['create'],onlyDebug:true};const config={enabled:true,rules,presets:[{id:'saved-debug',name:'Debug noise',rules}],activeId:'saved-debug'};
  expect((await guest.request.put('/api/log-filters/'+ids[0],{data:{config,revision:0},headers:{Origin:'http://localhost:8797'}})).status()).toBe(403);
  expect((await write(guest,'/api/log-filters/'+ids[0],{config,revision:0},'PUT')).status()).toBe(200);
  expect((await write(guest,'/api/log-filters/'+ids[0],{config,revision:0},'PUT')).status()).toBe(409);
  expect((await guest.request.get('/api/log-filters/'+ids[1])).status()).toBe(403);
  expect((await write(guest,'/api/log-filters/'+ids[1],{config,revision:0},'PUT')).status()).toBe(403);
  expect((await (await page.request.get('/api/log-filters/'+ids[0])).json()).config.presets).toEqual([]);
  await guest.reload();expect((await (await guest.request.get('/api/log-filters/'+ids[0])).json()).config).toEqual(config);
  expect((await write(guest,'/api/instances/'+ids[0]+'/command',{command:'stop'})).status()).toBe(403);
 } finally {await guestContext.close();}
});
