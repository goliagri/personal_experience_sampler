"""Entry point: ``python -m pes`` starts the tray-resident desktop client.

Sync uses a local folder store by default; the Google Drive backend is
enabled from Settings after connecting an OAuth client (``--client-secret``
records where the client JSON lives — the file itself is only ever read,
never copied or uploaded).
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


def default_data_dir() -> Path:
    if sys.platform.startswith("win"):
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData/Roaming"))
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share"))
    return base / "pes"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pes", description="Personal Experience Sampler")
    parser.add_argument(
        "--data-dir", type=Path, default=default_data_dir(), help="local database location"
    )
    parser.add_argument(
        "--cloud-dir",
        type=Path,
        default=None,
        help="cloud folder path (local-folder store; default <data-dir>/cloud)",
    )
    parser.add_argument(
        "--client-secret",
        type=Path,
        default=None,
        help="path to the Google OAuth 'installed' client JSON (remembered)",
    )
    args = parser.parse_args(argv)

    data_dir: Path = args.data_dir
    data_dir.mkdir(parents=True, exist_ok=True)
    cloud_dir: Path = args.cloud_dir or (data_dir / "cloud")

    from .device import get_device_id
    from .store import Db
    from .tray import attach_tray
    from .ui.app import App

    bootstrap_db = Db(data_dir / "pes.sqlite")
    device_id = get_device_id(bootstrap_db)
    if args.client_secret:
        bootstrap_db.kv_set(
            "device", "drive_client_secret", str(args.client_secret.resolve())
        )
    bootstrap_db.close()

    app = App(data_dir=data_dir, cloud_dir=cloud_dir, device_id=device_id)
    attach_tray(app)
    app.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
