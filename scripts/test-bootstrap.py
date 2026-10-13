#!/usr/bin/env python3
"""Isolated supervisor tests: signed upgrade, tamper rejection, rollback and profile selection."""
import pathlib,tempfile,subprocess,os,time,hashlib,shutil
ROOT=pathlib.Path(__file__).resolve().parent.parent
JAVA=pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip())/'bin'
SOURCE='''import java.nio.file.*; public class FakeServer { public static void main(String[] args)throws Exception {String v=System.getenv("SERVER_MANAGER_EXPECTED_VERSION");Path h=Path.of(System.getenv("SERVER_MANAGER_HOME"));if(Files.exists(h.resolve("fail-next")) && v.equals("0.4.0-beta.2"))System.exit(1);Files.writeString(Path.of(System.getenv("SERVER_MANAGER_READY_FILE")),"ready");Files.writeString(h.resolve("started-version"),v);if(v.equals("0.3.0") && Files.exists(h.resolve("updates/pending.txt"))){Thread.sleep(800);System.exit(75);}Thread.sleep(120000);}}'''
def wait_for(test,timeout=20):
 deadline=time.time()+timeout
 while time.time()<deadline:
  if test():return
  time.sleep(.15)
 raise RuntimeError('Timed out')
with tempfile.TemporaryDirectory(prefix='servermanager-bootstrap-') as name:
 root=pathlib.Path(name);app=root/'App & test.app';resources=app/'Contents/Resources';resources.mkdir(parents=True)
 classes=root/'classes';classes.mkdir();(root/'FakeServer.java').write_text(SOURCE)
 subprocess.run([str(JAVA/'javac'),'-d',str(classes),str(root/'FakeServer.java')],check=True)
 subprocess.run([str(JAVA/'jar'),'--create','--file',str(resources/'server-manager.jar'),'--main-class','FakeServer','-C',str(classes),'.'],check=True)
 subprocess.run([str(JAVA/'javac'),'--release','21','-d',str(classes),str(ROOT/'packaging/bootstrap/Bootstrap.java')],check=True)
 subprocess.run([str(JAVA/'jar'),'--create','--file',str(resources/'bootstrap.jar'),'--main-class','Bootstrap','-C',str(classes),'.'],check=True)
 (resources/'version.txt').write_text('0.3.0');(resources/'application.properties.example').write_text('server.port=8787\n')
 keydir=root/'keys';keydir.mkdir();subprocess.run([str(JAVA/'java'),str(ROOT/'packaging/bootstrap/Keygen.java'),str(keydir),str(resources/'update-public-key.der')],check=True)
 def prepare(home):
  folder=home/'updates/0.4.0-beta.2';folder.mkdir(parents=True);jar=folder/'server-manager-0.4.0-beta.2.jar';shutil.copy2(resources/'server-manager.jar',jar)
  manifest=f'protocol=1\nversion=0.4.0-beta.2\nfile={jar.name}\nsize={jar.stat().st_size}\nsha256={hashlib.sha256(jar.read_bytes()).hexdigest()}\n';(folder/'update.properties').write_text(manifest)
  subprocess.run([str(JAVA/'java'),str(ROOT/'packaging/bootstrap/SignRelease.java'),str(keydir/'private.der'),str(folder/'update.properties'),str(folder/'update.sig')],check=True)
  (home/'updates/pending.txt').write_text('0.4.0-beta.2');return jar
 def launch(home):
  env=os.environ.copy();env.update(SERVER_MANAGER_APP_PATH=str(app),SERVER_MANAGER_HOME=str(home));env.pop('SERVER_MANAGER_DATA',None)
  return subprocess.Popen([str(JAVA/'java'),'-jar',str(resources/'bootstrap.jar'),'--service'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 home=root/'upgrade';prepare(home);proc=launch(home)
 try:
  wait_for(lambda:(home/'started-version').exists() and (home/'started-version').read_text()=='0.4.0-beta.2')
  assert (home/'updates/active.txt').read_text()=='0.4.0-beta.2'
 finally:proc.terminate();proc.wait(timeout=10)
 home=root/'rollback';prepare(home);(home/'fail-next').touch();proc=launch(home)
 try:
  wait_for(lambda:(home/'failed-update-version.txt').exists())
  wait_for(lambda:(home/'started-version').exists() and (home/'started-version').read_text()=='0.3.0')
  assert (home/'updates/active.txt').read_text()=='0.3.0'
 finally:proc.terminate();proc.wait(timeout=10)
 home=root/'tamper';jar=prepare(home);jar.write_text('tampered');(home/'updates/active.txt').write_text('0.4.0-beta.2');(home/'updates/pending.txt').unlink();proc=launch(home)
 try:
  wait_for(lambda:(home/'started-version').exists());assert (home/'started-version').read_text()=='0.3.0';assert (home/'update-error.txt').exists()
 finally:proc.terminate();proc.wait(timeout=10)
 home=root/'profile';profile=root/'Chosen profiles';env=os.environ.copy();env.update(SERVER_MANAGER_APP_PATH=str(app),SERVER_MANAGER_HOME=str(home))
 subprocess.run([str(JAVA/'java'),'-jar',str(resources/'bootstrap.jar'),'--configure','--data-directory',str(profile)],env=env,check=True)
 assert pathlib.Path((home/'data-location.txt').read_text().strip())==profile.resolve()
print('Bootstrap verified (BETA): signed upgrade, tamper rejection, startup rollback, profile folder selection')
