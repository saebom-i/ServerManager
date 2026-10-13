#!/usr/bin/env python3
"""Test the shared installer without modifying login tasks or the installed runner."""
import os
import pathlib
import subprocess
import tempfile

ROOT=pathlib.Path(__file__).resolve().parent.parent
JAVA=pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip())
with tempfile.TemporaryDirectory(prefix='runner-setup-tests-') as folder:
    target=pathlib.Path(folder)
    files=['RunnerSetup.java','RunnerSetupTest.java','RunnerPairFixture.java']
    subprocess.run([str(JAVA/'bin/javac'),'--release','21','-encoding','UTF-8','-d',folder,*[str(ROOT/'packaging/runner'/name) for name in files]],check=True)
    fixture=target/'fixture.jar'
    subprocess.run([str(JAVA/'bin/jar'),'--create','--file',str(fixture),'--main-class','RunnerPairFixture','-C',folder,'RunnerPairFixture.class'],check=True)
    result=subprocess.run([str(JAVA/'bin/java'),'-cp',folder,'RunnerSetupTest',str(fixture)],capture_output=True,text=True,encoding='utf-8')
    assert 'fixture-code-that' not in result.stdout+result.stderr
    assert 'fixture-failing-code' not in result.stdout+result.stderr
    print(result.stdout,end='')
    if result.returncode:
        print(result.stderr,end='')
        raise SystemExit(result.returncode)
