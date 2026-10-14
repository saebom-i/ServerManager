import {test,expect} from '@playwright/test';
import {readFile,writeFile,copyFile,mkdir,open,readdir,stat,realpath} from 'node:fs/promises';
import {spawn,execFile,type ChildProcess} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash} from 'node:crypto';
import https from 'node:https';
import tls from 'node:tls';
import net from 'node:net';
const exec=promisify(execFile);

test('real web request pauses an upload, signed GitHub update restarts supervisor and preserves enrollment and resume',async({page,context})=>{
 const home=process.env.SM_RUNNERS_TEST_HOME!,java=process.env.SM_RUNNERS_TEST_JAVA!,jar=process.env.SM_RUNNERS_TEST_JAR!;expect(home.startsWith('/tmp/servermanager-runners.')).toBe(true);
 const target=process.env.SM_RUNNERS_UPDATE_TARGET||'0.4.3',fake='0.3.99-beta.1';
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(home+'/profiles/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'서버 실행기',exact:true})).toBeVisible();
 const session=await (await page.request.get('/api/auth/session')).json();
 async function api(path:string,method='GET',data?:any){const r=await page.request.fetch('/api'+path,{method,data,headers:{'X-CSRF-TOKEN':session.csrf}});expect(r.status(),method+' '+path+' '+await r.text()).toBeLessThan(300);return r.headers()['content-type']?.includes('json')?r.json():null;}
 const proxy=https.createServer({pfx:await readFile(home+'/tls.p12'),passphrase:'fixture-only-password'},async(req,res)=>{
  const parts:Buffer[]=[];for await(const part of req)parts.push(part);const bytes=Buffer.concat(parts);
  if(req.url?.includes('/chunk')||req.url?.includes('/batch'))await new Promise(r=>setTimeout(r,500));
  const outgoing=https.request({hostname:'127.0.0.1',servername:'localhost',port:8796,path:req.url,method:req.method,rejectUnauthorized:false,headers:{...req.headers,host:'localhost:8796'}},incoming=>{res.writeHead(incoming.statusCode!,incoming.headers);incoming.pipe(res);});
  outgoing.on('error',()=>{if(!res.headersSent){res.writeHead(502);res.end();}else res.destroy();});outgoing.end(bytes);
 });
 const sockets=new Set<net.Socket>();proxy.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));});
 proxy.on('upgrade',(req,socket,head)=>{
  const upstream=tls.connect({host:'127.0.0.1',servername:'localhost',port:8796,rejectUnauthorized:false},()=>{
   const headers=req.rawHeaders.slice();for(let n=0;n<headers.length;n+=2)if(headers[n].toLowerCase()==='host')headers[n+1]='localhost:8796';
   upstream.write(`${req.method} ${req.url} HTTP/1.1\r\n`+headers.reduce((s,v,n)=>n%2?s+v+'\r\n':s+v+': ','')+'\r\n');if(head.length)upstream.write(head);socket.pipe(upstream);upstream.pipe(socket);
  });upstream.on('error',()=>socket.destroy());socket.on('error',()=>upstream.destroy());socket.on('close',()=>upstream.destroy());
 });
 await new Promise<void>(r=>proxy.listen(0,'127.0.0.1',r));const port=(proxy.address() as net.AddressInfo).port;
 const packageHome=home+'/package',runnerHome=home+'/runner',original=home+'/original';let runner:ChildProcess|undefined;
 try {
  await mkdir(packageHome);await mkdir(original);await mkdir(runnerHome);
  await exec('python3',['-c',`import zipfile,sys\nwith zipfile.ZipFile(sys.argv[1]) as src,zipfile.ZipFile(sys.argv[2],'w') as out:\n for item in src.infolist():out.writestr(item,b'${fake}' if item.filename=='BOOT-INF/classes/app-version.txt' else src.read(item.filename))`,jar,packageHome+'/server-manager.jar']);
  await copyFile('../build/runner-package/ServerManagerRunner-mac/runner-bootstrap.jar',packageHome+'/runner-bootstrap.jar');await copyFile('../src/main/resources/update-public-key.der',packageHome+'/update-public-key.der');await writeFile(packageHome+'/version.txt',fake);
  const javaHome=java.substring(0,java.lastIndexOf('/bin/java')),keytool=javaHome+'/bin/keytool';
  await copyFile(javaHome+'/lib/security/cacerts',home+'/combined-trust');
  await exec(keytool,['-exportcert','-alias','fixture','-keystore',home+'/tls.p12','-storepass','fixture-only-password','-file',home+'/fixture.cer']);
  await exec(keytool,['-importcert','-noprompt','-alias','local-fixture','-keystore',home+'/combined-trust','-storepass','changeit','-file',home+'/fixture.cer']);
  const options=`-Djavax.net.ssl.trustStore=${home}/combined-trust -Djavax.net.ssl.trustStorePassword=changeit`;
  const env={...process.env,SERVER_MANAGER_RUNNER_HOME:runnerHome,RUNNER_PACKAGE_HOME:packageHome,JAVA_TOOL_OPTIONS:options};
  const row=await api('/instances','POST',{name:'Remote update fixture',loader:'Vanilla',minecraft:'1.21.1',loaderVersion:'1.21.1',port:25579,memoryMb:512,javaPath:java});const id=row.config.id;
  await writeFile(original+'/server.properties','level-name=world\n');await writeFile(original+'/custom.jar','never executed; this fixture tests import only');
  const file=await open(original+'/payload.bin','w');try{const block=Buffer.alloc(1024*1024,37);for(let n=0;n<256;n++)await file.write(block);}finally{await file.close();}
  await mkdir(runnerHome+'/instances/'+id,{recursive:true});await writeFile(runnerHome+'/instances/'+id+'/server-folder.properties',`path=${await realpath(original)}\ninitial=true\njar=custom.jar\nrequiredJava=21\n`);
  const code=(await api('/runners/enroll','POST',{name:'Update fixture'})).code;
  const pair=spawn(java,['-jar',packageHome+'/server-manager.jar','--runner-pair','--manager-url=https://localhost:'+port],{env,stdio:['pipe','ignore','pipe']});pair.stdin!.end(code+'\n');await new Promise<void>((resolve,reject)=>pair.once('exit',n=>n===0?resolve():reject(Error('Fixture pair failed'))));
  const identity=await readFile(runnerHome+'/identity.json');const device=JSON.parse(identity.toString()).id;
  await api('/runners/'+device+'/allow/'+id,'PUT');await api('/instances/'+id+'/runner','PUT',{runnerId:device});
  const log=await open(home+'/runner-bootstrap.log','w');runner=spawn(java,['-jar',packageHome+'/runner-bootstrap.jar'],{env,stdio:['ignore',log.fd,log.fd]});await log.close();
  await expect.poll(async()=> (await api('/runners')).find((r:any)=>r.id===device)?.online,{timeout:30000,intervals:[20,50,100]}).toBe(true);
  await api('/instances/'+id+'/import-folder','POST');
  const cacheRoot=home+'/profiles/'+id+'/runner-transfer';let tid='';
  await expect.poll(async()=>{try{tid=(await readdir(cacheRoot)).find(n=>/^[a-f0-9-]{36}$/.test(n))||'';return tid&&await stat(cacheRoot+'/'+tid+'/payload.bin').then(s=>s.size>=4*1024*1024,()=>false);}catch{return false;}},{timeout:60000,intervals:[50,100]}).toBeTruthy();
  await expect.poll(async()=> (await api('/runners')).find((r:any)=>r.id===device)?.remoteUpdateSupported,{timeout:20000}).toBe(true);
  await page.getByRole('button',{name:'서버 실행기',exact:true}).click();const controls=page.getByLabel('Update fixture 업데이트',{exact:true});
  await expect(controls.getByRole('button',{name:'전송 잠시 멈추고 업데이트',exact:true})).toBeEnabled();await controls.getByRole('button',{name:'전송 잠시 멈추고 업데이트',exact:true}).click();
  await expect.poll(async()=> (await api('/runners')).find((r:any)=>r.id===device)?.version,{timeout:180000,intervals:[1000]}).toBe(target);
  expect((await readFile(runnerHome+'/updates/active.txt','utf8')).trim()).toBe(target);expect((await readFile(runnerHome+'/updates/previous.txt','utf8')).trim()).toBe(fake);
  await expect.poll(async()=> (await api('/instances')).find((r:any)=>r.config.id===id)?.state,{timeout:120000,intervals:[1000]}).toBe('STOPPED');
  expect(await readFile(runnerHome+'/identity.json')).toEqual(identity);
  await stat(home+'/profiles/'+id+'/transfer-receipts/'+tid+'.json');
  expect(createHash('sha256').update(await readFile(home+'/profiles/'+id+'/server/payload.bin')).digest('hex')).toBe(createHash('sha256').update(await readFile(original+'/payload.bin')).digest('hex'));
  expect(await readFile(runnerHome+'/instances/'+id+'/server-folder.properties','utf8')).toContain(await realpath(original));
  console.log('PASS: real web pause request, pinned GitHub signature download, supervisor exit75/restart, same identity/path/ticket/transfer ID, exact256MiB resume, no Minecraft launched.');
 } finally {
  if(runner?.exitCode===null){runner.kill('SIGTERM');await new Promise<void>(r=>runner!.once('exit',()=>r()));}
  proxy.closeAllConnections();for(const s of sockets)s.destroy();await new Promise<void>(r=>proxy.close(()=>r()));
 }
});
