#!/usr/bin/env python3
"""Build self-contained Mac universal and Windows x64 runner installers."""
import hashlib
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import zipfile

ROOT=pathlib.Path(__file__).resolve().parent.parent
VERSION=(ROOT/'VERSION').read_text().strip()
DIST=ROOT/'dist'
STAGE=ROOT/'build/runner-package'
MAC_RUNTIMES=ROOT/'build/macos-package/root/Applications/Server Manager.app/Contents/Resources/runtimes'
JAVA=pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip())

def copy_common(target):
    target.mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/f'build/libs/server-manager-{VERSION}.jar',target/'server-manager.jar')
    shutil.copy2(ROOT/'src/main/resources/update-public-key.der',target/'update-public-key.der')
    (target/'version.txt').write_text(VERSION+'\n')
    classes=STAGE/'bootstrap-classes'
    classes.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(JAVA/'bin/javac'),'--release','21','-d',str(classes),str(ROOT/'packaging/runner/RunnerBootstrap.java')],check=True)
    subprocess.run([str(JAVA/'bin/jar'),'--create','--file',str(target/'runner-bootstrap.jar'),'--main-class','RunnerBootstrap','-C',str(classes),'.'],check=True)

def archive(folder,name):
    destination=DIST/name
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
        for path in folder.rglob('*'):
            if path.is_file():
                info=zipfile.ZipInfo(str(path.relative_to(folder.parent)))
                mode=stat.S_IMODE(path.stat().st_mode)
                info.external_attr=(0o755 if path.suffix in ('.command','.sh') or mode&0o111 else 0o644)<<16
                info.compress_type=zipfile.ZIP_DEFLATED
                output.writestr(info,path.read_bytes())
    print(destination)

def mac():
    if not (MAC_RUNTIMES/'x64').exists() or not (MAC_RUNTIMES/'aarch64').exists():
        raise RuntimeError('Run scripts/package-macos.py before packaging the Mac runner')
    folder=STAGE/'ServerManagerRunner-mac'
    copy_common(folder)
    shutil.copytree(MAC_RUNTIMES,folder/'runtimes',dirs_exist_ok=True)
    for script in (ROOT/'packaging/runner/macos').iterdir():shutil.copy2(script,folder/script.name)
    archive(folder,f'ServerManager-Runner-{VERSION}-mac-universal.zip')

def windows():
    folder=STAGE/'ServerManagerRunner-windows'
    copy_common(folder)
    for script in (ROOT/'packaging/runner/windows').iterdir():shutil.copy2(script,folder/script.name)
    api='https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
    asset=json.loads(subprocess.check_output(['/usr/bin/curl','--fail','--silent','--show-error','--location','--retry','2','--max-time','90',api]))[0]
    package=asset['binary']['package']
    cache=ROOT/'.runtime-cache'
    cache.mkdir(exist_ok=True)
    archive_file=cache/package['name']
    if not archive_file.exists() or hashlib.sha256(archive_file.read_bytes()).hexdigest()!=package['checksum']:
        partial=archive_file.with_suffix('.partial')
        subprocess.run(['/usr/bin/curl','--fail','--silent','--show-error','--location','--retry','2','--max-time','600','--output',str(partial),package['link']],check=True)
        if hashlib.sha256(partial.read_bytes()).hexdigest()!=package['checksum']:raise RuntimeError('Windows Java runtime checksum mismatch')
        partial.replace(archive_file)
    with zipfile.ZipFile(archive_file) as source:
        names=source.namelist()
        if any(name.startswith('/') or '..' in pathlib.PurePosixPath(name).parts for name in names):raise RuntimeError('Unsafe Windows Java runtime archive')
        prefix=pathlib.PurePosixPath(names[0]).parts[0]
        for item in source.infolist():
            parts=pathlib.PurePosixPath(item.filename).parts
            if len(parts)<=1 or item.is_dir():continue
            target=folder/'runtime'/pathlib.Path(*parts[1:])
            target.parent.mkdir(parents=True,exist_ok=True)
            with source.open(item) as input,target.open('wb') as output:shutil.copyfileobj(input,output)
    if not (folder/'runtime/bin/java.exe').exists():raise RuntimeError('Windows Java runtime layout not recognized')
    archive(folder,f'ServerManager-Runner-{VERSION}-windows-x64.zip')

if __name__=='__main__':
    DIST.mkdir(exist_ok=True)
    if STAGE.exists():shutil.rmtree(STAGE)
    modes=sys.argv[1:] or ['mac','windows']
    for mode in modes:
        if mode=='mac':mac()
        elif mode=='windows':windows()
        else:raise ValueError(mode)
