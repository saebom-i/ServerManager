import {test,expect} from '@playwright/test';
import {readFile,access,realpath} from 'node:fs/promises';
test('installed app switches profile folder without moving data and persists update preference',async({page,context})=>{
 const home=process.env.SM_DESKTOP_HOME||'';expect(home.startsWith('/tmp/servermanager-desktop.')).toBe(true);
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(home+'/profiles/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();
 await expect(page.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 const before=await readFile(home+'/profiles/security/accounts.json','utf8');
 await page.getByRole('button',{name:'계정과 보안',exact:true}).click();
 const toggle=page.getByLabel('자동 업데이트',{exact:true});await expect(toggle).toBeEnabled();await expect(toggle).toBeChecked();await toggle.uncheck();await expect(toggle).not.toBeChecked();await expect(toggle).toBeEnabled();await access(home+'/updates-disabled');
 const selected=(await realpath(home))+'/Different profiles & worlds';await page.getByLabel('프로필 폴더 절대 경로').fill(selected);
 page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'저장 위치 변경',exact:true}).click();
 await expect.poll(async()=>{try{return (await readFile(home+'/data-location.txt','utf8')).trim();}catch{return '';}},{timeout:30000}).toBe(selected);
 await expect.poll(async()=>{try{await access(selected+'/security/setup-code.txt');return true;}catch{return false;}},{timeout:30000}).toBe(true);
 await expect.poll(async()=>{try{return (await page.request.get('/api/auth/session')).status();}catch{return 0;}},{timeout:30000}).toBe(200);
 await page.goto('/');await expect(page.getByRole('heading',{name:'소유자 등록',exact:true})).toBeVisible();
 expect(await readFile(home+'/profiles/security/accounts.json','utf8')).toBe(before);
 expect(await readFile(home+'/config/application.properties','utf8')).toContain('server-manager.public-origin=');
});
