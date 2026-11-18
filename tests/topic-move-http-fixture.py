#!/usr/bin/env python3
"""Loopback-only HTTP fixture for testing URLSession's actual streamed POST transport."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    records: list[dict[str, str]] = []
    node: str = "qna"

    def log_message(self, format: str, *args: object) -> None:
        pass

    def respond(self, body: str, status: int = 200) -> None:
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        if self.path == "/records":
            self.respond(json.dumps(type(self).records))
            return
        type(self).records.append({"method": "GET", "path": self.path})
        if self.path == "/t/123":
            self.respond(f'<div id="Main"><div class="box"><div class="header">'
                         f'<a href="/go/{type(self).node}">Node</a><h1>Fixture</h1>'
                         '<a class="op" href="/move/topic/123">MOVE</a></div></div></div>')
        elif self.path == "/move/topic/123":
            self.respond('<div id="Main"><div class="box"><div class="inner">'
                         '<form method="post" action="/move/topic/123">'
                         '<select name="destination"></select><input type="text" name="memo">'
                         '</form></div></div></div>')
        else:
            self.respond("", 404)

    def do_POST(self) -> None:
        body = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()
        type(self).records.append({"method": "POST", "path": self.path, "body": body,
                                  "cookie": self.headers.get("Cookie", "")})
        if "destination=sandbox" in body:
            type(self).node = "sandbox"
        # A transport might replay a non-one-shot body after this response.
        self.respond("", 503)


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 0), Handler)
    print(server.server_port, flush=True)
    server.serve_forever()
