"""Real local TCP smoke test with synthetic Java/model backends and a child Uvicorn process."""

import asyncio, json, os, socket, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import httpx

from test_run_api import Backend, SERVICE, envelope, headers

def test_native_uvicorn_http_roundtrip_and_shutdown():
    root=Path(__file__).resolve().parents[1]
    backend=Backend()
    class Stub(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def handle_request(self):
            raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
            target=('https://model.test' if self.path=='/chat' else 'http://java.test:8080')+self.path
            request=httpx.Request(self.command,target,headers=dict(self.headers),content=raw)
            response=asyncio.run(backend.handle(request))
            self.send_response(response.status_code)
            self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(response.content)))
            self.end_headers(); self.wfile.write(response.content)
        do_GET=handle_request
        do_POST=handle_request
    stub=ThreadingHTTPServer(('127.0.0.1',0),Stub)
    thread=threading.Thread(target=stub.serve_forever,daemon=True); thread.start()
    with socket.socket() as selected:
        selected.bind(('127.0.0.1',0)); port=selected.getsockname()[1]
    env=dict(os.environ)
    env.update(APP_AGENT_SERVICE_TOKEN=SERVICE, APP_AGENT_JAVA_BASE_URL=f'http://127.0.0.1:{stub.server_port}',
        APP_AI_PROVIDER='qwen', APP_AI_API_URL=f'http://127.0.0.1:{stub.server_port}/chat',
        APP_AI_API_KEY='test-only-model-key', APP_AI_CONNECT_TIMEOUT='10000', APP_AI_READ_TIMEOUT='10000',
        APP_AGENT_INTERNAL_MAX_REQUEST_MS='170000')
    process=subprocess.Popen([sys.executable,'-m','uvicorn','kangban_agent.main:create_app','--factory','--app-dir','src',
        '--host','127.0.0.1','--port',str(port),'--no-access-log','--log-level','warning'],cwd=root,env=env,
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    try:
        with httpx.Client(base_url=f'http://127.0.0.1:{port}',trust_env=False,timeout=5) as client:
            for attempt in range(100):
                try:
                    if client.get('/health').status_code==200: break
                except httpx.HTTPError: pass
                if process.poll() is not None: raise AssertionError(process.stdout.read().decode(errors='replace'))
                time.sleep(.05)
            else: raise AssertionError('Uvicorn did not become ready')
            body=envelope()
            response=client.post('/internal/agent/v1/run',json=body,headers=headers(body))
            assert response.status_code==200,response.text
            assert response.json()['response']['content']=='模拟回答'
            assert len(backend.requests)==3
            assert client.post('/internal/agent/v1/run',json=body).status_code==401
            print('PASS: native Uvicorn TCP health, authenticated run, 3 simulated backend requests, and missing-credential rejection')
    finally:
        process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        stub.shutdown(); stub.server_close(); thread.join(timeout=5)
    with socket.socket() as probe:
        probe.settimeout(.5)
        assert probe.connect_ex(('127.0.0.1',port))!=0
    print('PASS: smoke-test service stopped and port released')
