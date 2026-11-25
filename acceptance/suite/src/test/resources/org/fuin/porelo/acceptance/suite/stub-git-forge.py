"""A forge's git side for the acceptance scenarios: smart HTTP through git http-backend on loopback.

Serves every repository under <work>/served over HTTPS with <work>/server.pem and <work>/server.key, to a
request whose Basic credential's SHA-256 is the one in <work>/credential.sha256, as a forge serves a
repository to its token; every other request is answered 401 with a Basic challenge, which is when git
sends its credential. It listens on 127.0.0.1 at a port of its own choosing and writes that port to
<work>/port once it listens. It logs each request's method, path and status, never a header.

Usage: python3 -I stub-git-forge.py <work>
"""

import base64
import hashlib
import hmac
import http.server
import os
import ssl
import subprocess
import sys

WORK = sys.argv[1]
SERVED = os.path.join(WORK, "served")
with open(os.path.join(WORK, "credential.sha256"), encoding="ascii") as kept:
    EXPECTED = kept.read().strip()


def allowed(header):
    """Whether a request's Authorization header carries the credential the work dir names."""
    if not header or not header.startswith("Basic "):
        return False
    try:
        sent = base64.b64decode(header[len("Basic "):], validate=True)
    except ValueError:
        return False
    return hmac.compare_digest(hashlib.sha256(sent).hexdigest(), EXPECTED)


class Forge(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.answer()

    def do_POST(self):
        self.answer()

    def body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            read = bytearray()
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    return bytes(read)
                read += self.rfile.read(size)
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def answer(self):
        body = self.body()
        if not allowed(self.headers.get("Authorization")):
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="stub-forge"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        path, _, query = self.path.partition("?")
        environment = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_PROJECT_ROOT": SERVED,
            "GIT_HTTP_EXPORT_ALL": "1",
            "REQUEST_METHOD": self.command,
            "PATH_INFO": path,
            "QUERY_STRING": query,
            "CONTENT_LENGTH": str(len(body)),
            "REMOTE_USER": "x-access-token",
            "REMOTE_ADDR": "127.0.0.1",
        }
        for header, variable in (
            ("Content-Type", "CONTENT_TYPE"),
            ("Content-Encoding", "HTTP_CONTENT_ENCODING"),
            ("Git-Protocol", "GIT_PROTOCOL"),
        ):
            if self.headers.get(header):
                environment[variable] = self.headers[header]
        backend = subprocess.run(
            ["git", "http-backend"], input=body, env=environment, capture_output=True, check=False
        )
        out = backend.stdout
        crlf, lf = out.find(b"\r\n\r\n"), out.find(b"\n\n")
        end, gap = (crlf, 4) if crlf >= 0 and (lf < 0 or crlf < lf) else (lf, 2)
        if end < 0:
            self.send_response(502)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        status, headers = 200, []
        for line in out[:end].decode("latin-1").splitlines():
            name, colon, value = line.partition(":")
            if not colon:
                continue
            if name.strip().lower() == "status":
                status = int(value.strip().split(" ")[0])
            else:
                headers.append((name.strip(), value.strip()))
        content = out[end + gap:]
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, format, *args):
        # The request line and status only: a header could carry the credential.
        status = args[1] if len(args) > 1 else "-"
        path = getattr(self, "path", "-").partition("?")[0]
        sys.stderr.write("%s %s %s\n" % (getattr(self, "command", "-"), path, status))


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Forge)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(os.path.join(WORK, "server.pem"), os.path.join(WORK, "server.key"))
server.socket = context.wrap_socket(server.socket, server_side=True)
port = os.path.join(WORK, "port")
with open(port + ".new", "w", encoding="ascii") as written:
    written.write(str(server.server_address[1]))
# Whole when it appears: the glue reads it as soon as it exists.
os.replace(port + ".new", port)
server.serve_forever()
