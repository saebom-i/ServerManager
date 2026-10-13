#!/usr/bin/env python3
import pathlib,hashlib,shutil,os,subprocess
root=pathlib.Path(__file__).resolve().parent.parent;version=(root/'VERSION').read_text().strip();dist=root/'dist';dist.mkdir(exist_ok=True)
jar=dist/f'server-manager-{version}.jar';shutil.copy2(root/'build/libs'/jar.name,jar)
manifest=f'protocol=1\nversion={version}\nfile={jar.name}\nsize={jar.stat().st_size}\nsha256={hashlib.sha256(jar.read_bytes()).hexdigest()}\n'
(dist/'update.properties').write_text(manifest,encoding='ascii')
java_home=os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip()
key=pathlib.Path(os.environ.get('UPDATE_SIGNING_KEY_FILE',root/'.release-keys/private.der'))
subprocess.run([str(pathlib.Path(java_home)/'bin/java'),str(root/'packaging/bootstrap/SignRelease.java'),str(key),str(dist/'update.properties'),str(dist/'update.sig')],check=True)
files=[jar,dist/f'ServerManager-{version}-universal.pkg',dist/f'ServerManager-Runner-{version}-mac-universal.zip',dist/f'ServerManager-Runner-{version}-windows-x64.zip',dist/'update.properties',dist/'update.sig']
(dist/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in files))
print('Release prepared:',version)
