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
DEFAULT_WAIT = 10.0
FORCELOAD_WAIT = 30.0
SLEEP_REJECTED = ("real-time 'sleep' runs at normal speed: use 'ticks', 'waitlog' or 'waitfor' "
                  "(or 'realsleep <seconds>' where real time is genuinely needed)")


class Abort(Exception):
    pass


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
        self.lineno = 0
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

    def where(self):
        return f"{self.file}:{self.lineno}"

    def timeout(self, what, detail):
        message = f"TIMEOUT waiting for {what}"
        self.timeouts.append(f"{self.where()}: {message}")
        self.out("!" * 70)
        self.out(f"{message} ({detail}, line {self.lineno})")
        self.out("!" * 70)
        raise Abort()

    def fail(self, what, pattern, line):
        message = f"FAIL waiting for {what}: /{pattern}/ matched"
        self.failures.append(f"{self.where()}: {message}: {line.strip()}")
        self.out("!" * 70)
        self.out(f"{message} (line {self.lineno})")
        self.out(line.strip())
        self.out("!" * 70)
        raise Abort()

    def error(self, message):
        self.failures.append(f"{self.where()}: FAIL: {message}")
        self.out("!" * 70)
        self.out(f"FAIL in line {self.lineno}: {message}")
        self.out("!" * 70)
        raise Abort()

    def split_fail(self, rest):
        rest, sep, fail = rest.partition(" !!")
        if not sep:
            return rest, None
        return rest, re.compile(fail.strip() or DEFAULT_FAIL)

    def failed(self, fail, offset, what):
        if fail is None:
            return offset
        lines, offset = self.log.read(offset)
        for line in lines:
            if fail.search(line):
                self.fail(what, fail.pattern, line)
        return offset

    def run_command(self, command):
        command = command.replace("{q}", "\"")
        self.command_offset = self.log.size()
        body = self.rcon.command(command)
        self.out(f"> {command}")
        self.out(body or "(no output)")

    def ticks_args(self, args):
        parts = args.split()
        if len(parts) not in (1, 2):
            raise ValueError(f"ticks needs '<n> [timeout]': ticks {args}")
        count = int(parts[0])
        return count, float(parts[1]) if len(parts) > 1 else max(DEFAULT_WAIT, count / 100.0)

    def ticks(self, args):
        count, limit = self.ticks_args(args)
        self.out(f"> ticks {count}")
        start, tick0 = time.time(), self.gametime()
        self.rcon.command(f"tick sprint {count}")
        while "sprinting" in self.rcon.command("tick query"):
            if time.time() - start > limit:
                self.rcon.command("tick sprint stop")
                self.timeout(f"{count} ticks to pass", f"{limit:g} s")
            time.sleep(0.02)
        self.out(f"(waited {self.waited(start, tick0)})")

    def wait_args(self, args):
        limit = DEFAULT_WAIT
        match = re.match(r"(\d+(?:\.\d+)?) (.*)", args)
        if match:
            limit, args = float(match.group(1)), match.group(2)
        if args.startswith("nosprint "):
            return limit, False, args[len("nosprint "):]
        return limit, True, args

    def waitfor_args(self, args):
        limit, sprint, rest = self.wait_args(args)
        rest, fail = self.split_fail(rest)
        match = re.match(r"(.*?) (=~|!~) (.*)", rest)
        if not match:
            raise ValueError(f"waitfor needs '[timeout] [nosprint] <command> =~ <regex>' (or '!~'): waitfor {args}")
        return limit, sprint, fail, match.group(1).replace("{q}", "\""), match.group(2), re.compile(match.group(3))

    def waitfor(self, args):
        limit, sprint, fail, command, op, pattern = self.waitfor_args(args)
        regex = pattern.pattern
        fail_offset = self.command_offset
        start, tick0 = time.time(), self.gametime()
        if sprint:
            self.rcon.command(f"tick sprint {SPRINT_TICKS}")
        try:
            while True:
                body = self.rcon.command(command)
                if (pattern.search(body) is not None) == (op == "=~"):
                    break
                fail_offset = self.failed(fail, fail_offset, f"output of '{command}' {op} /{regex}/")
                if time.time() - start > limit:
                    self.out(f"> {command}")
                    self.out(body or "(no output)")
                    self.timeout(f"output of '{command}' {op} /{regex}/", f"{limit:g} s")
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
                if time.time() - start > FORCELOAD_WAIT:
                    self.timeout("the force-loaded area to load",
                                 f"{len(chunks)} chunk(s) still not loaded with their entities after {FORCELOAD_WAIT:g} s")
                time.sleep(self.poll)
        finally:
            self.rcon.command("tick sprint stop")
        self.out(f"(area loaded with its entities after {self.waited(start, tick0)})")

    def waitlog_args(self, args):
        limit, sprint, rest = self.wait_args(args)
        count = 1
        match = re.match(r"x(\d+) (.*)", rest)
        if match:
            count, rest = int(match.group(1)), match.group(2)
        rest, fail = self.split_fail(rest)
        if not rest.strip():
            raise ValueError(f"waitlog needs '[timeout] [nosprint] [x<count>] <regex>': waitlog {args}")
        return limit, sprint, count, re.compile(rest), fail

    def waitlog(self, args):
        limit, sprint, count, pattern, fail = self.waitlog_args(args)
        rest = pattern.pattern
        fail_offset = self.command_offset
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
                fail_offset = self.failed(fail, fail_offset, f"{count} server log line(s) matching /{rest}/")
                if time.time() - start > limit:
                    self.timeout(f"{count} server log line(s) matching /{rest}/", f"found {len(hits)} in {limit:g} s")
                time.sleep(self.poll)
        finally:
            self.rcon.command("tick sprint stop")
        self.out(hits[count - 1].strip())
        self.out(f"(waited {self.waited(start, tick0)})")

    def check(self, word, args):
        if word == "sleep":
            raise ValueError(SLEEP_REJECTED)
        if word == "realsleep":
            float(args)
        elif word == "ticks":
            self.ticks_args(args)
        elif word == "waitfor":
            self.waitfor_args(args)
        elif word == "waitlog":
            self.waitlog_args(args)

    def run_line(self, line):
        word, _, args = line.partition(" ")
        if word == "realsleep":
            self.out(f"> realsleep {args}")
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

    def guarded(self, action, *args):
        try:
            return action(*args)
        except (ValueError, re.error) as e:
            self.error(str(e))

    def reset(self, players):
        commands = ["tick sprint stop", "tick unfreeze", "kill @e[type=journeymen:builder]"]
        commands += [f"journeymen debug gamemode {player} survival" for player in sorted(players)]
        for command in commands:
            self.out(f"> {command}")
            self.out(self.rcon.command(command) or "(no output)")

    def run_file(self, path, rate):
        self.file = os.path.basename(path)
        self.lineno = 0
        self.log.mark = file_mark = self.log.size()
        self.out("=" * 70)
        self.out(f"=== {self.file}")
        self.out("=" * 70)
        with open(path, encoding="utf-8") as f:
            lines = [(n, line.strip()) for n, line in enumerate(f, 1)]
        lines = [(n, line) for n, line in lines if line and not line.startswith("#")]
        file_start, file_tick = time.time(), self.gametime()
        stopped = not_run = False
        try:
            for self.lineno, line in lines:
                word, _, args = line.partition(" ")
                self.guarded(self.check, word, args)
        except Abort:
            stopped = not_run = True
            self.out(f"!!! NOT RUN: {self.file} has an error in line {self.lineno}")
        if not stopped:
            self.rcon.command(f"tick rate {rate}")
            players = set()
            remaining = len(lines)
            try:
                for self.lineno, line in lines:
                    remaining -= 1
                    match = re.match(r"journeymen debug gamemode (\S+) ", line)
                    if match:
                        players.add(match.group(1))
                    self.guarded(self.run_line, line)
            except Abort:
                stopped = True
                self.out(f"!!! STOPPED {self.file} at line {self.lineno}, skipping its {remaining} remaining line(s)")
                self.reset(players)
        self.rcon.command("tick sprint stop")
        self.rcon.command("tick rate 20")
        elapsed, ticks = time.time() - file_start, self.gametime() - file_tick
        time.sleep(0.5)
        if self.pattern:
            self.out(f"--- server log ({self.file}) ---")
            log_lines, _ = self.log.read(file_mark)
            for line in log_lines:
                if self.pattern.search(line):
                    self.out(line)
        summary = f"{self.file}: {elapsed:.0f} s" + (f", {ticks} ticks" if self.has_gametime else "")
        if not_run:
            summary += f", NOT RUN (error in line {self.lineno})"
        elif stopped:
            summary += f", STOPPED at line {self.lineno}"
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
