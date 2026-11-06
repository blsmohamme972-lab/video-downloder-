"""StreamSave's supervised, bounded HTTP transfer adapter.

yt-dlp still extracts/selects formats and merges the original streams. Only
ordinary, known-size YouTube HTTP files use this adapter. Manifest downloaders
and files of unknown size retain yt-dlp's native implementation.

The .part file is ALWAYS a contiguous source prefix. Temporary pieces are
verified before an ordered append; no sparse-file bitmap or fragment-index
migration is needed when resuming a download made by StreamSave 1.0.
"""

import concurrent.futures
import contextlib
import ctypes
import os
import re
import shutil
import signal
import sys
import threading
import time
from collections import deque


PIECE_SIZE = 8 * 1024 * 1024
CONNECTIONS = 8
BUFFER_SIZE = 64 * 1024
CONTENT_RANGE = re.compile(r"bytes (\d+)-(\d+)/(\d+)\Z", re.IGNORECASE)


class RangeResponseError(Exception):
    """The server did not return the exact representation/range requested."""


class RangeUnsupported(RangeResponseError):
    pass


def install_parallel_http():
    from yt_dlp.downloader import PROTOCOL_MAP
    from yt_dlp.downloader.http import HttpFD
    from yt_dlp.networking import Request
    from yt_dlp.networking.exceptions import HTTPError, TransportError
    from yt_dlp.utils import DownloadError, ReExtractInfo
    from yt_dlp.utils.networking import HTTPHeaderDict

    original_download = HttpFD.real_download
    refreshed = set()

    def parallel_download(self, filename, info):
        size = info.get('filesize')
        headers = HTTPHeaderDict(info.get('http_headers'))
        eligible = (
            (info.get('extractor_key') == 'Youtube' or info.get('extractor') == 'youtube')
            and isinstance(size, int) and size > 0
            and str(info.get('url', '')).startswith(('http://', 'https://'))
            and filename != '-' and not hasattr(filename, 'write')
            and not info.get('is_live') and not info.get('request_data')
            and not headers.get('Range') and not info.get('impersonate')
            and not self.params.get('test') and not self.params.get('nopart')
        )
        if not eligible:
            return original_download(self, filename, info)

        tmp = self.temp_name(filename)
        resume = os.path.getsize(tmp) if os.path.isfile(tmp) else 0
        if resume > size:
            raise DownloadError('The selected stream changed size. Your partial download was kept; choose this video again.')
        if resume and not self.params.get('continuedl', True):
            # The app always requests --continue. Never silently discard a prefix.
            raise DownloadError('Resume is required for this saved partial download.')

        headers['Accept-Encoding'] = 'identity'
        validator = [None, None]
        stop = threading.Event()
        state_lock = threading.Lock()
        observed = {}
        committed = [resume]
        start_time = time.monotonic()
        samples = deque([(start_time, resume)])
        last_report = [0.0]
        pieces = tmp + '.streamsave-pieces'

        def open_range(first, last):
            request_headers = HTTPHeaderDict(headers)
            request_headers['Range'] = f'bytes={first}-{last}'
            if validator[0]:
                request_headers['If-Range'] = validator[0]
            response = self.ydl.urlopen(Request(info['url'], headers=request_headers))
            try:
                if response.status == 200:
                    full_length = response.headers.get('Content-Length')
                    if full_length is not None and int(full_length) != size:
                        raise RangeResponseError('The server returned a different full-file size.')
                    raise RangeUnsupported('The server ignored the byte range request.')
                match = CONTENT_RANGE.fullmatch(response.headers.get('Content-Range', '').strip())
                if response.status != 206 or not match or tuple(map(int, match.groups())) != (first, last, size):
                    raise RangeResponseError('The server returned an unexpected byte range or stream size.')
                length = response.headers.get('Content-Length')
                if length is not None and int(length) != last - first + 1:
                    raise RangeResponseError('The server returned an unexpected range length.')
                if response.headers.get('Content-Encoding', 'identity').lower() not in ('', 'identity'):
                    raise RangeResponseError('The server returned compressed range data.')
                if validator[1] and response.headers.get('ETag') not in (None, validator[1]):
                    raise RangeResponseError('The source changed during this download.')
                return response
            except BaseException:
                response.close()
                raise

        def read_exact(response, expected, output=None, offset=None):
            received = 0
            small = bytearray() if output is None else None
            while received < expected:
                if stop.is_set():
                    raise InterruptedError('Transfer stopped')
                block = response.read(min(BUFFER_SIZE, expected - received))
                if not block:
                    raise TransportError('The server closed a range before it was complete.')
                if output is None:
                    small.extend(block)
                else:
                    output.write(block)
                received += len(block)
                if offset is not None:
                    with state_lock:
                        # Count each source byte once even when a request retries.
                        observed[offset] = max(observed.get(offset, 0), received)
            if response.read(1):
                raise RangeResponseError('The server returned extra bytes beyond the requested range.')
            return bytes(small) if small is not None else None

        def retry_request(operation):
            retries = min(5, max(0, int(self.params.get('retries') or 0)))
            for attempt in range(retries + 1):
                try:
                    return operation()
                except (HTTPError, TransportError) as error:
                    if isinstance(error, HTTPError) and error.status not in (408, 429, 500, 502, 503, 504):
                        raise
                    if attempt == retries or stop.is_set():
                        raise
                    if stop.wait(min(2 ** attempt, 8)):
                        raise InterruptedError('Transfer stopped') from error

        def probe():
            first = max(0, resume - BUFFER_SIZE) if resume else 0
            last = resume - 1 if resume else 0
            with contextlib.closing(open_range(first, last)) as response:
                data = read_exact(response, last - first + 1)
                etag = response.headers.get('ETag')
                validator[1] = etag
                validator[0] = etag if etag and not etag.startswith('W/') else response.headers.get('Last-Modified')
            if resume:
                with open(tmp, 'rb') as partial:
                    partial.seek(first)
                    if partial.read(len(data)) != data:
                        raise RangeResponseError('The source no longer matches your saved partial download.')

        def fetch(first, last):
            path = os.path.join(pieces, f'{first}-{last}')

            def attempt():
                with contextlib.closing(open_range(first, last)) as response:
                    with open(path, 'wb') as output:
                        read_exact(response, last - first + 1, output, first)
                        output.flush()
                if os.path.getsize(path) != last - first + 1:
                    raise RangeResponseError('A temporary range has the wrong length.')
                return path

            return retry_request(attempt)

        def report(force=False):
            now = time.monotonic()
            if not force and now - last_report[0] < 0.4:
                return
            with state_lock:
                done = min(size, committed[0] + sum(observed.values()))
            samples.append((now, done))
            while len(samples) > 2 and samples[1][0] < now - 4.0:
                samples.popleft()
            elapsed = now - samples[0][0]
            speed = max(0.0, (done - samples[0][1]) / elapsed) if elapsed > 0.01 else None
            eta = (size - done) / speed if speed else None
            self._hook_progress({
                'status': 'downloading', 'downloaded_bytes': done, 'total_bytes': size,
                'tmpfilename': tmp, 'filename': filename, 'speed': speed, 'eta': eta,
                'elapsed': now - start_time, 'ctx_id': info.get('ctx_id'),
            }, info)
            last_report[0] = now

        pool = None
        try:
            try:
                retry_request(probe)
            except RangeUnsupported:
                if resume:
                    raise DownloadError('This server cannot resume the saved byte range. The existing partial download was kept.')
                self.to_screen('[streamsave] Range requests unavailable; using the standard transfer.')
                result = original_download(self, filename, info)
                if result and (not os.path.isfile(filename) or os.path.getsize(filename) != size):
                    # A server can return a short error page with status200 and
                    # no declared length. Never publish that as known-size media.
                    # This branch starts without an existing prefix or final file.
                    if os.path.isfile(filename):
                        os.remove(filename)
                    raise DownloadError('The server returned an incomplete media file. The invalid result was not saved.')
                return result

            if os.path.isdir(pieces):
                # Only the contiguous .part is a cross-attempt checkpoint.
                # Discard uncommitted temporary ranges; never discard the prefix.
                shutil.rmtree(pieces)
            os.makedirs(pieces, exist_ok=True)
            self.report_destination(filename)
            if resume:
                self.report_resuming_byte(resume)
            self.to_screen(f'[streamsave] Parallel transfer: up to {CONNECTIONS} connections, 8 MiB ranges.')
            pool = concurrent.futures.ThreadPoolExecutor(max_workers=CONNECTIONS)
            pending = deque()
            next_offset = resume

            def schedule():
                nonlocal next_offset
                if next_offset >= size:
                    return
                first = next_offset
                last = min(size - 1, first + PIECE_SIZE - 1)
                next_offset = last + 1
                pending.append((first, last, pool.submit(fetch, first, last)))

            for _ in range(CONNECTIONS):
                schedule()
            with open(tmp, 'ab', buffering=0) as destination:
                while pending:
                    first, last, future = pending.popleft()
                    while True:
                        try:
                            path = future.result(timeout=0.4)
                            break
                        except concurrent.futures.TimeoutError:
                            report()
                    # Only verified, complete pieces enter the contiguous file.
                    # If killed during this copy, every appended byte is still a
                    # valid prefix; the next attempt resumes at its actual length.
                    if destination.tell() != first or os.path.getsize(path) != last - first + 1:
                        raise RangeResponseError('The saved download position is inconsistent.')
                    with open(path, 'rb') as piece:
                        while block := piece.read(BUFFER_SIZE):
                            remaining = memoryview(block)
                            while remaining:
                                written = destination.write(remaining)
                                if not written:
                                    raise OSError('Cannot write the downloaded range.')
                                remaining = remaining[written:]
                    if destination.tell() != last + 1:
                        raise RangeResponseError('The saved range has the wrong length.')
                    os.fsync(destination.fileno())
                    with state_lock:
                        committed[0] = last + 1
                        observed.pop(first, None)
                    os.remove(path)
                    schedule()
                    report()
            report(True)
            if os.path.getsize(tmp) != size:
                raise RangeResponseError('The completed stream has the wrong size.')
            self.try_rename(tmp, filename)
            self._hook_progress({
                'status': 'finished', 'downloaded_bytes': size, 'total_bytes': size,
                'filename': filename, 'ctx_id': info.get('ctx_id'),
            }, info)
            return True
        except HTTPError as error:
            key = (info.get('id'), info.get('format_id'))
            if error.status in (403, 410) and key not in refreshed:
                refreshed.add(key)
                raise ReExtractInfo('Refreshing an expired stream address; saved bytes were kept', expected=True) from error
            raise
        except RangeResponseError as error:
            raise DownloadError(f'{error} Your saved partial download was kept.') from error
        finally:
            stop.set()
            if pool:
                pool.shutdown(wait=True, cancel_futures=True)
            # SIGKILL can interrupt cleanup. The next attempt removes this exact
            # private directory before starting workers.
            if os.path.isdir(pieces):
                shutil.rmtree(pieces, ignore_errors=True)

    class StreamSaveHttpFD(HttpFD):
        real_download = parallel_download

    # FragmentFD imports/instantiates its own HttpQuietDownloader subclass.
    # Keeping HttpFD itself untouched prevents nested 8 x 8 worker pools.
    PROTOCOL_MAP['http'] = StreamSaveHttpFD
    PROTOCOL_MAP['https'] = StreamSaveHttpFD


def _prctl(option, value):
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(option, value, 0, 0, 0) != 0:
        number = ctypes.get_errno()
        raise OSError(number, os.strerror(number))


def run_supervised(worker):
    """Keep Java's Process alive until its entire transfer group is stopped.

    The supervisor is a Linux subreaper: orphan grandchildren (e.g. FFmpeg)
    are reaped here, so a completed Process.waitFor is a reliable ownership
    boundary for pause/resume/delete and service replacement.
    """
    original_parent = os.getppid()
    _prctl(36, 1)  # PR_SET_CHILD_SUBREAPER
    child = [0]
    stopped = [False]

    def stop_group(_signal=None, _frame=None):
        stopped[0] = True
        if child[0]:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(child[0], signal.SIGKILL)
            # Covers cancellation between fork() and the child's setsid().
            with contextlib.suppress(ProcessLookupError):
                os.kill(child[0], signal.SIGKILL)

    signal.signal(signal.SIGTERM, stop_group)
    signal.signal(signal.SIGINT, stop_group)
    _prctl(1, signal.SIGTERM)  # PR_SET_PDEATHSIG
    if os.getppid() != original_parent or stopped[0]:
        return 130
    ready_read, ready_write = os.pipe()
    go_read, go_write = os.pipe()
    supervisor_pid = os.getpid()
    sys.stdout.flush()
    sys.stderr.flush()
    child[0] = os.fork()
    if child[0] == 0:
        os.close(ready_read)
        os.close(go_write)
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        os.setsid()
        _prctl(1, signal.SIGKILL)
        if os.getppid() != supervisor_pid:
            os._exit(130)
        os.write(ready_write, b'1')
        os.close(ready_write)
        if os.read(go_read, 1) != b'1':
            os._exit(130)
        os.close(go_read)
        try:
            worker()
        except SystemExit as error:
            code = error.code if isinstance(error.code, int) else (1 if error.code else 0)
        except BaseException:
            import traceback
            traceback.print_exc()
            code = 1
        else:
            code = 0
        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(code)
    os.close(ready_write)
    os.close(go_read)
    initialized = os.read(ready_read, 1) == b'1'
    os.close(ready_read)
    if initialized and not stopped[0]:
        # Java records the group before releasing the worker. If its Process
        # is cancelled during startup, no unowned downloader can start.
        print(f'STREAMSAVE_WORKER:{child[0]}:{supervisor_pid}', flush=True)
        allowed = sys.stdin.readline() == 'READY\n'
    else:
        allowed = False
    if stopped[0] or not allowed:
        stop_group()
    else:
        os.write(go_write, b'1')
    os.close(go_write)
    _, status = os.waitpid(child[0], 0)
    with contextlib.suppress(ProcessLookupError):
        os.killpg(child[0], signal.SIGKILL)
    # Reap any descendant adopted after its parent exits. The worker group was
    # killed first, and no new job starts before this supervisor exits.
    while True:
        try:
            os.waitpid(-1, 0)
        except ChildProcessError:
            break
    return 130 if stopped[0] else os.waitstatus_to_exitcode(status)


def main():
    engine, *arguments = sys.argv[1:]

    def worker():
        if arguments in (['--streamsave-supervision-test'], ['--streamsave-supervision-stall-test'],
                         ['--streamsave-supervision-crash-test']):
            import subprocess
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
            if arguments == ['--streamsave-supervision-stall-test']:
                # Instrumentation verifies Java's independent watchdog when the
                # supervisor cannot handle SIGTERM or close its output pipe.
                os.kill(os.getppid(), signal.SIGSTOP)
            print(f'STREAMSAVE_TEST_CHILD:{child.pid}', flush=True)
            if arguments == ['--streamsave-supervision-crash-test']:
                # The Java owner must close descendants even if the supervisor
                # itself exits before cleaning its group.
                os.kill(os.getppid(), signal.SIGKILL)
            child.wait()
            return
        sys.path.insert(0, engine)
        import yt_dlp
        install_parallel_http()
        yt_dlp.main(arguments)

    return run_supervised(worker)


if __name__ == '__main__':
    sys.exit(main())
