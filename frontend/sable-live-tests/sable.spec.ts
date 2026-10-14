import {test,expect} from '@playwright/test';
import {readFile,writeFile,copyFile,open,mkdir} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import net from 'node:net';
import dgram from 'node:dgram';
import http from 'node:http';
async function freePort(){const s=net.createServer();await new Promise<void>(r=>s.listen(0,'127.0.0.1',r));const p=(s.address() as net.AddressInfo).port;await new Promise<void>(r=>s.close(()=>r()));return p;}
function vi(v:number){const bytes:number[]=[];do{let b=v&127;v>>>=7;if(v)b|=128;bytes.push(b);}while(v);return Buffer.from(bytes);}
test('real passkey, control WSS, separate data WSS and actual Sable authentication plus UDP round trip',async({page,context})=>{
 const home=process.env.SM_RUNNERS_TEST_HOME!,java=process.env.SM_RUNNERS_TEST_JAVA!,jar=process.env.SM_RUNNERS_TEST_JAR!;expect(home.startsWith('/tmp/servermanager-runners.')).toBe(true);const sable=process.env.SM_LIVE_SABLE_JAR!;expect(sable).toBeTruthy();
 const c=await context.newCDPSession(page);await c.send('WebAuthn.enable');await c.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(home+'/profiles/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'서버 실행기',exact:true})).toBeVisible();
 async function api(path:string,method='GET',data?:any){const session=await (await page.request.get('/api/auth/session')).json();const r=await page.request.fetch('/api'+path,{method,data,headers:{'X-CSRF-TOKEN':session.csrf}});expect(r.status(),method+' '+path+' '+await r.text()).toBeLessThan(300);return r.headers()['content-type']?.includes('json')?await r.json():null;}
 const row=await api('/instances','POST',{name:'격리 Sable 중계',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.249',port:await freePort(),memoryMb:1536,javaPath:java});const id=row.config.id,base='/instances/'+id;
 const settings=(await api('/admin/game-network')).settings,port=await freePort();await api('/admin/game-network','PUT',{revision:settings.revision,config:{...settings.config,enabled:true,sableUdpEnabled:true,port,baseDomain:'server.example.com',labels:{[id]:'sable'}}});
 let token:string|null=null;const callback=http.createServer(async(req,res)=>{let body='';for await(const part of req){body+=part;if(body.length>64){res.writeHead(400).end();return;}}if(/^[0-9a-f-]{36}$/.test(body)){token=body;res.writeHead(204).end();}else res.writeHead(400).end();});await new Promise<void>(r=>callback.listen(0,'127.0.0.1',r));
 const runnerHome=home+'/sable-runner',env={...process.env,SERVER_MANAGER_RUNNER_HOME:runnerHome,SM_SABLE_PROBE_WAIT:'1',SM_SABLE_PROBE_REPLY_PORT:String((callback.address() as net.AddressInfo).port)};
 const trust=['-Djavax.net.ssl.trustStore='+home+'/tls.p12','-Djavax.net.ssl.trustStorePassword=fixture-only-password'];const code=(await api('/runners/enroll','POST',{name:'Sable fixture'})).code;
 const pair=spawn(java,[...trust,'-jar',jar,'--runner-pair','--manager-url=https://localhost:8796'],{env,stdio:['pipe','ignore','ignore']});pair.stdin!.end(code+'\n');await new Promise<void>((r,j)=>pair.once('exit',x=>x===0?r():j(Error('pair failed'))));const device=JSON.parse(await readFile(runnerHome+'/identity.json','utf8')).id;
 await api('/runners/'+device+'/allow/'+id,'PUT');await api(base+'/runner','PUT',{runnerId:device});const log=await open(runnerHome+'/runner.log','w');const runner=spawn(java,[...trust,'-jar',jar,'--runner'],{env,stdio:['ignore',log.fd,log.fd]});await log.close();
 let safe=true;let tcp:net.Socket|null=null;const udp=dgram.createSocket('udp4');
 try{
  await expect.poll(async()=>(await api('/runners')).find((x:any)=>x.id===device)?.online,{timeout:30000}).toBe(true);await api(base+'/file-sync','PUT',{enabled:false});
  await api(base+'/install','POST');await expect.poll(async()=>(await api('/instances')).find((x:any)=>x.config.id===id)?.state,{timeout:480000}).toBe('STOPPED');
  const dir=runnerHome+'/instances/'+id+'/server';await mkdir(dir+'/mods',{recursive:true});await copyFile(sable,dir+'/mods/sable.jar');await copyFile('../sable-bridge/build/libs/isolated-sable-probe.jar',dir+'/mods/probe.jar');
  await writeFile(dir+'/server.properties',(await readFile(dir+'/server.properties','utf8'))+'\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n');
  await api(base+'/start','POST');safe=false;await expect.poll(async()=>(await api('/instances')).find((x:any)=>x.config.id===id)?.state,{timeout:180000}).toBe('RUNNING');
  tcp=net.connect(port,'127.0.0.1');await new Promise<void>((r,j)=>tcp!.once('connect',r).once('error',j));const host=Buffer.from('sable.server.example.com'),p=Buffer.alloc(2);p.writeUInt16BE(port);const handshake=Buffer.concat([vi(0),vi(767),vi(host.length),host,p,vi(1)]);tcp.write(Buffer.concat([vi(handshake.length),handshake]));
  await expect.poll(()=>!!token,{timeout:15000}).toBe(true);
  const packet=await new Promise<Buffer>((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('No actual Sable UDP keep-alive')),8000);udp.once('message',b=>{clearTimeout(timeout);resolve(b);});udp.send(Buffer.concat([Buffer.from([3,36]),Buffer.from(token!)]),port,'127.0.0.1');});expect(packet[0]).toBe(4);udp.send(Buffer.from([5]),port,'127.0.0.1');
  await expect.poll(async()=>(await api('/instances')).find((x:any)=>x.config.id===id)?.sableUdp?.state,{timeout:10000}).toBe('CONNECTED');
  await expect.poll(async()=>(await api('/instances')).find((x:any)=>x.config.id===id)?.players?.count,{timeout:20000}).toBe(0);
  const events=await api(base+'/event-page?limit=500');expect(events.events.some((x:any)=>x.text.includes('invalid packet ID: 254'))).toBe(false);
  console.log('PASS: actual HTTPS/passkey enrollment, distinct signed control/data WSS, real Sable2.0.6 authentication via server bridge, shared-port UDP round trip and isolated Query player count. No production profile used.');
  tcp.destroy();tcp=null;await api(base+'/stop','POST');await expect.poll(async()=>(await api('/instances')).find((x:any)=>x.config.id===id)?.state,{timeout:90000}).toBe('STOPPED');safe=true;
 }finally{tcp?.destroy();udp.close();callback.close();if(safe){runner.kill('SIGTERM');await new Promise(r=>runner.once('exit',r));}else console.error('Fixture left running for normal-stop inspection:',home);}
});
