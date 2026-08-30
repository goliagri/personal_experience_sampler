"""Sync procedure (spec §8.4) over a ``CloudStore``.

Every step is idempotent; a failed step is simply retried on the next
trigger. Sync never blocks the ping path — the engine works entirely from the
local database, and this module only moves bytes between it and the cloud.

Change detection uses the store's opaque ``etag`` (content hash for the local
folder backend, ``modifiedTime`` for Drive later), remembered in ``sync_meta``.
"""

from __future__ import annotations

import hashlib
import io
import json
import zipfile
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

from .core.export import columns_json, export_csv
from .core.timeutil import fmt_utc, parse_utc
from .engine import Engine
from .store.cloud import CloudStore

TERMINAL = {"answered", "skipped", "expired", "retracted", "suppressed"}

APP_VERSION = "0.3.0"
SNAPSHOT_KEEP = 12  # §9: 12 weekly + 12 monthly
PRIMARY_STALE_S = 14 * 86400  # §9: a primary silent this long can be replaced
FORMAT_VERSION = 1


class Syncer:
    def __init__(self, engine: Engine, store: CloudStore, platform: str = "desktop"):
        self.engine = engine
        self.db = engine.db
        self.store = store
        self.platform = platform

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
        self._snapshot(result)
        result["backfilled"] = len(self.engine.backfill_now())
        self.db.kv_set("sync_meta", "last_sync", fmt_utc(self.engine.clock.now()))
        return result

    # -- restore (§8.6) ---------------------------------------------------

    def restore(self) -> dict:
        """Rebuild a lost or damaged cloud folder from this device's cache,
        then run a normal sync. Creates files only: another device's cloud
        file is never overwritten — lines it lacks go to ``restored/``."""
        result: dict = {"uploaded": [], "restored": [], "docs": []}
        dev = self.engine.device_id
        cloud_files = set(self.store.list("events"))
        for source in self.db.event_files():
            if not source.startswith("events/"):
                continue  # restored/ fragments are re-derived below
            _events, file_dev, name = source.split("/")
            lines = self.db.file_lines(source)
            data = _jsonl(lines)
            if source not in cloud_files:
                if self.store.put_if_absent(source, data):
                    result["uploaded"].append(source)
                continue
            raw = self.store.get(source) or b""
            present = set(raw.decode().split("\n"))
            extra = [line for line in lines if line not in present]
            if not extra:
                continue
            if file_dev == dev:
                # Own log: authoritative, and the normal upload path
                # overwrites it wholesale anyway.
                self.store.put(source, data)
                result["uploaded"].append(source)
            else:
                path = f"restored/{dev}/{file_dev}/{name}"
                self.store.put(path, _jsonl(extra))
                result["restored"].append(path)

        for doc in self.db.config_history():
            path = f"config/history/config_v{doc['version']:04}.json"
            if self.store.put_if_absent(path, _dumps(doc)):
                result["docs"].append(path)
        latest = self.db.latest_config()
        if latest and self.store.put_if_absent("config/current.json", _dumps(latest)):
            result["docs"].append("config/current.json")
        for (survey_id, version), doc in self.db.all_surveys().items():
            path = f"surveys/{survey_id}/v{version}.json"
            if self.store.put_if_absent(path, _dumps(doc)):
                result["docs"].append(path)
        if self._ensure_manifest():
            result["docs"].append("manifest.json")
        result["sync"] = self.sync()
        return result

    def _ensure_manifest(self) -> bool:
        doc = {
            "format_version": FORMAT_VERSION,
            "created_at": fmt_utc(self.engine.clock.now()),
            "install_id": self.engine.device_id,
        }
        return self.store.put_if_absent("manifest.json", _dumps(doc))

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
                self._reconcile_history(cloud["version"], result)
                self.engine.apply_config(cloud)
                result["applied_config"] = cloud["version"]
        elif cloud is not None and local is None:
            self._reconcile_history(cloud["version"], result)
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

    def _reconcile_history(self, before: int, result: dict) -> None:
        """Cache the cloud's config versions below ``before`` for piecewise
        scheduling. A local doc that disagrees with the cloud's same-version
        doc lost a race it never saw (another device's version chain moved
        past it, §8.2): archive it as a conflict and adopt the cloud lineage
        — silently keeping it would schedule from a history no other device
        has."""
        local_by_v = {d["version"]: d for d in self.db.config_history()}
        for v in range(1, before):
            raw = self.store.get(f"config/history/config_v{v:04}.json")
            if raw is None:
                continue
            cloud_doc = json.loads(raw)
            local_doc = local_by_v.get(v)
            if local_doc == cloud_doc:
                continue
            if local_doc is not None:
                self._archive_rejected(local_doc, result)
                result["warnings"].append(
                    f"config v{v} from {local_doc['written_by']} rejected"
                    f" (cloud lineage moved past it); kept {cloud_doc['written_by']}'s"
                )
            self.db.upsert_config(cloud_doc)

    def _archive_rejected(self, doc: dict, result: dict) -> None:
        stamp = doc["written_at"].replace(":", "").replace("-", "")
        conflict_path = (
            f"config/conflicts/config_v{doc['version']:04}"
            f"_rejected_{doc['written_by']}_{stamp}.json"
        )
        self.store.put_if_absent(conflict_path, _dumps(doc))
        result["conflicts"].append(conflict_path)

    def _resolve_conflict(self, local: dict, cloud: dict, result: dict) -> None:
        """Two writers branched the same base (§8.2): later written_at wins."""
        loser, winner = sorted([local, cloud], key=lambda d: d["written_at"])
        self._archive_rejected(loser, result)
        result["warnings"].append(
            f"config v{loser['version']} from {loser['written_by']} rejected"
            f" (concurrent edit); kept {winner['written_by']}'s"
        )
        if winner is cloud:
            self.engine.apply_config(cloud)
        else:
            self.store.put("config/current.json", _dumps(local))
        # The loser may have uploaded its doc as this version's history file
        # already; the lineage must record the winner.
        self.store.put(
            f"config/history/config_v{winner['version']:04}.json", _dumps(winner)
        )
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
            lines = self.db.month_lines(dev, month)
            self.store.put(path, _jsonl(lines))
            # Mark only the snapshot uploaded: the engine thread may append
            # to this month while the put is in flight, and those events must
            # stay unsynced for the next trigger.
            self.db.mark_month_synced(dev, month, upto_line=len(lines))
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
            # Split on newlines only (Android lines may contain raw non-ASCII
            # such as U+2028, which str.splitlines would split on) and drop
            # blanks; a malformed file is skipped with a warning — its etag
            # stays unremembered so it is retried, and it cannot block the
            # remaining files.
            lines = [line for line in raw.decode().split("\n") if line]
            try:
                affected = self.db.import_file(path, lines)
            except (ValueError, KeyError, TypeError) as exc:
                result["warnings"].append(f"skipped {path}: malformed line ({exc})")
                continue
            result["imported"].append(path)
            self._remember_etag(path)
            for sample_id in affected:
                self.engine.refold(sample_id)
                changed_streams.add(sample_id.split("|", 1)[0])
                types = {e["ev"] for _f, _l, e in self.db.events_for_sample(sample_id)}
                if types & TERMINAL:
                    self.engine.notifier.cancel(sample_id)
                    continue
                # Retroactive expiry (§8.4 step 3), independent of the
                # backfill watermark — decided on event types, not the folded
                # status: fired + unobserved folds to unobserved (precedence),
                # yet an observed sample whose window has passed must still
                # be closed out as expired.
                if "fired" not in types:
                    continue
                stream_id, scheduled_iso = sample_id.split("|", 1)
                scheduled = parse_utc(scheduled_iso)
                expiry_s = (
                    self.engine.effective_settings(stream_id, scheduled)["expiry_minutes"]
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
                            "stream": stream_id,
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
        others = self._other_devices(dev)
        role = self.db.kv_get("device", "role") or ""
        # §9: claim primary when no live primary exists (first run, or the
        # previous primary has been silent for 14 days). Two simultaneous
        # claimants are resolved by lexicographic device_id at the next
        # sync that sees both.
        if role != "primary" and not self._live_primary(others, now):
            role = "primary"
        if role == "primary" and any(
            d["device_id"] < dev for d in others if self._is_live_primary(d, now)
        ):
            role = ""
        self.db.kv_set("device", "role", role)
        result["role"] = role
        doc = {
            "device_id": dev,
            "name": self.db.kv_get("device", "name") or dev,
            "platform": self.platform,
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
        self._ensure_manifest()

    def _other_devices(self, own_dev: str) -> list[dict]:
        docs = []
        for path in self.store.list("devices"):
            raw = self.store.get(path)
            if not raw:
                continue
            doc = json.loads(raw)
            if doc.get("device_id") and doc["device_id"] != own_dev:
                docs.append(doc)
        return docs

    @classmethod
    def _live_primary(cls, others: list[dict], now: int) -> bool:
        return any(cls._is_live_primary(d, now) for d in others)

    @staticmethod
    def _is_live_primary(doc: dict, now: int) -> bool:
        return (
            doc.get("role") == "primary"
            and now - parse_utc(doc.get("last_sync", "1970-01-01T00:00:00Z")) < PRIMARY_STALE_S
        )

    # -- step 5b: snapshots (§9) -----------------------------------------

    def _snapshot(self, result: dict) -> None:
        """Primary only: zip the folder on the first sync after Sunday 03:00
        local, promote the month's first weekly zip to monthly, prune to
        12 + 12. Keyed by the Sunday's date, so retries are idempotent."""
        if self.db.kv_get("device", "role") != "primary":
            return
        config = self.db.latest_config()
        if not config:
            return
        sunday = _last_sunday_0300(self.engine.clock.now(), config["timezone"])
        weekly = f"snapshots/weekly/{sunday}.zip"
        if self.store.metadata(weekly) is None:
            data = self._zip_folder()
            if self.store.put_if_absent(weekly, data):
                result["snapshot"] = weekly
            if self.store.put_if_absent(f"snapshots/monthly/{sunday[:7]}.zip", data):
                result["snapshot_monthly"] = f"snapshots/monthly/{sunday[:7]}.zip"
        for prefix in ("snapshots/weekly", "snapshots/monthly"):
            zips = sorted(p for p in self.store.list(prefix) if p.endswith(".zip"))
            for path in zips[:-SNAPSHOT_KEEP]:
                self.store.delete(path)

    def _zip_folder(self) -> bytes:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            for path in self.store.list(""):
                if path.startswith("snapshots/"):
                    continue
                data = self.store.get(path)
                if data is None:
                    continue
                info = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(info, data)
        return buf.getvalue()


def _last_sunday_0300(now: int, timezone: str) -> str:
    """Local date (YYYY-MM-DD) of the most recent Sunday 03:00 <= now."""
    local = datetime.fromtimestamp(now, UTC).astimezone(ZoneInfo(timezone))
    days_since_sunday = (local.weekday() + 1) % 7
    sunday = local.date() - timedelta(days=days_since_sunday)
    if days_since_sunday == 0 and local.time() < time(3, 0):
        sunday -= timedelta(days=7)
    return sunday.isoformat()


def _jsonl(lines: list[str]) -> bytes:
    return ("\n".join(lines) + "\n").encode()


def _dumps(doc: dict) -> bytes:
    return (json.dumps(doc, indent=2) + "\n").encode()
