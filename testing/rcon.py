import argparse
import os
import re
import socket
import struct
import sys
import time

SENTINEL_TYPE = 100
SPRINT_TICKS = 10_000_000
DEFAULT_FAIL = r"can't start this job|\] I'm missing |\] I need |isn't allowed to build|Exception"


class Rcon:
    def __init__(self, port, password):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=30)
        self.next_id = 1
        req = self.send(3, password)
        if self.receive()[0] != req:
            sys.exit("RCON authentication failed")

    def send(self, kind, body):
        req = self.next_id
        self.next_id += 1
        data = struct.pack("<ii", req, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(data)) + data)
        return req

    def receive(self):
        length = struct.unpack("<i", self.read(4))[0]
        data = self.read(length)
        req, _ = struct.unpack("<ii", data[:8])
        return req, data[8:-2].decode("utf-8", "replace")

    def read(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise ConnectionError("connection closed")
            out += chunk
        return out

    def command(self, text, timeout=300):
        self.sock.settimeout(timeout)
        req = self.send(2, text)
        got, body = self.receive()
        while got != req:
            got, body = self.receive()
        sentinel = self.send(SENTINEL_TYPE, "")
        while True:
            got, part = self.receive()
            if got == sentinel:
                break
            body += part
        return re.sub("§.", "", body)

    def close(self):
        self.sock.close()


class Log:
    def __init__(self, path):
        self.path = path
        self.mark = self.size()

    def size(self):
        try:
            return os.path.getsize(self.path) if self.path else 0
        except OSError:
            return 0

    def read(self, start):
        if not self.path:
            return [], start
        with open(self.path, "rb") as f:
            f.seek(start)
            data = f.read()
        end = data.rfind(b"\n")
        if end < 0:
            return [], start
        return data[:end].decode("utf-8", "replace").splitlines(), start + end + 1


class Runner:
    def __init__(self, rcon, log, pattern, poll):
        self.rcon = rcon
        self.log = log
        self.pattern = re.compile(pattern) if pattern else None
        self.poll = poll
        self.timeouts = []
        self.failures = []
        self.command_offset = 0
        self.file = ""
        self.has_gametime = True

    def out(self, text):
        print(text, flush=True)

    def gametime(self):
        if not self.has_gametime:
            return 0
        for _ in range(2):
            match = re.search(r"The time is (\d+)", self.rcon.command("time query gametime"))
            if match:
                return int(match.group(1))
        self.has_gametime = False
        return 0

    def waited(self, start, tick0):
        seconds = time.time() - start
        if not self.has_gametime:
            return f"{seconds:.1f} s"
        ticks = self.gametime() - tick0
        return f"{seconds:.1f} s, {ticks} ticks, {ticks / max(seconds, 0.001):.0f} ticks/s"

    def timeout(self, what, detail):
        message = f"TIMEOUT waiting for {what}"
        self.timeouts.append(f"{self.file}: {message}")
        self.out("!" * 70)
        self.out(f"{message} ({detail})")
        self.out("!" * 70)

    def fail(self, what, pattern, line):
        message = f"FAIL waiting for {what}: /{pattern}/ matched"
        self.failures.append(f"{self.file}: {message}: {line.strip()}")
        self.out("!" * 70)
        self.out(message)
        self.out(line.strip())
        self.out("!" * 70)

    def split_fail(self, rest):
        rest, sep, fail = rest.partition(" !!")
        if not sep:
            return rest, None
        return rest, re.compile(fail.strip() or DEFAULT_FAIL)

    def failed(self, fail, offset, what):
        if fail is None:
            return offset, False
        lines, offset = self.log.read(offset)
        for line in lines:
            if fail.search(line):
                self.fail(what, fail.pattern, line)
                return offset, True
        return offset, False

    def run_command(self, command):
        command = command.replace("{q}", "\"")
        self.command_offset = self.log.size()
        body = self.rcon.command(command)
        self.out(f"> {command}")
        self.out(body or "(no output)")

    def ticks(self, args):
        parts = args.split()
        count = int(parts[0])
        limit = float(parts[1]) if len(parts) > 1 else max(60.0, count / 50.0)
        self.out(f"> ticks {count}")
        start, tick0 = time.time(), self.gametime()
        self.rcon.command(f"tick sprint {count}")
        while "sprinting" in self.rcon.command("tick query"):
            if time.time() - start > limit:
                self.rcon.command("tick sprint stop")
                self.timeout(f"{count} ticks to pass", f"{limit:.0f} s")
                return
            time.sleep(0.02)
        self.out(f"(waited {self.waited(start, tick0)})")

    def wait_args(self, args):
        limit, rest = args.split(" ", 1)
        if rest.startswith("nosprint "):
            return float(limit), False, rest[len("nosprint "):]
        return float(limit), True, rest

    def waitfor(self, args):
        limit, sprint, rest = self.wait_args(args)
        rest, fail = self.split_fail(rest)
        fail_offset = self.command_offset
        match = re.match(r"(.*?) (=~|!~) (.*)", rest)
        if not match:
            raise ValueError(f"waitfor needs '<command> =~ <regex>' or '<command> !~ <regex>': {args}")
        command, op, regex = match.group(1).replace("{q}", "\""), match.group(2), match.group(3)
        pattern = re.compile(regex)
        start, tick0 = time.time(), self.gametime()
        if sprint:
            self.rcon.command(f"tick sprint {SPRINT_TICKS}")
        try:
            while True:
                body = self.rcon.command(command)
                if (pattern.search(body) is not None) == (op == "=~"):
                    break
                fail_offset, failed = self.failed(fail, fail_offset, f"output of '{command}' {op} /{regex}/")
                if failed:
                    return
                if time.time() - start > limit:
                    self.out(f"> {command}")
                    self.out(body or "(no output)")
                    self.timeout(f"output of '{command}' {op} /{regex}/", f"{limit:.0f} s")
                    return
                time.sleep(self.poll)
        finally:
            self.rcon.command("tick sprint stop")
        self.out(f"> {command}")
        self.out(body or "(no output)")
        self.out(f"(waited {self.waited(start, tick0)} until output {op} /{regex}/)")

    def forceload(self, line):
        self.run_command(line)
        coords = [int(float(v)) >> 4 for v in line.split()[2:6]]
        if len(coords) == 2:
            coords += coords
        chunks = [(cx, cz) for cx in range(min(coords[0], coords[2]), max(coords[0], coords[2]) + 1)
                  for cz in range(min(coords[1], coords[3]), max(coords[1], coords[3]) + 1)]
        start, tick0 = time.time(), self.gametime()
        self.rcon.command(f"tick sprint {SPRINT_TICKS}")
        try:
            while chunks:
                chunks = [(cx, cz) for cx, cz in chunks if "passed" not in self.rcon.command(f"execute if loaded {cx * 16} 0 {cz * 16}")]
                if not chunks:
                    break
                if time.time() - start > 60:
                    self.timeout("the force-loaded area to load", f"{len(chunks)} chunk(s) still not loaded with their entities after 60 s")
                    return
                time.sleep(self.poll)
        finally:
            self.rcon.command("tick sprint stop")
        self.out(f"(area loaded with its entities after {self.waited(start, tick0)})")

    def waitlog(self, args):
        limit, sprint, rest = self.wait_args(args)
        count = 1
        match = re.match(r"x(\d+) (.*)", rest)
        if match:
            count, rest = int(match.group(1)), match.group(2)
        rest, fail = self.split_fail(rest)
        fail_offset = self.command_offset
        pattern = re.compile(rest)
        self.out(f"> waitlog {'x%d ' % count if count > 1 else ''}{rest}")
        start, tick0 = time.time(), self.gametime()
        offset, hits = self.log.mark, []
        if sprint:
            self.rcon.command(f"tick sprint {SPRINT_TICKS}")
        try:
            while True:
                lines, offset = self.log.read(offset)
                hits += [line for line in lines if pattern.search(line)]
                if len(hits) >= count:
                    break
                fail_offset, failed = self.failed(fail, fail_offset, f"{count} server log line(s) matching /{rest}/")
                if failed:
                    return
                if time.time() - start > limit:
                    self.timeout(f"{count} server log line(s) matching /{rest}/", f"found {len(hits)} in {limit:.0f} s")
                    return
                time.sleep(self.poll)
        finally:
            self.rcon.command("tick sprint stop")
        self.out(hits[count - 1].strip())
        self.out(f"(waited {self.waited(start, tick0)})")

    def run_file(self, path, rate):
        self.file = os.path.basename(path)
        self.log.mark = file_mark = self.log.size()
        self.out("=" * 70)
        self.out(f"=== {self.file}")
        self.out("=" * 70)
        file_start, file_tick = time.time(), self.gametime()
        self.rcon.command(f"tick rate {rate}")
        with open(path, encoding="utf-8") as f:
            lines = [line.strip() for line in f]
        for line in lines:
            if not line or line.startswith("#"):
                continue
            word, _, args = line.partition(" ")
            if word == "sleep":
                time.sleep(float(args))
            elif word == "ticks":
                self.ticks(args)
            elif word == "waitfor":
                self.waitfor(args)
            elif word == "waitlog":
                self.waitlog(args)
            elif line.startswith("forceload add "):
                self.forceload(line)
            elif word == "mark":
                self.log.mark = self.log.size()
            else:
                self.run_command(line)
        self.rcon.command("tick sprint stop")
        self.rcon.command("tick rate 20")
        elapsed, ticks = time.time() - file_start, self.gametime() - file_tick
        time.sleep(0.5)
        if self.pattern:
            self.out(f"--- server log ({self.file}) ---")
            lines, _ = self.log.read(file_mark)
            for line in lines:
                if self.pattern.search(line):
                    self.out(line)
        summary = f"{self.file}: {elapsed:.0f} s" + (f", {ticks} ticks" if self.has_gametime else "")
        self.out(f"=== {summary}")
        return summary

    def run(self, files, rate):
        summaries = []
        for path in files:
            summaries.append(self.run_file(path, rate))
        return summaries


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="journeymen-dev")
    parser.add_argument("--log", default="")
    parser.add_argument("--log-pattern", default="")
    parser.add_argument("--rate", type=float, default=20)
    parser.add_argument("--poll", type=float, default=0.05)
    parser.add_argument("--stop", action="store_true")
    parser.add_argument("files", nargs="+")
    args = parser.parse_args()
    rcon = Rcon(args.port, args.password)
    runner = Runner(rcon, Log(args.log), args.log_pattern, args.poll)
    summaries = runner.run(args.files, f"{args.rate:g}")
    print("=" * 70)
    for line in summaries:
        print(line)
    if runner.timeouts:
        print(f"{len(runner.timeouts)} TIMEOUT(S):")
        for line in runner.timeouts:
            print("  " + line)
    else:
        print("no timeouts")
    if runner.failures:
        print(f"{len(runner.failures)} FAILURE(S):")
        for line in runner.failures:
            print("  " + line)
    else:
        print("no failures")
    if args.stop:
        try:
            rcon.command("stop", timeout=10)
        except (OSError, ConnectionError):
            pass
    rcon.close()
    sys.exit(1 if runner.timeouts or runner.failures else 0)


main()
