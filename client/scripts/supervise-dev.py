"""Run the local frontend without a console and recover unexpected exits."""
import datetime
import pathlib
import subprocess
import sys
import time

client = pathlib.Path(__file__).resolve().parents[1]
root = client.parent


def log(message):
    with (root / 'client-supervisor.log').open('a', encoding='utf-8') as stream:
        stream.write(f'[{datetime.datetime.now().astimezone().isoformat()}] {message}\n')


with (root / 'client-bootstrap.log').open('ab') as output, (root / 'client-bootstrap.err.log').open('ab') as errors:
    while True:
        try:
            child = subprocess.Popen(
                [sys.argv[1], str(client / 'scripts' / 'start-background-dev.mjs')],
                cwd=client, stdin=subprocess.DEVNULL, stdout=output, stderr=errors,
                creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP,
            )
            (root / 'client.pid').write_text(str(child.pid), encoding='ascii')
            log(f'frontend started pid={child.pid}')
            code = child.wait()
            log(f'frontend exited pid={child.pid} code={code} hex=0x{code & 0xffffffff:08X}; restart in 5s')
        except Exception as error:
            log(f'frontend launch failed: {error!r}; retry in 5s')
        time.sleep(5)
