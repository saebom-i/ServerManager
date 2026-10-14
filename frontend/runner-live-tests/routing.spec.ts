import {test,expect} from '@playwright/test';
import {readFile,writeFile,access,open,cp,mkdir,rename,rm,readdir,realpath} from 'node:fs/promises';
import {spawn, type ChildProcess} from 'node:child_process';
import net from 'node:net';

function varint(n:number){const bytes:number[]=[];do{let b=n&127;n>>>=7;if(n)b|=128;bytes.push(b);}while(n);return Buffer.from(bytes);}
async function freePort(){const socket=net.createServer();await new Promise<void>(r=>socket.listen(0,'127.0.0.1',r));const port=(socket.address() as net.AddressInfo).port;await new Promise<void>(r=>socket.close(()=>r()));return port;}
async function query(port:number,host:string){return new Promise<any>((resolve,reject)=>{
 const socket=net.connect(port,'127.0.0.1');let bytes=Buffer.alloc(0);let done=false;
 const finish=(error?:Error,value?:any)=>{if(done)return;done=true;socket.destroy();error?reject(error):resolve(value);};
 socket.setTimeout(10000,()=>finish(Error('Status query timeout: '+host)));socket.on('error',e=>finish(Error(`Status ${host}:${port}: ${e.message}`)));socket.on('end',()=>{if(!done)finish(Error('Route rejected: '+host));});
 socket.on('connect',()=>{const h=Buffer.from(host);const p=Buffer.concat([varint(0),varint(767),varint(h.length),h,Buffer.from([port>>8,port&255]),varint(1)]);socket.write(Buffer.concat([varint(p.length),p,Buffer.from([1,0])]));});
 socket.on('data',chunk=>{bytes=Buffer.concat([bytes,chunk]);let offset=0;function read(){let n=0;for(let i=0;i<5;i++){if(offset>=bytes.length)throw Error('partial');const b=bytes[offset++];n|=(b&127)<<(7*i);if(!(b&128))return n;}throw Error('bad varint');}try{const length=read();if(bytes.length-offset<length)return;expect(read()).toBe(0);const size=read();finish(undefined,JSON.parse(bytes.subarray(offset,offset+size).toString()));}catch(e){if((e as Error).message!=='partial')finish(e as Error);}});
 });}
test('real HTTPS manager, two signed runner sessions, shared TCP routing, safe failed start and stop handoff',async({page,context,browser})=>{
 const home=process.env.SM_RUNNERS_TEST_HOME||'';expect(home.startsWith('/tmp/servermanager-runners.')).toBe(true);
 const java=process.env.SM_RUNNERS_TEST_JAVA!,jar=process.env.SM_RUNNERS_TEST_JAR!;
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(home+'/profiles/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'서버 실행기',exact:true})).toBeVisible();
 const session=await (await page.request.get('/api/auth/session')).json();
 async function api(path:string,method='GET',data?:any){const r=await page.request.fetch('/api'+path,{method,data,headers:{'X-CSRF-TOKEN':session.csrf}});expect(r.status(),`${method} ${path}: ${await r.text()}`).toBeLessThan(300);return r.headers()['content-type']?.includes('json')?r.json():null;}
 const shared=await freePort(),ids:string[]=[],runners:ChildProcess[]=[],runnerHomes:string[]=[],devices:string[]=[];
 const originalFolder=home+'/original Minecraft server';
 const trust=['-Djavax.net.ssl.trustStore='+home+'/tls.p12','-Djavax.net.ssl.trustStorePassword=fixture-only-password'];
 async function state(id:string){return (await api('/instances')).find((i:any)=>i.config.id===id)?.state;}
 async function waitState(id:string,wanted:string){await expect.poll(()=>state(id),{timeout:180000,intervals:[1000,2000]}).toBe(wanted);}
 try {
  await api('/admin/game-network','PUT',{revision:0,config:{enabled:true,port:shared,baseDomain:'server.example.com',targetHost:'gateway.example.com',defaultInstanceId:null,defaultHostname:'',labels:{},cloudflareEnabled:false,zoneId:''}});
  for(const [index,loader] of ['Vanilla','Fabric'].entries()){
   const version=loader==='Vanilla'?'1.21.1':(await api('/version-catalog?loader=Fabric&minecraft=1.21.1')).versions[0];
   const row=await api('/instances','POST',{name:'Fixture '+loader,loader,minecraft:'1.21.1',loaderVersion:version,port:await freePort(),memoryMb:1024,javaPath:java});const id=row.config.id;ids.push(id);
   await api('/instances/'+id+'/install','POST');await waitState(id,'STOPPED');
   const content=`server-ip=127.0.0.1\nserver-port=${row.config.port}\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nonline-mode=true\nmotd=Fixture-${index}\n`;
   await api('/instances/'+id+'/text','PUT',{path:'server.properties',content});
   await api('/instances/'+id+'/game-domain','PUT',{label:index?'beta':'alpha'});
   const runnerHome=home+'/runner-'+index;runnerHomes.push(runnerHome);const env={...process.env,SERVER_MANAGER_RUNNER_HOME:runnerHome};
   if(index===0) {
    await cp(home+'/profiles/'+id+'/server',originalFolder,{recursive:true});
    await rename(originalFolder+'/server.jar',originalFolder+'/custom-vanilla.jar');
    await rm(originalFolder+'/server-manager-runtime.json');
    await writeFile(originalFolder+'/original-folder-proof.txt','must remain in the original folder\n');
    await mkdir(runnerHome+'/instances/'+id,{recursive:true});
    await writeFile(runnerHome+'/instances/'+id+'/server-folder.properties',`path=${await realpath(originalFolder)}\ninitial=true\njar=custom-vanilla.jar\nrequiredJava=21\n`);
   }
   const code=(await api('/runners/enroll','POST',{name:'Live fixture '+index})).code;
   const pair=spawn(java,[...trust,'-jar',jar,'--runner-pair','--manager-url=https://localhost:8796'],{env,stdio:['pipe','ignore','pipe']});pair.stdin!.end(code+'\n');
   await new Promise<void>((resolve,reject)=>{let error='';pair.stderr!.on('data',d=>error+=d);pair.once('exit',n=>n===0?resolve():reject(Error('Pairing failed: '+error)));pair.once('error',reject);});
   const device=JSON.parse(await readFile(runnerHome+'/identity.json','utf8')).id;
   devices.push(device);
   await api('/runners/'+device+'/allow/'+id,'PUT');await api('/instances/'+id+'/runner','PUT',{runnerId:device});
   const log=await open(runnerHome+'/test.log','w');const runner=spawn(java,[...trust,'-jar',jar,'--runner'],{env,stdio:['ignore',log.fd,log.fd]});runners.push(runner);await log.close();
   await expect.poll(async()=> (await api('/runners')).find((d:any)=>d.id===device)?.online,{timeout:30000}).toBe(true);
  }
  // Import an already-bound original folder before launching any Minecraft process.
  await api('/instances/'+ids[0]+'/import-folder','POST');await waitState(ids[0],'STOPPED');
  const imported=(await api('/instances')).find((r:any)=>r.config.id===ids[0]);
  expect(imported.folderImport.phase).toBe('COMPLETE');expect(imported.pid).toBeNull();
  expect(await readFile(home+'/profiles/'+ids[0]+'/server/original-folder-proof.txt','utf8')).toContain('original folder');
  expect(await access(runnerHomes[0]+'/instances/'+ids[0]+'/console.log').then(()=>true,()=>false)).toBe(false);
  await access(home+'/profiles/'+ids[0]+'/server/custom-vanilla.jar');
  // A busy internal port must fail before a JVM starts and release only this instance's lease.
  const row=(await api('/instances')).find((r:any)=>r.config.id===ids[0]);const blocker=net.createServer();
  await new Promise<void>(r=>blocker.listen(row.config.port,'127.0.0.1',r));
  try{await api('/instances/'+ids[0]+'/start','POST');await waitState(ids[0],'FAILED');expect(await access(home+'/profiles/'+ids[0]+'/remote-lease').then(()=>true,()=>false)).toBe(false);}finally{await new Promise<void>(r=>blocker.close(()=>r()));}
  for(const id of ids){await api('/instances/'+id+'/start','POST');await waitState(id,'RUNNING');}
  await api('/instances/'+ids[0]+'/power-saving','PUT',{enabled:true,minutes:30});
  await expect.poll(async()=> (await api('/instances/'+ids[0]+'/power-saving')).status,{timeout:30000}).toBe('EMPTY');
  expect((await api('/instances/'+ids[0]+'/power-saving')).remainingSeconds).toBeGreaterThan(1700);
  const invitation=await api('/admin/invitations','POST',{name:'Power viewer fixture',permissions:{[ids[0]]:'VIEWER'}});
  const viewerContext=await browser.newContext({baseURL:'https://localhost:8796',ignoreHTTPSErrors:true});
  try {
   const viewer=await viewerContext.newPage();const viewerCdp=await viewerContext.newCDPSession(viewer);await viewerCdp.send('WebAuthn.enable');await viewerCdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
   await viewer.goto('/');await viewer.getByLabel('등록 코드').fill(invitation.token);await viewer.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(viewer.getByRole('button',{name:'서버 실행기',exact:true})).toBeVisible();
   const viewerSession=await (await viewer.request.get('/api/auth/session')).json();
   expect((await viewer.request.get('/api/instances/'+ids[0]+'/power-saving')).status()).toBe(200);
   expect((await viewer.request.put('/api/instances/'+ids[0]+'/power-saving',{data:{enabled:false,minutes:30},headers:{'X-CSRF-TOKEN':viewerSession.csrf}})).status()).toBe(403);
   expect((await viewer.request.get('/api/instances/'+ids[1]+'/power-saving')).status()).toBe(403);
   expect((await viewer.request.post('/api/instances/'+ids[0]+'/import-folder',{headers:{'X-CSRF-TOKEN':viewerSession.csrf}})).status()).toBe(403);
  } finally {await viewerContext.close();}
  expect((await page.request.put('/api/instances/'+ids[0]+'/power-saving',{data:{enabled:false,minutes:30}})).status()).toBe(403);
  const invalid=await page.request.put('/api/instances/'+ids[0]+'/power-saving',{data:{enabled:true,minutes:1},headers:{'X-CSRF-TOKEN':session.csrf}});expect(invalid.status()).toBe(400);
  await api('/instances/'+ids[0]+'/power-saving','PUT',{enabled:false,minutes:30});
  for(const row of await api('/instances'))expect(JSON.stringify((await query(row.config.port,'localhost')).description)).toContain('Fixture-');
  console.log('Both Minecraft processes respond directly. Querying shared TCP '+shared);
  expect(JSON.stringify((await query(shared,'alpha.server.example.com')).description)).toContain('Fixture-0');
  expect(JSON.stringify((await query(shared,'beta.server.example.com')).description)).toContain('Fixture-1');
  await expect(query(shared,'unknown.server.example.com')).rejects.toThrow();
  await writeFile(originalFolder+'/handoff-proof.txt','changed only after remote start\n');
  await api('/instances/'+ids[0]+'/command','POST',{command:'list'});
  await api('/instances/'+ids[0]+'/stop','POST');await waitState(ids[0],'STOPPED');
  expect(await readFile(home+'/profiles/'+ids[0]+'/server/handoff-proof.txt','utf8')).toContain('changed only');
  await access(home+'/profiles/'+ids[0]+'/server/world/level.dat');
  expect(await access(home+'/profiles/'+ids[0]+'/remote-lease').then(()=>true,()=>false)).toBe(false);
  expect(await readFile(originalFolder+'/original-folder-proof.txt','utf8')).toContain('original folder');
  expect(await access(runnerHomes[0]+'/instances/'+ids[0]+'/server').then(()=>true,()=>false)).toBe(false);
  // The original path remains stable; later central changes pull into it with a sibling backup.
  await api('/instances/'+ids[0]+'/text','PUT',{path:'central-edit.txt',content:'central change after first upload'});
  await api('/instances/'+ids[0]+'/start','POST');await waitState(ids[0],'RUNNING');
  expect(await readFile(originalFolder+'/central-edit.txt','utf8')).toContain('central change');
  expect((await readdir(home+'/.servermanager-history-'+ids[0])).some(name=>name.startsWith('delta-'))).toBe(true);
  await api('/instances/'+ids[0]+'/stop','POST');await waitState(ids[0],'STOPPED');
  expect(JSON.stringify((await query(shared,'beta.server.example.com')).description)).toContain('Fixture-1');
  await expect(query(shared,'alpha.server.example.com')).rejects.toThrow();
  await api('/instances/'+ids[1]+'/stop','POST');await waitState(ids[1],'STOPPED');await access(home+'/profiles/'+ids[1]+'/server/world/level.dat');
  // Custom entryJar metadata follows the world to a normal second runner.
  await api('/runners/'+devices[1]+'/allow/'+ids[0],'PUT');await api('/instances/'+ids[0]+'/runner','PUT',{runnerId:devices[1]});
  await api('/instances/'+ids[0]+'/start','POST');await waitState(ids[0],'RUNNING');
  expect(JSON.stringify((await query(shared,'alpha.server.example.com')).description)).toContain('Fixture-0');
  await access(runnerHomes[1]+'/instances/'+ids[0]+'/server/custom-vanilla.jar');
  await api('/instances/'+ids[0]+'/stop','POST');await waitState(ids[0],'STOPPED');
  const accounts=JSON.parse(await readFile(home+'/profiles/security/accounts.json','utf8'));expect(JSON.stringify(accounts)).not.toContain('fixture-only-password');
  console.log('PASS: original-folder direct execution and stable-path restart/backup, custom JAR migration, passkey enrollment, signed WSS runners, shared TCP isolation, busy-port recovery, console and normal save/file handoff.');
 } finally {
  let safe=true;
  for(const id of ids){try{const current=await state(id);if(['RUNNING','STARTING'].includes(current)){await api('/instances/'+id+'/stop','POST');await waitState(id,'STOPPED');}else if(['STOPPING','SYNCING','TRANSFERRING','UNKNOWN','SYNC_FAILED'].includes(current)){await waitState(id,'STOPPED');}}catch{safe=false;}}
  // Never kill a worker with a possibly active test world.
  if(safe)for(const runner of runners){runner.kill('SIGTERM');await new Promise<void>(resolve=>{if(runner.exitCode!==null)resolve();else runner.once('exit',()=>resolve());});}
  else console.error('Test cleanup needs inspection: workers kept alive to avoid forced world shutdown. '+home);
 }
});
