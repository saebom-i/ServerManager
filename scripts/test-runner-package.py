#!/usr/bin/env python3
"""Smoke-test the packaged Mac runner without pairing or touching the live profile."""
import os
import pathlib
import platform
import subprocess
import tempfile
import time
import zipfile

ROOT=pathlib.Path(__file__).resolve().parent.parent
VERSION=(ROOT/'VERSION').read_text().strip()
PACKAGE=ROOT/'dist'/f'ServerManager-Runner-{VERSION}-mac-universal.zip'
with tempfile.TemporaryDirectory(prefix='servermanager-runner.',dir='/private/tmp') as folder:
    temp=pathlib.Path(folder)
    with zipfile.ZipFile(PACKAGE) as archive:
        archive.extractall(temp)
        for item in archive.infolist():
            if not item.is_dir():
                mode=item.external_attr>>16
                if mode&0o111:(temp/item.filename).chmod(mode)
    package=temp/'ServerManagerRunner-mac'
    home=temp/'runner-data'
    arch={'arm64':'aarch64','x86_64':'x64'}[platform.machine()]
    java=package/'runtimes'/arch/'Contents/Home/bin/java'
    env=os.environ.copy()
    env.update(SERVER_MANAGER_RUNNER_HOME=str(home))
    env.pop('RUNNER_PACKAGE_HOME',None)
    # A piped console must still explain what input it is waiting for. A failed
    # enrollment must not persist credentials or falsely mark the device paired.
    failed_pair=subprocess.run([str(java),'-jar',str(package/'server-manager.jar'),'--runner-pair','--manager-url=https://localhost:1'],env=env,input='fake-enrollment-code-never-issued-by-a-server\n',text=True,capture_output=True,timeout=30)
    assert failed_pair.returncode!=0 and '실행기 등록 코드:' in failed_pair.stdout
    assert not (home/'identity.json').exists()
    process=subprocess.Popen([str(java),'-jar',str(package/'runner-bootstrap.jar')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        log=home/'logs/runner.log'
        for _ in range(100):
            if process.poll() is not None:raise RuntimeError(f'Runner bootstrap exited: {process.stderr.read().decode()}')
            if log.exists() and 'Server Manager' in log.read_text():break
            time.sleep(.2)
        else:raise RuntimeError('Runner did not enter standby: '+(log.read_text() if log.exists() else 'no runner log')+' / '+(process.stderr.read(4096).decode() if process.poll() is not None else 'bootstrap still active'))
        time.sleep(4)
        if process.poll() is not None:raise RuntimeError('Runner supervisor exited after initial readiness check')
        assert '이 컴퓨터는 아직 등록되지 않았습니다' in log.read_text()
    finally:
        process.terminate()
        process.wait(timeout=30)
    print('Packaged Mac runner shows enrollment input, leaves no identity on failure, and enters unattended unpaired standby.')
