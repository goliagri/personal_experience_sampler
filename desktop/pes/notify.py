"""Desktop notifications (spec §12): best-effort, never blocking the ping path.

The engine talks to a ``Notifier``; the UI supplies one wired to the platform
(Windows toasts / ``notify-send``) with a click callback that opens the Answer
window. Tests use ``RecordingNotifier``.
"""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Callable


class Notifier:
    def notify(self, sample_id: str, title: str, body: str) -> None:  # pragma: no cover
        raise NotImplementedError

    def cancel(self, sample_id: str) -> None:  # pragma: no cover - optional
        pass


class RecordingNotifier(Notifier):
    def __init__(self) -> None:
        self.shown: list[tuple[str, str, str]] = []
        self.log: list[tuple[str, str]] = []  # ("show"|"cancel", sample_id)

    def notify(self, sample_id: str, title: str, body: str) -> None:
        self.shown.append((sample_id, title, body))
        self.log.append(("show", sample_id))

    def cancel(self, sample_id: str) -> None:
        self.log.append(("cancel", sample_id))

    def active(self) -> set[str]:
        current: set[str] = set()
        for op, sample_id in self.log:
            if op == "show":
                current.add(sample_id)
            else:
                current.discard(sample_id)
        return current


class DesktopNotifier(Notifier):
    """Fires a platform notification and always invokes ``on_ping`` so the UI
    can surface the sample even when no notification backend is available."""

    def __init__(self, on_ping: Callable[[str], None] | None = None):
        self.on_ping = on_ping
        self._notify_send = shutil.which("notify-send")
        self._toast = None
        try:  # Windows: winrt toasts, optional dependency
            from windows_toasts import Toast, WindowsToaster  # type: ignore

            self._toast = (WindowsToaster("Personal Experience Sampler"), Toast)
        except Exception:  # noqa: BLE001 - optional dependency probing
            self._toast = None

    def notify(self, sample_id: str, title: str, body: str) -> None:
        try:
            if self._toast is not None:
                toaster, toast_cls = self._toast
                toast = toast_cls()
                toast.text_fields = [title, body]
                toaster.show_toast(toast)
            elif self._notify_send:
                subprocess.run(
                    [self._notify_send, "--app-name=PES", title, body],
                    check=False,
                    timeout=5,
                )
        except Exception:  # noqa: BLE001, S110 - never block the ping path
            pass
        if self.on_ping:
            self.on_ping(sample_id)

    def cancel(self, sample_id: str) -> None:
        pass  # platform backends here have no reliable cancel; UI handles it
