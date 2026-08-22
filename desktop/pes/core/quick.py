"""Quick/full survey selection (spec §7).

`index` is the sample's 0-based position in its local day's resolved
candidate list — after the collision rule, before suppression, so suppressed
candidates count. The list is `scheduler.resolve_day`'s output order.
"""

from __future__ import annotations


def is_full(index: int, full_survey_every_n: int) -> bool:
    n = full_survey_every_n
    return n <= 1 or index % n == 0


def presented_fields(survey: dict, full: bool) -> list[dict]:
    """Fields shown for a full or quick presentation. A quick presentation
    shows `quick: true` fields; if none are flagged, the first `tags` field.
    """
    fields = survey["fields"]
    if full:
        return list(fields)
    quick = [f for f in fields if f.get("quick", False)]
    if quick:
        return quick
    return [f for f in fields if f["type"] == "tags"][:1]
