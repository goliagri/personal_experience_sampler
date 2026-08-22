"""Local SQLite database (spec §5.3).

Holds this device's own event log (authoritative), imported rows from other
devices (cache), the folded ``samples`` view, the materialized ``schedule``,
cloud-document caches, the tag vocabulary, and sync bookkeeping.

Events are stored with their exact JSONL line text (``payload_json``) plus a
``(source_file, line)`` position so the fold's deduplication order matches the
cloud files byte-for-byte. Own events are appended with the next line number
of their month file; imported files are replaced wholesale.
"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY,
    dev TEXT NOT NULL,
    ev TEXT NOT NULL,
    sample TEXT,
    stream TEXT,
    t TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,
    source_file TEXT NOT NULL,
    line INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_sample ON events(sample);
CREATE INDEX IF NOT EXISTS idx_events_source ON events(source_file, line);
CREATE INDEX IF NOT EXISTS idx_events_ev ON events(ev);

CREATE TABLE IF NOT EXISTS samples (
    sample TEXT PRIMARY KEY,
    stream TEXT NOT NULL,
    scheduled_utc TEXT NOT NULL,
    status TEXT NOT NULL,
    row_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_samples_stream ON samples(stream, scheduled_utc);

CREATE TABLE IF NOT EXISTS schedule (
    sample TEXT PRIMARY KEY,
    stream TEXT NOT NULL,
    scheduled_utc TEXT NOT NULL,
    config_v INTEGER NOT NULL,
    suppressed_reason TEXT,
    idx INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'planned'
);
CREATE INDEX IF NOT EXISTS idx_schedule_time ON schedule(scheduled_utc);

CREATE TABLE IF NOT EXISTS config_cache (
    version INTEGER PRIMARY KEY,
    doc_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS survey_cache (
    survey_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    doc_json TEXT NOT NULL,
    PRIMARY KEY (survey_id, version)
);
CREATE TABLE IF NOT EXISTS kv (
    ns TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (ns, key)
);
CREATE TABLE IF NOT EXISTS tag_vocab (
    vocab TEXT NOT NULL,
    tag TEXT NOT NULL,
    last_used TEXT NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (vocab, tag)
);
"""

# kv namespaces: "device" (device_id, name, role), "sync_meta" (etags,
# last_materialized_at, export hashes), "state" (state.json document).


class Db:
    def __init__(self, path: Path | str):
        # One connection per thread (WAL allows a writer alongside readers);
        # the UI thread and the sync worker each open their own Db.
        self.conn = sqlite3.connect(str(path))
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute("PRAGMA foreign_keys=ON")
        self.conn.executescript(_SCHEMA)
        self.conn.commit()

    def close(self) -> None:
        self.conn.close()

    # -- events -----------------------------------------------------------

    @staticmethod
    def month_file(dev: str, t: str) -> str:
        return f"events/{dev}/{t[:7]}.jsonl"

    @staticmethod
    def dumps_line(ev: dict) -> str:
        return json.dumps(ev, separators=(",", ":"), sort_keys=True)

    def append_own_event(self, ev: dict) -> None:
        """Append one event authored by this device (assigns file position)."""
        source = self.month_file(ev["dev"], ev["t"])
        (line,) = self.conn.execute(
            "SELECT COUNT(*) FROM events WHERE source_file = ?", (source,)
        ).fetchone()
        self.conn.execute(
            "INSERT INTO events (dev, ev, sample, stream, t, payload_json,"
            " synced, source_file, line) VALUES (?,?,?,?,?,?,0,?,?)",
            (
                ev["dev"],
                ev["ev"],
                ev.get("sample"),
                ev.get("stream"),
                ev["t"],
                self.dumps_line(ev),
                source,
                line,
            ),
        )
        self.conn.commit()

    def import_file(self, source_file: str, raw_lines: list[str]) -> list[str]:
        """Replace the cached copy of another device's file.

        Returns the sample ids whose event sets changed (for refolding).
        """
        before = {
            (line, payload)
            for line, payload in self.conn.execute(
                "SELECT line, payload_json FROM events WHERE source_file = ?",
                (source_file,),
            )
        }
        affected: set[str] = set()
        for line_no, raw in enumerate(raw_lines):
            if (line_no, raw) in before:
                continue
            ev = json.loads(raw)
            if ev.get("sample"):
                affected.add(ev["sample"])
        for _line, payload in before - {
            (i, raw) for i, raw in enumerate(raw_lines)
        }:
            ev = json.loads(payload)
            if ev.get("sample"):
                affected.add(ev["sample"])

        self.conn.execute("DELETE FROM events WHERE source_file = ?", (source_file,))
        rows = []
        for line_no, raw in enumerate(raw_lines):
            ev = json.loads(raw)
            rows.append(
                (
                    ev["dev"],
                    ev["ev"],
                    ev.get("sample"),
                    ev.get("stream"),
                    ev["t"],
                    raw,
                    source_file,
                    line_no,
                )
            )
        self.conn.executemany(
            "INSERT INTO events (dev, ev, sample, stream, t, payload_json,"
            " synced, source_file, line) VALUES (?,?,?,?,?,?,1,?,?)",
            rows,
        )
        self.conn.commit()
        return sorted(affected)

    def events_for_sample(self, sample: str) -> list[tuple[str, int, dict]]:
        return [
            (source, line, json.loads(payload))
            for source, line, payload in self.conn.execute(
                "SELECT source_file, line, payload_json FROM events"
                " WHERE sample = ? ORDER BY source_file, line",
                (sample,),
            )
        ]

    def events_of_type(self, ev_type: str) -> list[dict]:
        return [
            json.loads(payload)
            for (payload,) in self.conn.execute(
                "SELECT payload_json FROM events WHERE ev = ? ORDER BY t", (ev_type,)
            )
        ]

    def all_sample_events(self) -> list[dict]:
        return [
            json.loads(payload)
            for (payload,) in self.conn.execute(
                "SELECT payload_json FROM events WHERE sample IS NOT NULL"
                " ORDER BY source_file, line"
            )
        ]

    def unsynced_months(self, dev: str) -> list[str]:
        return [
            source.split("/")[-1].removesuffix(".jsonl")
            for (source,) in self.conn.execute(
                "SELECT DISTINCT source_file FROM events"
                " WHERE dev = ? AND synced = 0 ORDER BY source_file",
                (dev,),
            )
        ]

    def month_lines(self, dev: str, month: str) -> list[str]:
        source = f"events/{dev}/{month}.jsonl"
        return [
            payload
            for (payload,) in self.conn.execute(
                "SELECT payload_json FROM events WHERE source_file = ?"
                " ORDER BY line",
                (source,),
            )
        ]

    def mark_month_synced(self, dev: str, month: str) -> None:
        source = f"events/{dev}/{month}.jsonl"
        self.conn.execute(
            "UPDATE events SET synced = 1 WHERE source_file = ?", (source,)
        )
        self.conn.commit()

    # -- samples (folded view) -------------------------------------------

    def upsert_sample(self, row: dict) -> None:
        self.conn.execute(
            "INSERT INTO samples (sample, stream, scheduled_utc, status, row_json)"
            " VALUES (?,?,?,?,?) ON CONFLICT(sample) DO UPDATE SET"
            " stream=excluded.stream, scheduled_utc=excluded.scheduled_utc,"
            " status=excluded.status, row_json=excluded.row_json",
            (
                row["sample"],
                row["stream"],
                row["scheduled_utc"],
                row["status"],
                json.dumps(row),
            ),
        )
        self.conn.commit()

    def sample_row(self, sample: str) -> dict | None:
        got = self.conn.execute(
            "SELECT row_json FROM samples WHERE sample = ?", (sample,)
        ).fetchone()
        return json.loads(got[0]) if got else None

    def sample_rows(
        self, stream: str | None = None, statuses: list[str] | None = None
    ) -> list[dict]:
        sql = "SELECT row_json FROM samples"
        clauses, params = [], []
        if stream:
            clauses.append("stream = ?")
            params.append(stream)
        if statuses:
            clauses.append(f"status IN ({','.join('?' * len(statuses))})")
            params.extend(statuses)
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        sql += " ORDER BY scheduled_utc"
        return [json.loads(r[0]) for r in self.conn.execute(sql, params)]

    # -- schedule ---------------------------------------------------------

    def replace_schedule(self, rows: list[dict]) -> None:
        """Rebuild the materialized horizon (states recomputed by caller)."""
        self.conn.execute("DELETE FROM schedule")
        self.conn.executemany(
            "INSERT INTO schedule (sample, stream, scheduled_utc, config_v,"
            " suppressed_reason, idx, state) VALUES (?,?,?,?,?,?,?)",
            [
                (
                    r["sample"],
                    r["stream"],
                    r["scheduled_utc"],
                    r["config_v"],
                    r["suppressed_reason"],
                    r["idx"],
                    r["state"],
                )
                for r in rows
            ],
        )
        self.conn.commit()

    def due_schedule(self, now_iso: str) -> list[dict]:
        return [
            {
                "sample": s,
                "stream": st,
                "scheduled_utc": sch,
                "config_v": v,
                "suppressed_reason": sup,
                "idx": i,
            }
            for s, st, sch, v, sup, i in self.conn.execute(
                "SELECT sample, stream, scheduled_utc, config_v,"
                " suppressed_reason, idx FROM schedule"
                " WHERE state = 'planned' AND scheduled_utc <= ?"
                " ORDER BY scheduled_utc",
                (now_iso,),
            )
        ]

    def next_planned(self, now_iso: str) -> str | None:
        got = self.conn.execute(
            "SELECT MIN(scheduled_utc) FROM schedule"
            " WHERE state = 'planned' AND scheduled_utc > ?",
            (now_iso,),
        ).fetchone()
        return got[0] if got else None

    def mark_schedule(self, sample: str, state: str) -> None:
        self.conn.execute(
            "UPDATE schedule SET state = ? WHERE sample = ?", (state, sample)
        )
        self.conn.commit()

    # -- cloud document caches -------------------------------------------

    def upsert_config(self, doc: dict) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO config_cache (version, doc_json) VALUES (?,?)",
            (doc["version"], json.dumps(doc)),
        )
        self.conn.commit()

    def config_history(self) -> list[dict]:
        return [
            json.loads(r[0])
            for r in self.conn.execute(
                "SELECT doc_json FROM config_cache ORDER BY version"
            )
        ]

    def latest_config(self) -> dict | None:
        got = self.conn.execute(
            "SELECT doc_json FROM config_cache ORDER BY version DESC LIMIT 1"
        ).fetchone()
        return json.loads(got[0]) if got else None

    def upsert_survey(self, doc: dict) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO survey_cache (survey_id, version, doc_json)"
            " VALUES (?,?,?)",
            (doc["id"], doc["version"], json.dumps(doc)),
        )
        self.conn.commit()

    def survey(self, survey_id: str, version: int) -> dict | None:
        got = self.conn.execute(
            "SELECT doc_json FROM survey_cache WHERE survey_id = ? AND version = ?",
            (survey_id, version),
        ).fetchone()
        return json.loads(got[0]) if got else None

    def all_surveys(self) -> dict[tuple[str, int], dict]:
        return {
            (sid, v): json.loads(doc)
            for sid, v, doc in self.conn.execute(
                "SELECT survey_id, version, doc_json FROM survey_cache"
            )
        }

    # -- kv (device / sync_meta / state) ---------------------------------

    def kv_get(self, ns: str, key: str) -> str | None:
        got = self.conn.execute(
            "SELECT value FROM kv WHERE ns = ? AND key = ?", (ns, key)
        ).fetchone()
        return got[0] if got else None

    def kv_set(self, ns: str, key: str, value: str) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO kv (ns, key, value) VALUES (?,?,?)",
            (ns, key, value),
        )
        self.conn.commit()

    def kv_all(self, ns: str) -> dict[str, str]:
        return dict(
            self.conn.execute("SELECT key, value FROM kv WHERE ns = ?", (ns,))
        )

    # -- tag vocabulary ---------------------------------------------------

    def bump_tags(self, vocab: str, tags: list[str], now_iso: str) -> None:
        self.conn.executemany(
            "INSERT INTO tag_vocab (vocab, tag, last_used, count) VALUES (?,?,?,1)"
            " ON CONFLICT(vocab, tag) DO UPDATE SET"
            " last_used = excluded.last_used, count = count + 1",
            [(vocab, tag, now_iso) for tag in tags],
        )
        self.conn.commit()

    def suggest_tags(self, vocab: str, prefix: str, limit: int = 8) -> list[str]:
        like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return [
            tag
            for (tag,) in self.conn.execute(
                "SELECT tag FROM tag_vocab WHERE vocab = ? AND tag LIKE ? ESCAPE '\\'"
                " ORDER BY count DESC, last_used DESC LIMIT ?",
                (vocab, like + "%", limit),
            )
        ]
