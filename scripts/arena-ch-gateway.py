#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
arena-ch-gateway.py —— 在 Arena 沙箱内提供真实 ClickHouse HTTP 服务端。

背景：沙箱无法下载/运行 clickhouse-server 二进制（仅 github.com / pypi.org 可达），
但 pip 提供的 chdb 是完整的 ClickHouse 本地引擎（与官方 server 同源同 SQL 方言）。
本网关用 http.server 把 chdb 包装成 clickhouse-jdbc（V2 驱动，jdbc:ch://）所需的
HTTP 子集：

  GET  /ping                     -> "Ok."
  POST /?query=...&database=...  -> 执行 SQL，按语句内 FORMAT 子句原样返回字节流
                                    （驱动默认追加 FORMAT RowBinaryWithNamesAndTypes）

用法：
  python3 arena-ch-gateway.py [--port 8123] [--data-dir /tmp/chdb-monitor] [--db monitor]

设计要点：
  * 单 chdb 会话 + 全局锁（chdb 会话非线程安全；查询本身为毫秒级，串行足够）；
  * 错误按 ClickHouse 惯例返回 500 + X-ClickHouse-Exception-Code 头；
  * 任何用户名/密码均接受（本地无访问管理，权限即数据源由账号约定承担）；
  * 全量请求日志（截断 SQL），便于联调排查驱动行为。
"""
import argparse
import gzip
import io
import re
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from chdb import session as chdb_session
except ImportError:
    print("请先安装 chdb：pip install chdb", file=sys.stderr)
    raise

_LOCK = threading.RLock()
_STATS = {"queries": 0, "errors": 0, "total_ms": 0}
_SESSION = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ClickHouse/26.7-chdb-gateway"

    # ── 基础工具 ──────────────────────────────────────────────────────────
    def _send(self, code, payload: bytes, content_type="text/plain; charset=UTF-8",
              extra=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(payload)

    def _error(self, code, message):
        payload = message.encode("utf-8")
        self._send(500, payload, extra={
            "X-ClickHouse-Exception-Code": str(code),
        })

    def _params(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        return parsed.path, {k: v[0] for k, v in qs.items()}

    def log_message(self, fmt, *args):  # 静默默认访问日志，统一走下方结构化日志
        pass

    # ── 端点 ──────────────────────────────────────────────────────────────
    def do_GET(self):
        path, params = self._params()
        if path == "/ping":
            self._send(200, b"Ok.\n")
            return
        if "query" in params:
            self._run(params["query"], params)
            return
        self._send(200, b"Ok.\n")

    def do_POST(self):
        path, params = self._params()
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        if "compress" in params or self.headers.get("Content-Encoding") == "gzip":
            try:
                body = gzip.decompress(body)
            except OSError:
                pass
        sql = params.get("query") or body.decode("utf-8", "replace")
        self._run(sql, params)

    # ── 执行 ──────────────────────────────────────────────────────────────
    def _run(self, sql, params):
        user = (self.headers.get("X-ClickHouse-User")
                or params.get("user") or "default")
        # JDBC V2 驱动不在 SQL 内追加 FORMAT，而是通过 X-ClickHouse-Format 头声明；
        # 语句内自带 FORMAT 子句时优先（chdb 的 format 参数仅为默认值）
        fmt = (self.headers.get("X-ClickHouse-Format")
               or params.get("default_format") or "TabSeparated")
        sql_one = re.sub(r"\s+", " ", sql.strip())
        head = sql_one[:150]
        t0 = time.time()
        with _LOCK:
            try:
                result = _SESSION.query(sql, fmt) if sql else b""
                data = result.bytes() if hasattr(result, "bytes") else result
                if not isinstance(data, (bytes, bytearray)):
                    data = str(data).encode("utf-8")
                elapsed = (time.time() - t0) * 1000
                _STATS["queries"] += 1
                _STATS["total_ms"] += elapsed
                print(f"[ch] {elapsed:7.1f}ms user={user} fmt={fmt} {len(data)}B  {head}",
                      flush=True)
                self._send(200, bytes(data), "application/octet-stream",
                           extra={"X-ClickHouse-Format": fmt})
            except Exception as ex:  # chdb 抛 RuntimeError: Code: xx. DB::Exception...
                _STATS["errors"] += 1
                msg = str(ex)
                m = re.search(r"Code:\s*(\d+)", msg)
                code = int(m.group(1)) if m else 1000
                elapsed = (time.time() - t0) * 1000
                print(f"[ch-err] {elapsed:7.1f}ms code={code} {msg[:300]}", flush=True)
                self._error(code, f"Code: {code}. DB::Exception: {msg}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8123)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--data-dir", default="/tmp/chdb-monitor")
    ap.add_argument("--db", default="monitor")
    args = ap.parse_args()

    global _SESSION
    _SESSION = chdb_session.Session(args.data_dir)
    _SESSION.query(f"CREATE DATABASE IF NOT EXISTS {args.db}", "TabSeparated")
    _SESSION.query(f"USE {args.db}", "TabSeparated")
    version = _SESSION.query("SELECT version()", "TabSeparated").bytes().decode().strip()
    print(f"[ch] chdb ClickHouse {version} 就绪: data={args.data_dir} db={args.db}", flush=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"[ch] HTTP 网关监听 http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stats = _STATS
        print(f"[ch] 关闭: {stats}")


if __name__ == "__main__":
    main()
