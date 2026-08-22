"""Surveys screen and editor (spec §10.2, §7): form-based field editor plus a
raw JSON tab as the emergency backup. Surveys are immutable per version -
saving always creates the next version."""

from __future__ import annotations

import json
import re
import tkinter as tk
from tkinter import messagebox, ttk

from . import theme

SLUG_RE = re.compile(r"^[a-z0-9_]{1,32}$")
FIELD_TYPES = ["text", "number", "tags", "choice"]


def validate_survey(doc: dict) -> list[str]:
    errors = []
    if not SLUG_RE.match(str(doc.get("id", ""))):
        errors.append("bad survey id (slug)")
    if not isinstance(doc.get("version"), int) or doc["version"] < 1:
        errors.append("bad version")
    fields = doc.get("fields")
    if not isinstance(fields, list) or not fields:
        errors.append("no fields")
        return errors
    seen = set()
    for field in fields:
        fid = field.get("id", "")
        if not SLUG_RE.match(str(fid)):
            errors.append(f"bad field id: {fid!r}")
        if fid in seen:
            errors.append(f"duplicate field id: {fid}")
        seen.add(fid)
        if field.get("type") not in FIELD_TYPES:
            errors.append(f"bad type on {fid}: {field.get('type')}")
        if field.get("type") == "choice" and not field.get("options"):
            errors.append(f"choice field {fid} needs options")
    return errors


class SurveysScreen(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app

    def refresh(self) -> None:
        for child in self.winfo_children():
            child.destroy()
        ttk.Label(self, text="Surveys", font=theme.FONT_BIG).pack(anchor="w")
        surveys = self.app.engine.db.all_surveys()
        latest: dict[str, int] = {}
        for sid, version in surveys:
            latest[sid] = max(latest.get(sid, 0), version)
        for sid, version in sorted(latest.items()):
            doc = surveys[(sid, version)]
            row = ttk.Frame(self, style="Card.TFrame", padding=8)
            row.pack(fill="x", pady=3)
            ttk.Label(
                row,
                text=f"{doc.get('title', sid)}  ({sid} v{version}, {len(doc['fields'])} fields)",
                style="Card.TLabel",
            ).pack(side="left")
            ttk.Button(
                row,
                text="Edit (new version)",
                command=lambda d=doc: SurveyEditor(self.app, d),
            ).pack(side="right")
        ttk.Button(
            self, text="New survey...", command=lambda: SurveyEditor(self.app, None)
        ).pack(anchor="w", pady=8)


class SurveyEditor(tk.Toplevel):
    def __init__(self, app, base: dict | None):
        super().__init__(app.root)
        self.app = app
        self.base = base
        self.doc = json.loads(json.dumps(base)) if base else {
            "id": "",
            "version": 0,
            "title": "",
            "fields": [],
        }
        self.title("Survey editor")
        self.geometry("640x560")

        notebook = ttk.Notebook(self)
        notebook.pack(fill="both", expand=True)
        self.form_tab = ttk.Frame(notebook, padding=10)
        self.json_tab = ttk.Frame(notebook, padding=10)
        notebook.add(self.form_tab, text="Form")
        notebook.add(self.json_tab, text="Raw JSON")
        self._build_form()
        self._build_json()

        footer = ttk.Frame(self, padding=8)
        footer.pack(fill="x")
        self.error = ttk.Label(footer, text="", foreground="#c62828")
        self.error.pack(side="left")
        ttk.Button(footer, text="Save as new version", command=self._save).pack(side="right")

    # -- form tab ---------------------------------------------------------

    def _build_form(self) -> None:
        top = ttk.Frame(self.form_tab)
        top.pack(fill="x")
        ttk.Label(top, text="Id:").pack(side="left")
        self.id_var = tk.StringVar(value=self.doc["id"])
        id_entry = ttk.Entry(top, textvariable=self.id_var, width=20)
        id_entry.pack(side="left", padx=(2, 12))
        if self.base:
            id_entry.configure(state="disabled")
        ttk.Label(top, text="Title:").pack(side="left")
        self.title_var = tk.StringVar(value=self.doc.get("title", ""))
        ttk.Entry(top, textvariable=self.title_var, width=30).pack(side="left", padx=2)

        columns = ("id", "type", "label", "required", "quick")
        self.tree = ttk.Treeview(
            self.form_tab, columns=columns, show="headings", height=12
        )
        for col, width in zip(columns, (110, 70, 240, 70, 60)):
            self.tree.heading(col, text=col)
            self.tree.column(col, width=width, anchor="w")
        self.tree.pack(fill="both", expand=True, pady=6)
        self._reload_tree()

        buttons = ttk.Frame(self.form_tab)
        buttons.pack(fill="x")
        for text, command in (
            ("Add", self._add_field),
            ("Edit", self._edit_field),
            ("Remove", self._remove_field),
            ("Up", lambda: self._move(-1)),
            ("Down", lambda: self._move(1)),
        ):
            ttk.Button(buttons, text=text, command=command).pack(side="left", padx=2)

    def _reload_tree(self) -> None:
        for item in self.tree.get_children():
            self.tree.delete(item)
        for i, field in enumerate(self.doc["fields"]):
            self.tree.insert(
                "",
                "end",
                iid=str(i),
                values=(
                    field.get("id", ""),
                    field.get("type", ""),
                    field.get("label", ""),
                    "yes" if field.get("required") else "",
                    "yes" if field.get("quick") else "",
                ),
            )

    def _selected_index(self) -> int | None:
        selection = self.tree.selection()
        return int(selection[0]) if selection else None

    def _add_field(self) -> None:
        FieldDialog(self, None)

    def _edit_field(self) -> None:
        index = self._selected_index()
        if index is not None:
            FieldDialog(self, index)

    def _remove_field(self) -> None:
        index = self._selected_index()
        if index is not None:
            del self.doc["fields"][index]
            self._reload_tree()

    def _move(self, delta: int) -> None:
        index = self._selected_index()
        if index is None:
            return
        other = index + delta
        fields = self.doc["fields"]
        if 0 <= other < len(fields):
            fields[index], fields[other] = fields[other], fields[index]
            self._reload_tree()
            self.tree.selection_set(str(other))

    # -- raw JSON tab -----------------------------------------------------

    def _build_json(self) -> None:
        self.json_text = tk.Text(self.json_tab, wrap="none")
        self.json_text.pack(fill="both", expand=True)
        self._refresh_json()
        row = ttk.Frame(self.json_tab)
        row.pack(fill="x", pady=4)
        ttk.Button(row, text="Apply JSON to form", command=self._apply_json).pack(side="left")
        ttk.Button(row, text="Reload from form", command=self._refresh_json).pack(
            side="left", padx=6
        )

    def _refresh_json(self) -> None:
        self._collect_form()
        self.json_text.delete("1.0", "end")
        self.json_text.insert("1.0", json.dumps(self.doc, indent=2))

    def _apply_json(self) -> None:
        try:
            doc = json.loads(self.json_text.get("1.0", "end"))
        except json.JSONDecodeError as exc:
            self.error.configure(text=f"JSON error: {exc}")
            return
        self.doc = doc
        self.id_var.set(doc.get("id", ""))
        self.title_var.set(doc.get("title", ""))
        self._reload_tree()
        self.error.configure(text="")

    # -- save -------------------------------------------------------------

    def _collect_form(self) -> None:
        self.doc["id"] = self.id_var.get().strip()
        self.doc["title"] = self.title_var.get().strip()

    def _save(self) -> None:
        self._collect_form()
        db = self.app.engine.db
        versions = [v for sid, v in db.all_surveys() if sid == self.doc["id"]]
        self.doc["version"] = max(versions, default=0) + 1
        errors = validate_survey(self.doc)
        if errors:
            self.error.configure(text="; ".join(errors))
            return
        if self.base and versions:
            # Field ids must stay stable across versions (§7): warn if any
            # previously-present id vanished.
            prev = db.survey(self.doc["id"], max(versions))
            gone = {f["id"] for f in prev["fields"]} - {
                f["id"] for f in self.doc["fields"]
            }
            if gone and not messagebox.askyesno(
                "Removed fields",
                f"Fields removed: {', '.join(sorted(gone))}. Analysis will treat"
                " them as missing from this version on. Save anyway?",
                parent=self,
            ):
                return
        db.upsert_survey(self.doc)
        self.app.set_status(f"Saved survey {self.doc['id']} v{self.doc['version']}")
        self.app.sync_async()  # uploaded via put_if_absent (immutable)
        self.destroy()
        self.app.refresh()


class FieldDialog(tk.Toplevel):
    def __init__(self, editor: SurveyEditor, index: int | None):
        super().__init__(editor)
        self.editor = editor
        self.index = index
        field = dict(editor.doc["fields"][index]) if index is not None else {}
        self.title("Field")
        form = ttk.Frame(self, padding=10)
        form.pack(fill="both", expand=True)

        self.vars: dict[str, tk.Variable] = {}

        def entry(label: str, key: str, value) -> None:
            row = ttk.Frame(form)
            row.pack(fill="x", pady=2)
            ttk.Label(row, text=label, width=16, anchor="w").pack(side="left")
            var = tk.StringVar(value="" if value is None else str(value))
            self.vars[key] = var
            ttk.Entry(row, textvariable=var, width=32).pack(side="left")

        entry("Id (slug)", "id", field.get("id", ""))
        entry("Label", "label", field.get("label", ""))
        row = ttk.Frame(form)
        row.pack(fill="x", pady=2)
        ttk.Label(row, text="Type", width=16, anchor="w").pack(side="left")
        self.type_var = tk.StringVar(value=field.get("type", "tags"))
        ttk.Combobox(
            row, textvariable=self.type_var, state="readonly", values=FIELD_TYPES, width=10
        ).pack(side="left")
        self.required_var = tk.BooleanVar(value=field.get("required", False))
        self.quick_var = tk.BooleanVar(value=field.get("quick", False))
        ttk.Checkbutton(form, text="Required", variable=self.required_var).pack(anchor="w")
        ttk.Checkbutton(form, text="Quick (shown on quick pings)", variable=self.quick_var).pack(
            anchor="w"
        )

        extras = {
            k: v
            for k, v in field.items()
            if k not in ("id", "label", "type", "required", "quick")
        }
        ttk.Label(
            form,
            text='Type params, JSON (e.g. {"multiline": true} or\n'
            '{"min":1,"max":7,"integer":true,"display":"slider","end_labels":["low","high"]})',
        ).pack(anchor="w", pady=(6, 2))
        self.params_text = tk.Text(form, height=4, width=48)
        self.params_text.pack(fill="x")
        if extras:
            self.params_text.insert("1.0", json.dumps(extras, indent=2))

        self.error = ttk.Label(form, text="", foreground="#c62828")
        self.error.pack(anchor="w")
        buttons = ttk.Frame(form)
        buttons.pack(pady=6)
        ttk.Button(buttons, text="OK", command=self._confirm).pack(side="left", padx=4)
        ttk.Button(buttons, text="Cancel", command=self.destroy).pack(side="left")

    def _confirm(self) -> None:
        raw = self.params_text.get("1.0", "end").strip()
        try:
            extras = json.loads(raw) if raw else {}
        except json.JSONDecodeError as exc:
            self.error.configure(text=str(exc))
            return
        if not isinstance(extras, dict):
            self.error.configure(text="params must be a JSON object")
            return
        field = {
            "id": self.vars["id"].get().strip(),
            "type": self.type_var.get(),
            "label": self.vars["label"].get().strip(),
            **extras,
        }
        if self.required_var.get():
            field["required"] = True
        if self.quick_var.get():
            field["quick"] = True
        if self.index is None:
            self.editor.doc["fields"].append(field)
        else:
            self.editor.doc["fields"][self.index] = field
        self.editor._reload_tree()
        self.destroy()
