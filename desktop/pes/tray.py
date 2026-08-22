"""Tray icon (spec §12): tray-resident process; closing the window hides it.

``pystray`` (and Pillow, for the icon bitmap) are optional — without them the
app simply stays as a normal window.
"""

from __future__ import annotations


def attach_tray(app) -> bool:
    """Attach a tray icon to the App; returns False if unsupported."""
    try:
        import pystray
        from PIL import Image, ImageDraw
    except ImportError:
        return False

    image = Image.new("RGB", (64, 64), "#1565c0")
    draw = ImageDraw.Draw(image)
    draw.ellipse((16, 16, 48, 48), fill="#ffffff")

    def show(_icon=None, _item=None):
        app.root.after(0, app.root.deiconify)

    def sync(_icon=None, _item=None):
        app.root.after(0, app.sync_async)

    def quiet_off(_icon=None, _item=None):
        app.root.after(0, lambda: (app.engine.set_quiet(None), app.refresh()))

    def quit_app(icon, _item=None):
        icon.stop()
        app.root.after(0, app.root.destroy)

    icon = pystray.Icon(
        "pes",
        image,
        "Personal Experience Sampler",
        menu=pystray.Menu(
            pystray.MenuItem("Open", show, default=True),
            pystray.MenuItem("Sync now", sync),
            pystray.MenuItem("Quiet off", quiet_off),
            pystray.MenuItem("Quit", quit_app),
        ),
    )
    icon.run_detached()
    # Closing the window hides to the tray instead of quitting.
    app.root.protocol("WM_DELETE_WINDOW", app.root.withdraw)
    return True
