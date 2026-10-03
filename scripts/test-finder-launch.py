#!/usr/bin/env python3
"""Exercise the installed .app entry point via macOS LaunchServices without changing real user data."""
import os,pathlib,subprocess,tempfile,time,urllib.request,json,socket,signal
root=pathlib.Path(__file__).resolve().parent.parent
app=root/'build/macos-package/root/Applications/Server Manager.app'
with socket.socket() as sock:
    sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
with tempfile.TemporaryDirectory(prefix='server-manager-finder-') as directory:
    home=pathlib.Path(directory)
    subprocess.run(['/usr/bin/open','-n','-g','--env',f'SERVER_MANAGER_HOME={home}','--env',f'SERVER_MANAGER_DATA={home}/data',str(app),'--args','--service',f'--server.port={port}',f'--server-manager.public-origin=http://localhost:{port}'],check=True)
    pid=None
    try:
        for _ in range(60):
            try:
                with urllib.request.urlopen(f'http://localhost:{port}/api/auth/session',timeout=2) as response: session=json.load(response)
                assert session['setupRequired']
                assert (home/'data/security/setup-code.txt').exists()
                print('macOS LaunchServices .app launch passed with isolated data and bundled Java')
                break
            except (OSError,TimeoutError):time.sleep(1)
        else:raise RuntimeError('Finder launch timed out')
    finally:
        result=subprocess.run(['/usr/sbin/lsof','-t',f'-iTCP:{port}','-sTCP:LISTEN'],capture_output=True,text=True)
        for row in result.stdout.splitlines():
            pid=int(row)
            command=subprocess.check_output(['/bin/ps','-p',str(pid),'-o','command='],text=True)
            if str(app) in command and f'--server.port={port}' in command:
                os.kill(pid,signal.SIGTERM)
                for _ in range(30):
                    try:os.kill(pid,0)
                    except ProcessLookupError:break
                    time.sleep(1)
