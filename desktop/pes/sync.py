"""Sync procedure (spec §8.4) over a ``CloudStore``.

Every step is idempotent; a failed step is simply retried on the next
trigger. Sync never blocks the ping path — the engine works entirely from the
local database, and this module only moves bytes between it and the cloud.

Change detection uses the store's opaque ``etag`` (content hash for the local
folder backend, ``modifiedTime`` for Drive later), remembered in ``sync_meta``.
"""

from __future__ import annotations

import hashlib
import json

from .core.export import columns_json, export_csv
from .core.timeutil import fmt_utc, parse_utc
from .engine import Engine
from .store.cloud import CloudStore

TERMINAL = {"answered", "skipped", "expired", "retracted", "suppressed"}

APP_VERSION = "0.2.0"


class Syncer:
    def __init__(self, engine: Engine, store: CloudStore):
        self.engine = engine
        self.db = engine.db
        self.store = store

    # -- helpers ----------------------------------------------------------

    def _seen_etag(self, path: str) -> str | None:
        return self.db.kv_get("sync_meta", f"etag:{path}")

    def _remember_etag(self, path: str) -> None:
        meta = self.store.metadata(path)
        if meta:
            self.db.kv_set("sync_meta", f"etag:{path}", meta["etag"])

    def _changed(self, path: str) -> bool:
        meta = self.store.metadata(path)
        if meta is None:
            return False
        return meta["etag"] != self._seen_etag(path)

    # -- full sync --------------------------------------------------------

    def sync(self) -> dict:
        result: dict = {"conflicts": [], "imported": [], "exported": [], "warnings": []}
        self._sync_config(result)
        self._sync_state(result)
        self._sync_surveys(result)
        self._upload_own_months(result)
        self._import_other_devices(result)
        self._regenerate_exports(result)
        self._update_device_doc(result)
        result["backfilled"] = len(self.engine.backfill_now())
        self.db.kv_set("sync_meta", "last_sync", fmt_utc(self.engine.clock.now()))
        return result

    def lightweight_check(self) -> None:
        """Pre-notification check (§8.4): config + state metadata only."""
        result: dict = {"conflicts": [], "warnings": []}
        try:
            self._sync_config(result)
            self._sync_state(result)
        except OSError:
            pass  # offline; skip per spec

    # -- step 1: config / state / surveys --------------------------------

    def _sync_config(self, result: dict) -> None:
        path = "config/current.json"
        local = self.db.latest_config()
        cloud_raw = self.store.get(path) if self._changed(path) or local is None else None
        cloud = json.loads(cloud_raw) if cloud_raw else None

        if cloud is not None and local is not None:
            if cloud["version"] == local["version"] and cloud != local:
                self._resolve_conflict(local, cloud, result)
                return
            if cloud["version"] > local["version"]:
                self._adopt_history_between(local["version"], cloud["version"])
                self.engine.apply_config(cloud)
                result["applied_config"] = cloud["version"]
        elif cloud is not None and local is None:
            self._adopt_history_between(0, cloud["version"])
            self.engine.apply_config(cloud)
            result["applied_config"] = cloud["version"]

        # Upload anything the cloud is missing (new local versions, history).
        local = self.db.latest_config()
        if local is None:
            return
        for doc in self.db.config_history():
            hist_path = f"config/history/config_v{doc['version']:04}.json"
            if self.store.metadata(hist_path) is None:
                self.store.put(hist_path, _dumps(doc))
        cloud_meta = self.store.metadata(path)
        cloud_now = json.loads(self.store.get(path)) if cloud_meta else None
        if cloud_now is None or cloud_now["version"] < local["version"]:
            self.store.put(path, _dumps(local))
        self._remember_etag(path)

    def _adopt_history_between(self, after: int, before: int) -> None:
        """Cache intermediate config versions for piecewise scheduling."""
        for v in range(after + 1, before):
            raw = self.store.get(f"config/history/config_v{v:04}.json")
            if raw:
                self.db.upsert_config(json.loads(raw))

    def _resolve_conflict(self, local: dict, cloud: dict, result: dict) -> None:
        """Two writers branched the same base (§8.2): later written_at wins."""
        loser, winner = sorted([local, cloud], key=lambda d: d["written_at"])
        stamp = loser["written_at"].replace(":", "").replace("-", "")
        conflict_path = (
            f"config/conflicts/config_v{loser['version']:04}"
            f"_rejected_{loser['written_by']}_{stamp}.json"
        )
        self.store.put_if_absent(conflict_path, _dumps(loser))
        result["conflicts"].append(conflict_path)
        result["warnings"].append(
            f"config v{loser['version']} from {loser['written_by']} rejected"
            f" (concurrent edit); kept {winner['written_by']}'s"
        )
        if winner is cloud:
            self.engine.apply_config(cloud)
        else:
            self.store.put("config/current.json", _dumps(local))
        self._remember_etag("config/current.json")

    def _sync_state(self, result: dict) -> None:
        path = "state.json"
        local = self.engine.quiet_state()
        cloud_raw = self.store.get(path)
        cloud = json.loads(cloud_raw) if cloud_raw else None
        dirty = self.db.kv_get("state", "dirty") == "1"
        # Last-writer-wins by set_at (§8.3).
        if cloud and (not local.get("set_at") or cloud.get("set_at", "") > local.get("set_at", "")):
            self.db.kv_set("state", "state", json.dumps(cloud))
            dirty = False
            self.db.kv_set("state", "dirty", "0")
        elif dirty and local.get("set_at"):
            self.store.put(path, _dumps(local))
            self.db.kv_set("state", "dirty", "0")
        self._remember_etag(path)

    def _sync_surveys(self, result: dict) -> None:
        cached = self.db.all_surveys()
        for path in self.store.list("surveys"):
            if not path.endswith(".json"):
                continue
            survey_id = path.split("/")[1]
            version = int(path.split("/")[-1].removeprefix("v").removesuffix(".json"))
            if (survey_id, version) not in cached:
                raw = self.store.get(path)
                if raw:
                    self.db.upsert_survey(json.loads(raw))
        # Surveys are immutable per version: upload local ones the cloud lacks.
        for (survey_id, version), doc in self.db.all_surveys().items():
            self.store.put_if_absent(f"surveys/{survey_id}/v{version}.json", _dumps(doc))

    # -- step 2: upload own event months ---------------------------------

    def _upload_own_months(self, result: dict) -> None:
        dev = self.engine.device_id
        for month in self.db.unsynced_months(dev):
            path = f"events/{dev}/{month}.jsonl"
            content = ("\n".join(self.db.month_lines(dev, month)) + "\n").encode()
            self.store.put(path, content)
            self.db.mark_month_synced(dev, month)
            self._remember_etag(path)

    # -- step 3: import other devices' events ----------------------------

    def _import_other_devices(self, result: dict) -> None:
        own_prefix = f"events/{self.engine.device_id}/"
        now = self.engine.clock.now()
        paths = [
            p
            for p in self.store.list("events") + self.store.list("restored")
            if p.endswith(".jsonl") and not p.startswith(own_prefix)
        ]
        changed_streams: set[str] = set()
        for path in paths:
            if not self._changed(path):
                continue
            raw = self.store.get(path)
            if raw is None:
                continue
            lines = raw.decode().splitlines()
            affected = self.db.import_file(path, lines)
            result["imported"].append(path)
            self._remember_etag(path)
            for sample_id in affected:
                row = self.engine.refold(sample_id)
                changed_streams.add(sample_id.split("|", 1)[0])
                status = row.get("status")
                if status in TERMINAL or status == "answered":
                    self.engine.notifier.cancel(sample_id)
                elif status == "pending" and row.get("observed"):
                    # Retroactive expiry (§8.4 step 3), independent of the
                    # backfill watermark.
                    scheduled = parse_utc(row["scheduled_utc"])
                    expiry_s = (
                        self.engine.effective_settings(row["stream"], scheduled)[
                            "expiry_minutes"
                        ]
                        * 60
                    )
                    if scheduled + expiry_s < now:
                        self.engine.append_event(
                            {
                                "ev": "expired",
                                "config_v": (self.engine.config_at(scheduled) or {}).get(
                                    "version", 0
                                ),
                                "t": fmt_utc(now),
                                "dev": self.engine.device_id,
                                "sample": sample_id,
                                "stream": row["stream"],
                            }
                        )
                        self.engine.notifier.cancel(sample_id)
        result["changed_streams"] = sorted(changed_streams)

    # -- step 4: exports --------------------------------------------------

    def _regenerate_exports(self, result: dict) -> None:
        config = self.db.latest_config()
        if not config:
            return
        surveys = self.db.all_surveys()
        stream_ids = {s["id"] for s in config["streams"]} | {
            r["stream"] for r in self.db.sample_rows()
        }
        for stream_id in sorted(stream_ids):
            rows = [
                r
                for r in self.db.sample_rows(stream=stream_id)
                if r["status"] != "scheduled"
            ]
            if not rows:
                continue
            csv_bytes, columns = export_csv(rows, surveys, config["timezone"])
            digest = hashlib.sha256(csv_bytes).hexdigest()
            if digest == self.db.kv_get("sync_meta", f"export:{stream_id}"):
                continue
            self.store.put(f"exports/{stream_id}.csv", csv_bytes)
            self.store.put(f"exports/{stream_id}.columns.json", columns_json(columns))
            self.db.kv_set("sync_meta", f"export:{stream_id}", digest)
            result["exported"].append(stream_id)

    # -- step 5: device document ------------------------------------------

    def _update_device_doc(self, result: dict) -> None:
        dev = self.engine.device_id
        now = self.engine.clock.now()
        role = self.db.kv_get("device", "role")
        if role is None:
            role = "primary" if self._no_other_primary(dev) else ""
            self.db.kv_set("device", "role", role)
        doc = {
            "device_id": dev,
            "name": self.db.kv_get("device", "name") or dev,
            "platform": "desktop",
            "app_version": APP_VERSION,
            "last_sync": fmt_utc(now),
            "role": role,
        }
        # Idempotence: rewrite only when something besides last_sync changed
        # or the recorded last_sync is over an hour stale.
        path = f"devices/{dev}.json"
        prev_raw = self.store.get(path)
        if prev_raw:
            prev = json.loads(prev_raw)
            unchanged = {k: v for k, v in prev.items() if k != "last_sync"} == {
                k: v for k, v in doc.items() if k != "last_sync"
            }
            if unchanged and now - parse_utc(prev["last_sync"]) < 3600:
                return
        self.store.put(path, _dumps(doc))

    def _no_other_primary(self, own_dev: str) -> bool:
        for path in self.store.list("devices"):
            raw = self.store.get(path)
            if not raw:
                continue
            doc = json.loads(raw)
            if doc.get("device_id") != own_dev and doc.get("role") == "primary":
                return False
        return True


def _dumps(doc: dict) -> bytes:
    return (json.dumps(doc, indent=2) + "\n").encode()
