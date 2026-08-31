"""Answer-screen behaviour that only shows up with a real soft keyboard.

Regressions from the Tier 3 C1 charter
(`android/tests/exploratory/findings/C1-answer-flow.md`): the window used to pan
under the status bar when a lower field took focus (F2), Submit disappeared
behind the IME after typing in the last field (F3), and a bounded integer field
opened the alphabetic keyboard (F5). Compose screen tests cannot see any of
this — there is no real IME in `createComposeRule`.
"""
from __future__ import annotations

import re
import time

from conftest import NOW, PING_EPOCH

STATUS_BAR_PX = 100  # emulator status bar is ~88 px tall at this density


def _open_answer(d):
    """Fire the seeded 12:00 ping and land on its Answer screen from Home."""
    d.seed(now=NOW)
    d.settime(PING_EPOCH + 3)
    d.wait_notification("Fixed times")
    d.launch()
    d.tap_text("Answer")
    d.wait_find(text="Submit", scroll=False)


def _ime_shown(d) -> bool:
    return "mInputShown=true" in d.shell("dumpsys input_method | grep mInputShown")


def test_focused_field_does_not_pan_content_under_the_status_bar(dev):
    """C1 F2: with adjustResize the window shrinks; nothing draws at y<status bar."""
    d = dev
    _open_answer(d)
    note = d.wait_find(text="Note")
    d.tap(*note.center)
    d.wait(lambda: _ime_shown(d), 8, "keyboard to open on the Note field")
    tops = [n.bounds[1] for n in d.nodes() if n.text and n.rid != "android:id/statusBarBackground"]
    offenders = [n.text for n in d.nodes() if n.text and 0 < n.bounds[1] < STATUS_BAR_PX]
    assert not offenders, f"app content drawn under the status bar: {offenders}"
    assert min(tops) >= 0
    d.screenshot("ime-note")


def test_submit_stays_reachable_while_the_keyboard_is_up(dev):
    """C1 F3: after typing in the last field Submit must still be on screen."""
    d = dev
    _open_answer(d)
    note = d.wait_find(text="Note")
    d.tap(*note.center)
    d.wait(lambda: _ime_shown(d), 8, "keyboard to open")
    d.type("drafting")
    assert d.ui_has(text="Submit"), "Submit is off-screen behind the IME after typing"


def test_integer_field_opens_a_numeric_keyboard(dev):
    """C1 F5: `number` + `integer: true` must not present QWERTY."""
    d = dev
    _open_answer(d)
    mood = d.wait_find(contains="Mood")
    d.tap(*mood.center)
    d.wait(lambda: _ime_shown(d), 8, "keyboard to open on the Mood field")
    # `dumpsys input_method` prints a history of start-input records; the most
    # recent non-zero one is the field we just focused.
    dump = d.shell("dumpsys input_method | grep -E 'inputType=0x[0-9a-f]+ imeOptions'")
    types = [int(v, 16) for v in re.findall(r"inputType=0x([0-9a-f]+)", dump)]
    nonzero = [t for t in types if t]
    assert nonzero, f"no start-input record in dumpsys input_method: {dump[:200]}"
    klass = nonzero[-1] & 0x0F  # TYPE_MASK_CLASS
    assert klass == 2, f"expected TYPE_CLASS_NUMBER (2), got 0x{nonzero[-1]:x}"


def test_tags_field_is_single_line_and_advances_on_enter(dev):
    """C1 F4: Enter used to insert a newline and reflow the page."""
    d = dev
    _open_answer(d)
    d.wait(lambda: _ime_shown(d), 8, "tags field to take focus")
    d.type("work.writing")
    # Measure the SAME node before and after: the field's content, not its
    # floating label (they have very different heights).
    before = d.wait_find(contains="work.writing", scroll=False).bounds
    d.emu("key", "ENTER")
    time.sleep(0.5)
    after = d.wait_find(contains="work.writing", scroll=False).bounds
    height_before, height_after = before[3] - before[1], after[3] - after[1]
    assert height_after == height_before, (
        f"tags field gained a line on Enter: {height_before} -> {height_after}"
    )
    assert "\n" not in d.find(contains="work.writing").text
    # Focus moved on: the focused node is no longer the tags field.
    focused = re.findall(r'text="([^"]*)"[^>]*focused="true"', d.uixml())
    assert not any("space-separated" in t for t in focused), focused
