"""Desktop runtime engine (spec §6.4–6.5, §12): materialization, firing,
snooze/skip/answer/expiry, quiet mode, backfill, and refolding.

Headless by design: the tkinter UI, the tray, and the scenario tests all
drive this class. Time comes from an injected clock; notifications go to an
injected ``Notifier``. The ping path never touches the network (local-first);
sync is a separate step (``pes.sync``).
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from .clock import SystemClock
from .core.backfill import backfill as core_backfill
from .core.fold import fold_sample
from .core.scheduler import resolve_day
from .core.timeutil import fmt_utc, parse_utc
from .notify import Notifier
from .store.db import Db

DEFAULTS = {
    "snooze_minutes": 10,
    "max_snoozes": 3,
    "expiry_minutes": 60,
    "backlog_hours": 12,
    "location": "off",
}

HORIZON_S = 48 * 3600
CLOCK_JUMP_S = 300  # a gap this large between ticks means we slept


class Engine:
    def __init__(self, db: Db, device_id: str, notifier: Notifier, clock=None):
        self.db = db
        self.device_id = device_id
        self.notifier = notifier
        self.clock = clock or SystemClock()

    # -- config ----------------------------------------------------------

    def config_at(self, instant: int) -> dict | None:
        """Config version in effect at an instant (§6.1 step 1)."""
        history = self.db.config_history()
        effective = None
        for doc in history:  # ascending version
            if parse_utc(doc["effective_from"]) <= instant:
                effective = doc
        return effective or (history[0] if history else None)

    def stream_config(self, stream_id: str, instant: int) -> dict | None:
        """Stream definition at an instant. Falls back to the latest staged
        config so a just-created stream is usable (test pings, answering)
        before its effective_from; scheduling never goes through this — it
        resolves the config history itself, so effective_from still gates
        real pings."""
        for config in (self.config_at(instant), self.db.latest_config()):
            for s in (config or {}).get("streams", []):
                if s["id"] == stream_id:
                    return s
        return None

    def effective_settings(self, stream_id: str, instant: int) -> dict:
        """Cascade: stream overrides -> config defaults -> built-ins (§6.5)."""
        config = self.config_at(instant) or {}
        stream = self.stream_config(stream_id, instant) or {}
        return {**DEFAULTS, **config.get("defaults", {}), **stream.get("overrides", {})}

    def ensure_config(self, timezone: str) -> None:
        """First-run bootstrap: an empty config so the app is operable."""
        if self.db.latest_config() is not None:
            return
        now_iso = fmt_utc(self.clock.now())
        self.apply_config(
            {
                "version": 1,
                "base_version": 0,
                "written_by": self.device_id,
                "written_at": now_iso,
                "effective_from": now_iso,
                "timezone": timezone,
                "defaults": dict(DEFAULTS),
                "streams": [],
            }
        )

    def apply_config(self, doc: dict) -> None:
        """Begin using a config version: cache it, log, re-materialize."""
        self.db.upsert_config(doc)
        self.append_event(
            {
                "ev": "config_applied",
                "t": fmt_utc(self.clock.now()),
                "dev": self.device_id,
                "config_v": doc["version"],
            }
        )
        self.materialize()

    def stage_new_config(
        self,
        streams: list[dict],
        defaults: dict,
        timezone: str,
        effective_from: str,
    ) -> list[str]:
        """Create version latest+1 locally (§8.2); uploaded at next sync.

        Returns validation error codes (empty on success).
        """
        from .core.config_validation import validate_config

        base = self.db.latest_config()
        now = self.clock.now()
        doc = {
            "version": (base["version"] + 1) if base else 1,
            "base_version": base["version"] if base else 0,
            "written_by": self.device_id,
            "written_at": fmt_utc(now),
            "effective_from": effective_from,
            "timezone": timezone,
            "defaults": defaults,
            "streams": streams,
        }
        errors = validate_config(doc, list(self.db.all_surveys().keys()), fmt_utc(now))
        if errors:
            return errors
        self.apply_config(doc)
        return []

    # -- events / fold ----------------------------------------------------

    def append_event(self, ev: dict) -> None:
        self.db.append_own_event(ev)
        if ev.get("sample"):
            self.refold(ev["sample"])

    def refold(self, sample_id: str) -> dict:
        events = self.db.events_for_sample(sample_id)
        scheduled = parse_utc(sample_id.split("|", 1)[1])
        expiry = self.effective_settings(sample_id.split("|", 1)[0], scheduled)[
            "expiry_minutes"
        ]
        row = fold_sample(events, expiry)
        if row["status"] != "scheduled":
            self.db.upsert_sample(row)
        return row

    def _sample_base(self, sample_id: str, now: int) -> dict:
        return {
            "t": fmt_utc(now),
            "dev": self.device_id,
            "sample": sample_id,
            "stream": sample_id.split("|", 1)[0],
        }

    # -- quiet mode -------------------------------------------------------

    def quiet_state(self) -> dict:
        raw = self.db.kv_get("state", "state")
        return json.loads(raw) if raw else {"quiet_until": None}

    def quiet_active(self, now: int) -> bool:
        until = self.quiet_state().get("quiet_until")
        if until is None:
            return False
        if until == "indefinite":
            return True
        return now < parse_utc(until)

    def set_quiet(self, quiet_until: str | None) -> None:
        """quiet_until: ISO instant, "indefinite", or None (off)."""
        now_iso = fmt_utc(self.clock.now())
        self.db.kv_set(
            "state",
            "state",
            json.dumps(
                {"quiet_until": quiet_until, "set_by": self.device_id, "set_at": now_iso}
            ),
        )
        self.db.kv_set("state", "dirty", "1")  # push state.json at next sync
        self.append_event(
            {
                "ev": "quiet_changed",
                "t": now_iso,
                "dev": self.device_id,
                "quiet_until": quiet_until,
            }
        )

    # -- materialization (§6.4) ------------------------------------------

    def _stream_ids(self) -> list[str]:
        ids: list[str] = []
        for doc in self.db.config_history():
            for s in doc["streams"]:
                if s["id"] not in ids:
                    ids.append(s["id"])
        return ids

    def _local_days(self, start: int, end: int) -> list:
        config = self.config_at(end) or self.config_at(start)
        tz = ZoneInfo(config["timezone"]) if config else ZoneInfo("UTC")

        first = datetime.fromtimestamp(start, UTC).astimezone(tz).date()
        last = datetime.fromtimestamp(end, UTC).astimezone(tz).date()
        days = []
        day = first
        while day <= last:
            days.append(day)
            day += timedelta(days=1)
        return days

    def _resolved_window(self, start: int, end: int) -> list[dict]:
        """Resolved candidates with scheduled_utc in [start, end)."""
        history = self.db.config_history()
        if not history:
            return []
        out: list[dict] = []
        for day in self._local_days(start, end):
            for stream_id in self._stream_ids():
                for r in resolve_day(history, stream_id, day):
                    if start <= r.scheduled_utc < end:
                        iso = fmt_utc(r.scheduled_utc)
                        out.append(
                            {
                                "sample": f"{stream_id}|{iso}",
                                "stream": stream_id,
                                "scheduled_utc": iso,
                                "config_v": r.config_v,
                                "suppressed_reason": r.suppressed_reason,
                                "idx": r.index,
                            }
                        )
        out.sort(key=lambda r: r["scheduled_utc"])
        return out

    def materialize(self) -> None:
        """Rebuild the 48 h schedule horizon from the config history."""
        now = self.clock.now()
        rows = []
        for r in self._resolved_window(now, now + HORIZON_S):
            events = self.db.events_for_sample(r["sample"])
            types = {ev["ev"] for _f, _l, ev in events}
            done = bool(
                types & {"fired", "answered", "skipped", "expired", "retracted", "suppressed"}
            )
            rows.append({**r, "state": "done" if done else "planned"})
        self.db.replace_schedule(rows)
        config = self.config_at(now)
        if config:
            tz = ZoneInfo(config["timezone"])

            local_day = datetime.fromtimestamp(now, UTC).astimezone(tz).date()
            self.db.kv_set("sync_meta", "materialized_day", local_day.isoformat())
        self.db.kv_set("sync_meta", "materialized_until", fmt_utc(now + HORIZON_S))

    # -- backfill (§6.4) --------------------------------------------------

    def start(self) -> None:
        """App-start sequence: materialize, then backfill the gap."""
        now = self.clock.now()
        if self.db.kv_get("sync_meta", "last_materialized_at") is None:
            # First run: no past to account for.
            self.db.kv_set("sync_meta", "last_materialized_at", fmt_utc(now))
        self.materialize()
        self.backfill_now()
        self.db.kv_set("sync_meta", "last_tick", str(now))

    def backfill_now(self) -> list[dict]:
        """Classify [last_materialized_at, now) per §6.4; append the results.

        The watermark advances only to ``now - max expiry``: samples whose
        active window is still open are left to the live scheduler (they can
        still fire late) and revisited by the next backfill run.
        """
        now = self.clock.now()
        wm_iso = self.db.kv_get("sync_meta", "last_materialized_at")
        wm = parse_utc(wm_iso) if wm_iso else now
        if wm >= now:
            return []
        resolved = self._resolved_window(wm, now)
        config = self.config_at(now)
        emitted = core_backfill(
            resolved_samples=resolved,
            known_events=self.db.all_sample_events(),
            quiet_history=self.db.events_of_type("quiet_changed"),
            now=now,
            device_id=self.device_id,
            config_v=config["version"] if config else 0,
            expiry_minutes_for={
                s: self.effective_settings(s, now)["expiry_minutes"]
                for s in self._stream_ids()
            },
        )
        for ev in emitted:
            self.append_event(ev)
        expiries = [
            self.effective_settings(s, now)["expiry_minutes"] for s in self._stream_ids()
        ]
        lookback_s = max(expiries, default=DEFAULTS["expiry_minutes"]) * 60
        self.db.kv_set(
            "sync_meta", "last_materialized_at", fmt_utc(max(wm, now - lookback_s))
        )
        return emitted

    # -- tick loop --------------------------------------------------------

    def tick(self) -> int | None:
        """Fire due pings, re-fire snoozes, expire; returns next wake epoch."""
        now = self.clock.now()
        last_tick = self.db.kv_get("sync_meta", "last_tick")
        if last_tick is not None and now - int(last_tick) > CLOCK_JUMP_S:
            self.backfill_now()  # slept/hibernated through the gap
        if self._day_rolled_or_horizon_near(now):
            self.materialize()

        for due in self.db.due_schedule(fmt_utc(now)):
            self._handle_due(due, now)
        self._refire_snoozes(now)
        self._expire_pending(now)

        self.db.kv_set("sync_meta", "last_tick", str(now))
        return self._next_wake(now)

    def _day_rolled_or_horizon_near(self, now: int) -> bool:
        until = self.db.kv_get("sync_meta", "materialized_until")
        if until is None or parse_utc(until) - now < HORIZON_S // 2:
            return True
        config = self.config_at(now)
        if not config:
            return False

        local_day = (
            datetime.fromtimestamp(now, UTC)
            .astimezone(ZoneInfo(config["timezone"]))
            .date()
            .isoformat()
        )
        return self.db.kv_get("sync_meta", "materialized_day") != local_day

    def _handle_due(self, due: dict, now: int) -> None:
        sample_id = due["sample"]
        events = self.db.events_for_sample(sample_id)
        types = {ev["ev"] for _f, _l, ev in events}
        base = self._sample_base(sample_id, now)
        terminal = types & {"answered", "skipped", "expired", "retracted", "suppressed"}
        scheduled = parse_utc(due["scheduled_utc"])
        expiry_s = self.effective_settings(due["stream"], scheduled)["expiry_minutes"] * 60

        if terminal or "fired" in types:
            pass  # already handled here or elsewhere
        elif due["suppressed_reason"]:
            self.append_event({"ev": "suppressed", "reason": due["suppressed_reason"], **base})
        elif self.quiet_active(now):
            self.append_event({"ev": "suppressed", "reason": "quiet_mode", **base})
        elif scheduled + expiry_s <= now:
            # The whole active window passed while nothing was running and no
            # backfill covered it yet; classify rather than fire stale.
            if "unobserved" not in types:
                self.append_event({"ev": "unobserved", "config_v": due["config_v"], **base})
        else:
            self.append_event(
                {
                    "ev": "fired",
                    "config_v": due["config_v"],
                    "scheduled": due["scheduled_utc"],
                    "test": False,
                    **base,
                }
            )
            self._notify_sample(sample_id, scheduled, snoozed_n=0)
        self.db.mark_schedule(sample_id, "done")

    def _notify_sample(self, sample_id: str, scheduled: int, snoozed_n: int) -> None:
        stream_id = sample_id.split("|", 1)[0]
        stream = self.stream_config(stream_id, scheduled)
        name = stream["name"] if stream else stream_id
        config = self.config_at(scheduled)
        tz = ZoneInfo(config["timezone"]) if config else ZoneInfo("UTC")

        local = datetime.fromtimestamp(scheduled, UTC).astimezone(tz)
        when = local.strftime("%H:%M")
        title = name if not snoozed_n else f"{name} (snoozed x{snoozed_n})"
        self.notifier.notify(sample_id, title, f"Ping at {when} - answer now")

    def _refire_snoozes(self, now: int) -> None:
        for row in self.db.sample_rows(statuses=["pending"]):
            events = [ev for _f, _l, ev in self.db.events_for_sample(row["sample"])]
            snoozes = [ev for ev in events if ev["ev"] == "snoozed"]
            if not snoozes:
                continue
            latest = max(snoozes, key=lambda ev: ev["until"])
            until = parse_utc(latest["until"])
            if until > now:
                continue
            refired = any(
                ev["ev"] == "fired"
                and ev["dev"] == self.device_id
                and parse_utc(ev["t"]) >= until
                for ev in events
            )
            if refired:
                continue
            scheduled = parse_utc(row["scheduled_utc"])
            expiry_s = (
                self.effective_settings(row["stream"], scheduled)["expiry_minutes"] * 60
            )
            if scheduled + expiry_s <= now:
                continue  # expiry scan will close it out
            base = self._sample_base(row["sample"], now)
            self.append_event(
                {
                    "ev": "fired",
                    "config_v": self.config_at(scheduled)["version"],
                    "scheduled": row["scheduled_utc"],
                    "test": False,
                    **base,
                }
            )
            self._notify_sample(row["sample"], scheduled, snoozed_n=len(snoozes))

    def _expire_pending(self, now: int) -> None:
        for row in self.db.sample_rows(statuses=["pending"]):
            scheduled = parse_utc(row["scheduled_utc"])
            expiry_s = (
                self.effective_settings(row["stream"], scheduled)["expiry_minutes"] * 60
            )
            if scheduled + expiry_s > now:
                continue
            base = self._sample_base(row["sample"], now)
            config = self.config_at(scheduled)
            self.append_event(
                {"ev": "expired", "config_v": config["version"] if config else 0, **base}
            )
            self.notifier.cancel(row["sample"])

    def _next_wake(self, now: int) -> int | None:
        candidates: list[int] = []
        nxt = self.db.next_planned(fmt_utc(now))
        if nxt:
            candidates.append(parse_utc(nxt))
        for row in self.db.sample_rows(statuses=["pending"]):
            scheduled = parse_utc(row["scheduled_utc"])
            expiry_s = (
                self.effective_settings(row["stream"], scheduled)["expiry_minutes"] * 60
            )
            if scheduled + expiry_s > now:
                candidates.append(scheduled + expiry_s)
            events = [ev for _f, _l, ev in self.db.events_for_sample(row["sample"])]
            for ev in events:
                if ev["ev"] == "snoozed" and parse_utc(ev["until"]) > now:
                    candidates.append(parse_utc(ev["until"]))
        until = self.db.kv_get("sync_meta", "materialized_until")
        if until:
            candidates.append(parse_utc(until) - HORIZON_S // 2)
        return min(candidates) if candidates else None

    # -- user actions (§6.5, §10.3) --------------------------------------

    def answer(
        self,
        sample_id: str,
        answers: dict,
        partial: bool = False,
        supersedes: str | None = None,
        loc: dict | None = None,
    ) -> dict:
        now = self.clock.now()
        stream_id = sample_id.split("|", 1)[0]
        scheduled = parse_utc(sample_id.split("|", 1)[1])
        stream = self.stream_config(stream_id, scheduled) or self.stream_config(
            stream_id, now
        )
        if stream is None:
            raise ValueError(f"unknown stream {stream_id}")
        survey_ref = stream["survey"]
        ev = {
            "ev": "answered",
            "survey": {"id": survey_ref["id"], "version": survey_ref["version"]},
            "answers": answers,
            "loc": loc,
            "supersedes": supersedes,
            "partial": partial,
            **self._sample_base(sample_id, now),
        }
        self.append_event(ev)
        self._bump_vocab(survey_ref, answers, fmt_utc(now))
        self.notifier.cancel(sample_id)
        self.db.mark_schedule(sample_id, "done")
        return self.refold(sample_id)

    def _bump_vocab(self, survey_ref: dict, answers: dict, now_iso: str) -> None:
        survey = self.db.survey(survey_ref["id"], survey_ref["version"])
        if not survey:
            return
        for field in survey["fields"]:
            if field["type"] != "tags":
                continue
            tags = answers.get(field["id"])
            if tags:
                vocab = field.get("vocab") or f"{survey['id']}.{field['id']}"
                self.db.bump_tags(vocab, list(tags), now_iso)

    def skip(self, sample_id: str) -> None:
        now = self.clock.now()
        self.append_event({"ev": "skipped", **self._sample_base(sample_id, now)})
        self.notifier.cancel(sample_id)
        self.db.mark_schedule(sample_id, "done")

    def snooze(self, sample_id: str) -> str | None:
        """Snooze; returns None on success or a refusal reason (§6.5)."""
        now = self.clock.now()
        stream_id, scheduled_iso = sample_id.split("|", 1)
        scheduled = parse_utc(scheduled_iso)
        settings = self.effective_settings(stream_id, scheduled)
        events = [ev for _f, _l, ev in self.db.events_for_sample(sample_id)]
        n = len([ev for ev in events if ev["ev"] == "snoozed"]) + 1
        if n > settings["max_snoozes"]:
            return "max_snoozes"
        until = now + settings["snooze_minutes"] * 60
        if until >= scheduled + settings["expiry_minutes"] * 60:
            return "near_expiry"
        self.append_event(
            {
                "ev": "snoozed",
                "n": n,
                "until": fmt_utc(until),
                **self._sample_base(sample_id, now),
            }
        )
        self.notifier.cancel(sample_id)
        return None

    def retract(self, sample_id: str, note: str | None = None) -> None:
        now = self.clock.now()
        ev = {"ev": "retracted", **self._sample_base(sample_id, now)}
        if note:
            ev["note"] = note
        self.append_event(ev)

    def fire_test_ping(self, stream_id: str) -> str:
        """Real sample at the current second with test=true (§10.2)."""
        now = self.clock.now()
        config = self.config_at(now)
        sample_id = f"{stream_id}|{fmt_utc(now)}"
        self.append_event(
            {
                "ev": "fired",
                "config_v": config["version"] if config else 0,
                "scheduled": fmt_utc(now),
                "test": True,
                **self._sample_base(sample_id, now),
            }
        )
        self._notify_sample(sample_id, now, snoozed_n=0)
        return sample_id

    # -- UI queries -------------------------------------------------------

    def sample_index(self, sample_id: str) -> int | None:
        """0-based index in the local day's resolved list (§7), None for
        ad-hoc samples (test pings)."""
        stream_id, scheduled_iso = sample_id.split("|", 1)
        scheduled = parse_utc(scheduled_iso)
        history = self.db.config_history()
        if not history:
            return None
        for day in self._local_days(scheduled, scheduled):
            for r in resolve_day(history, stream_id, day):
                if r.scheduled_utc == scheduled:
                    return r.index
        return None

    def is_full_survey(self, sample_id: str) -> bool:
        from .core.quick import is_full

        stream_id = sample_id.split("|", 1)[0]
        scheduled = parse_utc(sample_id.split("|", 1)[1])
        stream = self.stream_config(stream_id, scheduled) or {}
        n = stream.get("full_survey_every_n", 1)
        index = self.sample_index(sample_id)
        if index is None:
            return True
        return is_full(index, n)

    def active_samples(self, now: int | None = None) -> list[dict]:
        """Pending, fired here or anywhere, still inside the active window."""
        now = self.clock.now() if now is None else now
        out = []
        for row in self.db.sample_rows(statuses=["pending"]):
            scheduled = parse_utc(row["scheduled_utc"])
            expiry_s = (
                self.effective_settings(row["stream"], scheduled)["expiry_minutes"] * 60
            )
            if row["observed"] and scheduled + expiry_s > now:
                out.append(row)
        return out

    def backlog(self, now: int | None = None) -> list[dict]:
        """Expired/unobserved/stale-pending within the backlog window (§6.5)."""
        now = self.clock.now() if now is None else now
        active = {r["sample"] for r in self.active_samples(now)}
        out = []
        for row in self.db.sample_rows(statuses=["expired", "unobserved", "pending"]):
            if row["sample"] in active:
                continue
            scheduled = parse_utc(row["scheduled_utc"])
            backlog_s = (
                self.effective_settings(row["stream"], scheduled)["backlog_hours"] * 3600
            )
            if now - backlog_s < scheduled <= now:
                out.append(row)
        return out
