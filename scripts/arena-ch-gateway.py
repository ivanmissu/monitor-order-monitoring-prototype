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
import struct
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import lz4.block as _lz4block
except ImportError:  # 未安装时回退为不压缩（仅 compress=0 的客户端可用）
    _lz4block = None

# ClickHouse 变体 CityHash128（v1.0.2，与 Google v1.1 不同 —— PyPI cityhash 包不兼容！）
# 以下为 ClickHouseCityHash.java 的忠实移植，已用 JPype 调用驱动内真实实现
# 对拍 233 个随机样本（0B~1MB）全部一致。
# ClickHouseCityHash.java 的忠实 Python 移植（无符号语义），用于校验 PyPI cityhash 兼容性
M = (1 << 64) - 1
def u(x): return x & M
k0, k1, k2, k3 = 0xc3a5c85c97cb3127, 0xb492b66fbe98f273, 0x9ae16a3b2f90404f, 0xc949d7c7509e6557
kMul = 0x9ddfea08eb382d69

def fetch64(s, i): return int.from_bytes(s[i:i+8], "little")
def fetch32(s, i): return int.from_bytes(s[i:i+4], "little")
def rot(v, sh): return v if sh == 0 else u((v << (64 - sh)) | (v >> sh))
def rot1(v, sh): return u((v << (64 - sh)) | (v >> sh))
def smix(v): return u(v ^ (v >> 47))

def h128to64(a_, b_):
    a = u((a_ ^ b_) * kMul); a = u(a ^ (a >> 47))
    b = u((b_ ^ a) * kMul); b = u(b ^ (b >> 47))
    return u(b * kMul)

def hl16(x, y): return h128to64(x, y)

def h0to16(s, pos, ln):
    if ln > 8:
        a = fetch64(s, pos); b = fetch64(s, pos + ln - 8)
        return u(hl16(a, rot1(u(b + ln), ln)) ^ b)
    if ln >= 4:
        a = fetch32(s, pos)
        return hl16(u((a << 3) + ln), fetch32(s, pos + ln - 4))
    if ln > 0:
        a, b, c = s[pos], s[pos + (ln >> 1)], s[pos + ln - 1]
        y = a + (b << 8); z = ln + (c << 2)
        return u(smix(u(y * k2 ^ z * k3)) * k2)
    return k2

def weak32(w, x, y, z, a, b):
    a = u(a + w); b = rot(u(b + a + z), 21); c = a
    a = u(a + x); a = u(a + y); b = u(b + rot(a, 44))
    return u(a + z), u(b + c)

def weak32s(s, pos, a, b):
    return weak32(fetch64(s, pos), fetch64(s, pos+8), fetch64(s, pos+16), fetch64(s, pos+24), a, b)

def murmur(s, pos, ln, seed0, seed1):
    a, b, c, d = seed0, seed1, 0, 0
    l = ln - 16
    if l <= 0:
        a = u(smix(u(a * k1)) * k1)
        c = u(b * k1 + h0to16(s, pos, ln))
        d = u(smix(u(a + (fetch64(s, pos) if ln >= 8 else c))))
    else:
        c = hl16(u(fetch64(s, pos + ln - 8) + k1), a)
        d = hl16(u(b + ln), u(c + fetch64(s, pos + ln - 16)))
        a = u(a + d)
        while True:
            a = u(a ^ u(smix(u(fetch64(s, pos) * k1)) * k1)); a = u(a * k1); b = u(b ^ a)
            c = u(c ^ u(smix(u(fetch64(s, pos+8) * k1)) * k1)); c = u(c * k1); d = u(d ^ c)
            pos += 16; l -= 16
            if l <= 0: break
    a = hl16(a, c); b = hl16(d, b)
    return u(a ^ b), hl16(b, a)

def with_seed(s, pos, ln, seed0, seed1):
    if ln < 128:
        return murmur(s, pos, ln, seed0, seed1)
    x, y = seed0, seed1
    z = u(k1 * ln)
    v0 = u(rot(u(y ^ k1), 49) * k1 + fetch64(s, pos))
    v1 = u(rot(v0, 42) * k1 + fetch64(s, pos + 8))
    w0 = u(rot(u(y + z), 35) * k1 + x)
    w1 = u(rot(u(x + fetch64(s, pos + 88)), 53) * k1)
    while True:
        x = u(rot(u(x + y + v0 + fetch64(s, pos + 16)), 37) * k1)
        y = u(rot(u(y + v1 + fetch64(s, pos + 48)), 42) * k1)
        x = u(x ^ w1); y = u(y ^ v0)
        z = rot(u(z ^ w0), 33)
        v0, v1 = weak32s(s, pos, u(v1 * k1), u(x + w0))
        w0, w1 = weak32s(s, pos + 32, u(z + w1), y)
        z, x = x, z
        pos += 64
        x = u(rot(u(x + y + v0 + fetch64(s, pos + 16)), 37) * k1)
        y = u(rot(u(y + v1 + fetch64(s, pos + 48)), 42) * k1)
        x = u(x ^ w1); y = u(y ^ v0)
        z = rot(u(z ^ w0), 33)
        v0, v1 = weak32s(s, pos, u(v1 * k1), u(x + w0))
        w0, w1 = weak32s(s, pos + 32, u(z + w1), y)
        z, x = x, z
        pos += 64; ln -= 128
        if ln < 128: break
    y = u(y + u(rot(w0, 37) * k0 + z))
    x = u(x + u(rot(u(v0 + z), 49) * k0))
    tail = 0
    while tail < ln:
        tail += 32
        y = u(u(rot(u(y - x), 42)) * k0 + v1)
        w0 = u(w0 + fetch64(s, pos + ln - tail + 16))
        x = u(u(rot(x, 49)) * k0 + w0)
        w0 = u(w0 + v0)
        v0, v1 = weak32s(s, pos + ln - tail, v0, v1)
    x = hl16(x, v0); y = hl16(y, w0)
    return u(hl16(u(x + v1), w1) + y), hl16(u(x + w1), u(y + v1))

def city_hash128_(s):
    ln = len(s)
    if ln >= 16:
        return with_seed(s, 16, ln - 16, u(fetch64(s, 0) ^ k3), fetch64(s, 8))
    if ln >= 8:
        return with_seed(b"", 0, 0, u(fetch64(s, 0) ^ u(ln * k0)), u(fetch64(s, ln - 8) ^ k1))
    return with_seed(s, 0, ln, k0, k1)


try:
    from chdb import session as chdb_session
except ImportError:
    print("请先安装 chdb：pip install chdb", file=sys.stderr)
    raise

_LOCK = threading.RLock()
_STATS = {"queries": 0, "errors": 0, "total_ms": 0}
_SESSION = None


def ch_lz4_frames(data: bytes, block_size: int = 1 << 20) -> bytes:
    """按 ClickHouse HTTP 压缩协议（compress=1）把响应体打成 LZ4 帧。

    帧结构（clickhouse-java V2 的 ClickHouseLZ4InputStream.refill 契约）：
      16B CityHash128(block) 校验和（低 64 位在前）+ 1B magic 0x82
      + 4B 压缩长度(含 9B 头, LE) + 4B 原始长度(LE) + LZ4 块（无 size 前缀）
    真实 ClickHouse 服务端同样按 ~1MB 块切分。
    """
    if _lz4block is None or not data:
        return data
    out = bytearray()
    for i in range(0, len(data), block_size):
        chunk = data[i:i + block_size]
        comp = _lz4block.compress(chunk, store_size=False)
        csize = len(comp) + 9
        block = (b"\x82" + struct.pack("<i", csize) + struct.pack("<i", len(chunk))
                 + comp)
        # 前 16 字节 = CityHash128(block)，低 64 位在前（驱动 ClickHouseLZ4InputStream 校验）
        a, b = city_hash128_(block)
        out += struct.pack("<Q", a)
        out += struct.pack("<Q", b)
        out += block
    return bytes(out)


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
    def _read_body(self):
        """读请求体：httpclient5（ClickHouse JDBC V2 底层）对 POST 体使用
        Transfer-Encoding: chunked（无 Content-Length），必须按 chunk 协议解码，
        否则读到空 SQL、残留字节还会污染后续 keep-alive 请求的解析。"""
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            body = b""
            while True:
                size_line = self.rfile.readline(1024).strip()
                if not size_line:  # 对端异常断开
                    break
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    # 尾部 trailer 头，读到空行为止
                    while True:
                        t = self.rfile.readline(1024)
                        if t in (b"\r\n", b"\n", b""):
                            break
                    break
                body += self.rfile.read(size)
                self.rfile.readline(1024)  # chunk 后的 CRLF
            return body
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

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
        body = self._read_body()
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
        # JDBC V2 默认 compress=true（请求带 compress=1），响应需打成 ClickHouse LZ4 帧
        compress = params.get("compress") == "1" and _lz4block is not None
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
                print(f"[ch] {elapsed:7.1f}ms user={user} fmt={fmt} {len(data)}B{' LZ4' if compress else ''}  {head}",
                      flush=True)
                self._send(200, ch_lz4_frames(bytes(data)) if compress else bytes(data),
                           "application/octet-stream",
                           extra={"X-ClickHouse-Format": fmt})
            except Exception as ex:  # chdb 抛 RuntimeError: Code: xx. DB::Exception...
                _STATS["errors"] += 1
                msg = str(ex)
                m = re.search(r"Code:\s*(\d+)", msg)
                code = int(m.group(1)) if m else 1000
                elapsed = (time.time() - t0) * 1000
                print(f"[ch-err] {elapsed:7.1f}ms code={code} {msg[:300]}", flush=True)
                # chdb 缺陷兜底：「表头已写入输出缓冲、投影期才失败」的查询（如
                # toInt64(nan)、字符串→DateTime 转换失败）抛异常后会把部分输出残留在
                # 会话缓冲里，下一个成功查询的响应 = 残渣 + 新数据拼接，客户端表现为
                # 列名错乱 / 行数异常。牺牲查询把残渣冲掉（其返回值包含残渣，丢弃）。
                for _flush in range(2):
                    try:
                        _SESSION.query("SELECT 1", "TabSeparated")
                        break
                    except Exception:
                        continue
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
