#!/usr/bin/env python3
"""Real GitHub download/update test. Only the fixture's version metadata is lowered;
application/update code and pinned release public key are unmodified. Never uses the live profile."""
import pathlib,tempfile,subprocess,os,zipfile,shutil,time,urllib.request,socket
root=pathlib.Path(__file__).resolve().parent.parent
stage=root/'build/macos-package/root/Applications/Server Manager.app/Contents/Resources'
with socket.socket() as sock:sock.bind(('127.0.0.1',8794))
with tempfile.TemporaryDirectory(prefix='servermanager-update.',dir='/tmp') as tmp:
 home=pathlib.Path(tmp);app=home/'Fixture.app';resources=app/'Contents/Resources';resources.mkdir(parents=True);(app/'Contents/MacOS').mkdir()
 shutil.copy2(root/'packaging/macos/ServerManager',app/'Contents/MacOS/ServerManager')
 for name in ['bootstrap.jar','update-public-key.der','application.properties.example','choose-profile.applescript']:shutil.copy2(stage/name,resources/name)
 (resources/'runtimes').symlink_to(stage/'runtimes',target_is_directory=True)
 with zipfile.ZipFile(stage/'server-manager.jar') as src,zipfile.ZipFile(resources/'server-manager.jar','w') as out:
  for item in src.infolist():out.writestr(item,b'0.3.99-beta.1' if item.filename=='BOOT-INF/classes/app-version.txt' else src.read(item.filename))
 (resources/'version.txt').write_text('0.3.99-beta.1')
 env=os.environ.copy();env.update(SERVER_MANAGER_HOME=tmp,SERVER_MANAGER_DATA=str(home/'data'),SM_UPDATE_HOME=tmp,SM_UPDATE_TARGET=(root/'VERSION').read_text().strip())
 proc=subprocess.Popen([str(app/'Contents/MacOS/ServerManager'),'--service','--server.port=8794','--server-manager.public-origin=http://localhost:8794'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 try:
  for _ in range(60):
   if proc.poll() is not None:raise RuntimeError('Fixture launcher stopped')
   try:
    with urllib.request.urlopen('http://localhost:8794/api/auth/session',timeout=2):break
   except OSError:time.sleep(1)
  else:raise RuntimeError('Fixture did not start')
  subprocess.run(['npx','playwright','test','--config','playwright.updates.config.ts'],cwd=root/'frontend',env=env,check=True)
 except Exception:
  log=home/'logs/app.log'
  if log.exists():
   dest=root/'build/update-protocol-test.log';shutil.copy2(log,dest);print('Diagnostic log retained:',dest,flush=True)
  raise
 finally:
  proc.terminate();proc.wait(timeout=60)
