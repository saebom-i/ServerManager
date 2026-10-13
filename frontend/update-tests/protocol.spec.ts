import {test,expect} from '@playwright/test';
import {readFile,access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
test('automatic BETA update downloads signed GitHub assets and restarts preserving accounts',async({page,context})=>{
 const target=process.env.SM_UPDATE_TARGET||'';expect(target).toMatch(/^0\.[0-9]+\.[0-9]+-beta\.[1-9][0-9]*$/);
 const home=process.env.SM_UPDATE_HOME||'';expect(home.startsWith('/tmp/servermanager-update.')).toBe(true);
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(home+'/data/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 const initial=await (await page.request.get('/api/auth/session')).json();expect(initial.appVersion).toBe('0.3.99-beta.1');
 const status=await (await page.request.get('/api/admin/updates')).json();expect(status.supported).toBe(true);expect(status.enabled).toBe(true);expect(status.channel).toBe('BETA');
 const accounts=await readFile(home+'/data/security/accounts.json');const config=await readFile(home+'/config/application.properties');
 // Do not call the manual check API: exercise the actual scheduled automatic check after startup.
 await expect.poll(async()=>{try{return (await (await page.request.get('/api/auth/session')).json()).appVersion;}catch{return 'restarting';}},{timeout:150000,intervals:[2000]}).toBe(target);
 expect(await readFile(home+'/data/security/accounts.json')).toEqual(accounts);expect(await readFile(home+'/config/application.properties')).toEqual(config);
 expect((await readFile(home+'/updates/active.txt','utf8')).trim()).toBe(target);
 expect((await readFile(home+'/updates/previous.txt','utf8')).trim()).toBe('0.3.99-beta.1');
 const dir=home+'/updates/'+target+'/';const manifest=await readFile(dir+'update.properties','utf8');const hash=/^sha256=(.+)$/m.exec(manifest)![1];
 expect(createHash('sha256').update(await readFile(dir+'server-manager-'+target+'.jar')).digest('hex')).toBe(hash);await access(dir+'update.sig');
 const after=await (await page.request.get('/api/auth/session')).json();expect(after.setupRequired).toBe(false);
 await page.goto('/');await page.getByRole('button',{name:'패스키로 로그인',exact:true}).click();await expect(page.getByRole('button',{name:'계정과 보안',exact:true})).toBeVisible();
 const latest=await (await page.request.get('/api/admin/updates')).json();expect(latest.current).toBe(target);expect(latest.supported).toBe(true);
});
