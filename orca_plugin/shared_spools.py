# /// script
# requires-python = ">=3.12"
# dependencies = []
# [tool.orcaslicer.plugin]
# name = "Shared Spools"
# description = "Automatic loaded-spool profiles, owners and Paxx12 tag-link repair."
# author = "Kaskobiii"
# version = "0.2.0"
# ///
"""Standalone Orca plugin; networking and mapping logic can also be unit tested."""
import html
import json
import re
from queue import Empty, Full, Queue
from threading import Event
from time import monotonic
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


def endpoint(value):
    value = value.strip().rstrip("/")
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("Enter an http:// or https:// server URL without credentials or a query.")
    return value


def extra_text(record, key):
    value = (record.get("extra") or {}).get(key, "")
    try:
        decoded = json.loads(value)
        return decoded if isinstance(decoded, str) else ""
    except (TypeError, ValueError):
        return value if isinstance(value, str) else ""


def uid_hex(value):
    value = re.sub(r"[\s:\-]", "", str(value)).upper()
    if not re.fullmatch(r"(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})", value):
        raise ValueError("Card UID must contain 4, 7 or 10 hex bytes, as shown by the printer.")
    if set(value) == {"0"}:
        raise ValueError("An all-zero UID is not a scanned tag.")
    return value


def card_uids(spool):
    values = [t.get("uid", "") for t in spool.get("tags", [])]
    for key in ("card_uids", "card_uid"):
        values += re.split(r"[,;\s]+", extra_text(spool, key))
    result = []
    for value in values:
        if not value:
            continue
        try:
            normalized = uid_hex(value)
        except ValueError:
            continue
        if normalized not in result:
            result.append(normalized)
    return result


class JsonServer:
    def __init__(self, url):
        self.url = endpoint(url)

    def call(self, method, path, body=None):
        payload = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(self.url + path, data=payload, method=method,
                          headers={"Accept": "application/json", "Content-Type": "application/json"})
        with urlopen(request, timeout=8) as response:
            raw = response.read(8_000_001)
        if len(raw) > 8_000_000:
            raise ValueError("Server response is too large.")
        return json.loads(raw) if raw.strip() else None


class Spoolman:
    def __init__(self, server):
        self.server = server

    def get(self, spool_id):
        spool = self.server.call("GET", f"/api/v1/spool/{int(spool_id)}")
        if spool.get("id") != int(spool_id):
            raise ValueError("Spoolman returned a different spool; nothing was changed.")
        return spool

    def all_spools(self):
        result = []
        for offset in range(0, 100_000, 250):
            batch = self.server.call("GET", "/api/v1/spool?" + urlencode(
                {"limit": 250, "offset": offset, "allow_archived": "true"}))
            if not isinstance(batch, list):
                raise ValueError("Unexpected spool list response.")
            result.extend(batch)
            if len(batch) < 250:
                return result
        raise ValueError("Spool list exceeded the paging limit.")

    def ensure_text_field(self, key, name):
        fields = self.server.call("GET", "/api/v1/field/spool")
        existing = next((f for f in fields if f["key"] == key), None)
        if existing:
            if existing["field_type"].lower() != "text":
                raise ValueError(f"The {name} custom field exists with a different type.")
            return
        self.server.call("POST", f"/api/v1/field/spool/{key}",
                         {"name": name, "field_type": "text", "order": 10,
                          "default_value": json.dumps("")})

    def save_owner(self, spool_id, owner):
        owner = owner.strip()
        if len(owner) > 80:
            raise ValueError("Owner names may contain at most 80 characters.")
        self.ensure_text_field("owner", "Owner")
        spool = self.get(spool_id)
        extra = dict(spool.get("extra") or {})
        extra["owner"] = json.dumps(owner)
        self.server.call("PATCH", f"/api/v1/spool/{int(spool_id)}", {"extra": extra})
        if extra_text(self.get(spool_id), "owner") != owner:
            raise ValueError("Owner change was not confirmed by Spoolman. Reload and retry.")

    def native_tags_supported(self):
        try:
            info = self.server.call("GET", "/api/v1/info")
        except HTTPError as error:
            if error.code == 404:
                return False
            raise
        match = re.match(r"^v?(\d+)\.(\d+)\.", info.get("version", ""))
        if not match:
            raise ValueError("Cannot identify the Spoolman version; repair was not started.")
        return tuple(map(int, match.groups())) >= (0, 27)

    def repair_link(self, spool_id, raw_uid):
        spool_id, uid = int(spool_id), uid_hex(raw_uid)
        target = self.get(spool_id)
        for spool in self.all_spools():
            if spool["id"] != spool_id and uid in card_uids(spool):
                raise ValueError(f"This UID already belongs to spool #{spool['id']}. "
                                 "Review that link in Spool Studio before moving it.")
        native = self.native_tags_supported()
        if native and not any(uid_hex(t["uid"]) == uid for t in target.get("tags", [])):
            # The native endpoint also rejects tags already attached to a filament product.
            self.server.call("POST", f"/api/v1/spool/{spool_id}/tag", {"uid": uid})
        self.ensure_text_field("card_uids", "Card UIDs")
        current = self.get(spool_id)
        extra = dict(current.get("extra") or {})
        uids = card_uids(current)
        if uid not in uids:
            uids.append(uid)
        extra["card_uids"] = json.dumps(",".join(uids))
        self.server.call("PATCH", f"/api/v1/spool/{spool_id}", {"extra": extra})
        verified = self.get(spool_id)
        legacy = re.split(r"[,;\s]+", extra_text(verified, "card_uids"))
        if uid not in legacy:
            raise ValueError("Repair is incomplete: the Paxx12 Card UIDs mirror is missing. Reload and retry.")
        if native and not any(uid_hex(t["uid"]) == uid for t in verified.get("tags", [])):
            raise ValueError("Repair is incomplete: the native tag link is missing. Reload and retry.")


def printer_channels(server):
    response = server.call("GET", "/printer/objects/query?print_task_config=filament_spool_id&filament_detect")
    status = response["result"]["status"]
    ids = status.get("print_task_config", {}).get("filament_spool_id")
    if not isinstance(ids, list) or len(ids) != 4:
        raise ValueError("Paxx12 did not report four SpoolLink spool assignments.")
    info = status.get("filament_detect", {}).get("info", [])
    result = []
    for index, spool_id in enumerate(ids):
        if isinstance(spool_id, bool) or not isinstance(spool_id, (int, type(None))) or (spool_id or 0) < 0:
            raise ValueError("Printer reported an invalid spool ID.")
        card = info[index].get("CARD_UID", []) if index < len(info) and isinstance(info[index], dict) else []
        uid = ""
        if isinstance(card, list) and card and all(type(b) is int and 0 <= b <= 255 for b in card) and any(card):
            uid = uid_hex("".join(f"{b:02X}" for b in card))
        result.append({"extruder": index + 1, "spool_id": spool_id or 0, "uid": uid})
    return result


def mapping_key(printer, filament_id):
    return json.dumps([printer, int(filament_id)], separators=(",", ":"))


COLOR_WORDS = set("white black red yellow blue green orange purple grey gray brown transparent clear natural silver gold pink cyan magenta ivory beige violet turquoise navy".split())


def product_words(value):
    value = value.lower().replace("+", " plus ")
    value = re.sub(r"\bpla\s*plus\b", "pla plus", value)
    return set(re.findall(r"[a-z0-9]+", value)) - {"basic"}


def automatic_profile(filament, profiles):
    """Only select one exact product family; never guess from vendor or order."""
    vendor = product_words((filament.get("vendor") or {}).get("name") or "")
    material = product_words(filament.get("material") or "")
    if not vendor or not material:
        return "", "Vendor and material are required for automatic matching."
    name_words = product_words(filament.get("name") or "")
    variant = product_words(extra_text(filament, "variant")) | product_words(extra_text(filament, "material_modifier"))
    target = vendor | material | variant | (name_words - COLOR_WORDS)
    colors = name_words & COLOR_WORDS
    if "gray" in colors:
        colors = colors - {"gray"} | {"grey"}
    matches = []
    for profile in profiles:
        words = product_words(profile["name"].split("@", 1)[0])
        profile_colors = words & COLOR_WORDS
        if "gray" in profile_colors:
            profile_colors = profile_colors - {"gray"} | {"grey"}
        if words - COLOR_WORDS != target:
            continue
        # A black-specific profile is not a fallback for a white spool.
        if profile_colors and profile_colors != colors:
            continue
        matches.append(((bool(profile_colors), bool(profile.get("is_user"))), profile["name"]))
    if not matches:
        return "", "No exact product profile found. Choose and remember one once."
    best = max(rank for rank, _ in matches)
    names = sorted({name for rank, name in matches if rank == best})
    if len(names) != 1:
        return "", "Several exact profiles match. Choose and remember one once."
    return names[0], "Automatic product match"


def resolved_profiles(spools, profiles, mappings, printer):
    available = {p["name"] for p in profiles}
    choices, reasons = {}, {}
    for spool in spools:
        filament = spool["filament"]
        key = mapping_key(printer, filament["id"])
        if key in mappings:
            name = mappings[key]
            choices[key] = name if name in available else ""
            reasons[key] = "Saved choice" if name in available else "Saved profile is unavailable for this printer."
        else:
            choices[key], reasons[key] = automatic_profile(filament, profiles)
    return choices, reasons


def sync_plan(channels, spools, mappings, printer, slots, available, slot_count):
    if len(slots) != 4 or sorted(slots) != [1, 2, 3, 4]:
        raise ValueError("Assign each extruder to a different Orca filament slot (1–4).")
    if slot_count < 4:
        raise ValueError("Add four filament slots in Prepare before syncing.")
    by_id = {s["id"]: s for s in spools}
    plan = []
    for channel in channels:
        if not channel["spool_id"]:
            continue
        spool = by_id.get(channel["spool_id"])
        if not spool:
            raise ValueError(f"Loaded spool #{channel['spool_id']} was not found in Spoolman.")
        if channel.get("uid") and channel["uid"] not in card_uids(spool):
            raise ValueError(f"Extruder {channel['extruder']}'s Card UID is not linked to spool #{spool['id']}. Repair the link and rescan before syncing.")
        name = mappings.get(mapping_key(printer, spool["filament"]["id"]), "")
        if not name or name not in available:
            raise ValueError(f"Choose an available exact profile for spool #{spool['id']} before syncing.")
        plan.append({**channel, "slot": slots[channel["extruder"] - 1], "profile": name})
    if not plan:
        raise ValueError("The printer has no linked Spoolman spools to sync.")
    return plan


def apply_plan(editor, plan, read_names=None):
    done = []
    for row in plan:
        try:
            # An empty transaction selects the named profile; no preset settings are changed.
            editor.begin_update(row["slot"], row["profile"]).commit()
            if read_names is not None:
                names = read_names()
                if len(names) < row["slot"] or names[row["slot"] - 1] != row["profile"]:
                    raise RuntimeError("Orca did not retain the requested profile; refresh before retrying.")
        except Exception as error:
            completed = ", ".join(f"slot {r['slot']}: {r['profile']}" for r in done) or "none"
            raise RuntimeError(f"Sync stopped. Applied: {completed}. {error}") from error
        done.append(row)
    return done


def escaped(value):
    return html.escape(str(value), quote=True)


def show_plugin_dialog(ui, *, html, title, width, height):
    """Return one form action from either supported Orca host UI interface.

    create_window returns immediately and delivers callbacks on the UI thread.
    Only the Script invocation worker waits here; the Orca UI stays responsive.
    """
    legacy_dialog = getattr(ui, "show_dialog", None)
    if callable(legacy_dialog):
        return legacy_dialog(html=html, title=title, width=width, height=height)
    create_window = getattr(ui, "create_window", None)
    if not callable(create_window) or not hasattr(ui, "WINDOW_MODAL"):
        raise RuntimeError("This Orca build does not expose a supported plugin window API.")

    replies = Queue(maxsize=1)

    def finish(data=None):
        try:
            replies.put_nowait(data)
        except Full:
            pass  # Submission wins over a subsequent close notification.

    window = create_window(html=html, title=title, width=width, height=height,
                           style=ui.WINDOW_MODAL, on_submit=finish, on_close=finish)
    try:
        while True:
            try:
                return replies.get(timeout=0.2)
            except Empty:
                if not window.is_open():
                    return None  # Host teardown can close without firing on_close.
    finally:
        window.close()


def profile_options(profiles, selected):
    options = '<option value="">Choose exact profile…</option>'
    for name in profiles:
        checked = " selected" if name == selected else ""
        options += f'<option value="{escaped(name)}"{checked}>{escaped(name)}</option>'
    return options


def render(config, spools, channels, profiles, printer, message="", choices=None, reasons=None):
    loaded = {c["spool_id"] for c in channels if c["spool_id"]}
    rows = []
    for spool in spools:
        if spool.get("archived") and spool["id"] not in loaded:
            continue
        filament = spool["filament"]
        product = " ".join(filter(None, [(filament.get("vendor") or {}).get("name"),
                                        filament.get("material"), extra_text(filament, "material_modifier"),
                                        extra_text(filament, "variant"), filament.get("name")]))
        key = mapping_key(printer, filament["id"])
        rows.append(f'''<tr data-owner="{escaped(extra_text(spool, 'owner').lower())}">
<td>#{spool['id']}{' · loaded' if spool['id'] in loaded else ''}<br>{escaped(product)}<small>{escaped(', '.join(card_uids(spool)))}</small></td>
<td><input id="o{spool['id']}" aria-label="Owner for spool {spool['id']}" maxlength="80" value="{escaped(extra_text(spool, 'owner'))}">
<button onclick="send('owner',{{id:{spool['id']},owner:document.getElementById('o{spool['id']}').value}})">Save owner</button></td>
<td>{escaped(round(spool.get('remaining_weight') or 0, 1))} g</td>
<td><select id="p{spool['id']}" aria-label="Profile for spool {spool['id']}">{profile_options(profiles, (choices or config.get('mappings', {})).get(key))}</select>
<small>{escaped((reasons or {}).get(key, ''))}</small>
<button onclick="send('mapping',{{filament:{filament['id']},profile:document.getElementById('p{spool['id']}').value}})">Remember profile</button></td></tr>''')
    channel_rows = "".join(f"<li>Extruder {c['extruder']}: spool #{c['spool_id'] or '—'} · UID {escaped(c['uid'] or 'not reported')}</li>" for c in channels)
    slot_inputs = " ".join(f'<label>E{i+1} → slot <input id="slot{i+1}" class="slot" type="number" min="1" max="4" value="{s}" aria-label="Slot for extruder {i+1}"></label>' for i, s in enumerate(config.get("slots", [1, 2, 3, 4])))
    spool_options = "".join(f'<option value="{s["id"]}">Spool #{s["id"]} · {escaped(extra_text(s, "owner") or "Unassigned")}</option>' for s in spools)
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>
body{{font-family:system-ui;margin:24px}}input,select,button{{padding:8px;margin:4px;max-width:350px}}button{{cursor:pointer}}table{{width:100%;border-collapse:collapse}}td,th{{text-align:left;border-bottom:1px solid var(--orca-border,#777);padding:10px}}small{{display:block;color:var(--orca-muted,#888);overflow-wrap:anywhere}}.slot{{width:45px}}.notice{{padding:12px;border:1px solid var(--orca-border,#777)}}details{{margin-top:22px}}.servers{{display:flex;gap:12px;flex-wrap:wrap}}label{{display:inline-block}}</style></head><body>
<main id="app"><h2>Shared Spools</h2><p id="status" class="notice" role="status">{escaped(message or 'Enter your connections to load spools. Saved connections load automatically on reopening.')}</p>
<form onsubmit="return false"><div class="servers"><label>Spoolman URL<br><input id="spoolman" aria-label="Spoolman URL" value="{escaped(config.get('spoolman',''))}" placeholder="http://spoolman:8000"></label>
<label>Moonraker URL<br><input id="moonraker" aria-label="Moonraker URL" value="{escaped(config.get('moonraker',''))}" placeholder="http://printer:7125"></label></div>
<p>{slot_inputs}</p><button onclick="send('connect')">Save connections / refresh</button>
<button onclick="send('sync')">Sync shared spools</button><button onclick="orca.close()">Close</button>
<p><label><input id="autoSync" type="checkbox" {'checked' if config.get('auto_sync', True) else ''} onchange="send('settings')"> Automatically follow loaded spools while this window is open</label></p>
<p class="notice">Use this window for automatic matching. Orca’s normal Sync button still uses Orca’s own matching and can select Galaxy PLA. Keep this window open for automatic updates; pause automatic sync to choose profiles manually.</p>
<p>Printer profile: <strong>{escaped(printer)}</strong>. Slot numbers above refer to the filament list in Prepare.</p><ul>{channel_rows}</ul>
<label>Filter by owner <input id="ownerFilter" aria-label="Filter by owner" placeholder="All owners" oninput="filterOwners(this.value)"></label>
<table><thead><tr><th>Spool / product / tags</th><th>Owner</th><th>Remaining</th><th>Exact Orca profile</th></tr></thead><tbody>{''.join(rows)}</tbody></table>
<details><summary>Repair a tag link</summary><p>Choose the physical spool and copy the Card UID shown by your printer. Other tags and remaining weight are retained. A UID linked to another spool is refused.</p>
<select id="repairSpool" aria-label="Spool to repair">{spool_options}</select><input id="repairUid" aria-label="Card UID to repair" placeholder="04:AA:BB:CC:DD:EE:11">
<button onclick="send('repair',{{id:Number(document.getElementById('repairSpool').value),uid:document.getElementById('repairUid').value}})">Repair this link</button></details>
<p><small>Remember profile overrides automatic matching for this filament product and printer preset. Rescan a repaired tag to refresh the printer.</small></p></form></main>
<script>
let working=false;
function send(action,extra={{}}){{if(working)return;working=true;setBusy(true);orca.postMessage({{action,spoolman:document.getElementById('spoolman').value,moonraker:document.getElementById('moonraker').value,auto_sync:document.getElementById('autoSync').checked,slots:Array.from(document.querySelectorAll('.slot'),x=>Number(x.value)),...extra}})}}
function setBusy(value){{for(const button of document.querySelectorAll('button'))button.disabled=value;if(value)document.getElementById('status').textContent='Working… If Orca asks for network access, allow your Spoolman and Moonraker addresses.';}}
function filterOwners(value){{for(const row of document.querySelectorAll('tr[data-owner]'))row.hidden=!row.dataset.owner.includes(value.trim().toLowerCase())}}
orca.onMessage(function(data){{if(!data)return;if(data.busy){{setBusy(true);return;}}if(!data.html)return;
 const scroll=window.scrollY,focused=document.activeElement,focusId=focused&&focused.id,filter=document.getElementById('ownerFilter').value,saved={{}},expanded=Array.from(document.querySelectorAll('details'),x=>x.open);
 if(data.poll)for(const el of document.querySelectorAll('input[id],select[id]')){{const dirty=el.tagName==='SELECT'?el.value!==(Array.from(el.options).find(x=>x.defaultSelected)||el.options[0])?.value:el.type==='checkbox'?el.checked!==el.defaultChecked:el.value!==el.defaultValue;if(dirty)saved[el.id]={{value:el.value,checked:el.checked}};}}
 document.getElementById('app').outerHTML=data.html;
 for(const [id,value] of Object.entries(saved)){{const el=document.getElementById(id);if(el){{el.value=value.value;if(el.type==='checkbox')el.checked=value.checked;}}}}
 document.getElementById('ownerFilter').value=filter;filterOwners(filter);working=false;
 Array.from(document.querySelectorAll('details')).forEach((el,i)=>el.open=expanded[i]||false);
 const focus=focusId&&document.getElementById(focusId);if(focus)focus.focus();window.scrollTo(0,scroll);
}});
orca.postMessage({{action:'ready'}});
</script></body></html>'''


def page_body(page):
    return page[page.index('<main id="app">'):page.index('</main>') + len('</main>')]


class SharedSpoolsSession:
    """One script worker owns network requests, configuration and preset changes."""
    def __init__(self, config, save, bundle, editor, server_factory=JsonServer, stopped=None):
        self.config = {"spoolman": "", "moonraker": "", "slots": [1, 2, 3, 4],
                       "mappings": {}, "auto_sync": True, **config}
        self.save, self.bundle, self.editor = save, bundle, editor
        self.server_factory = server_factory
        self.stopped = stopped or Event()
        self.spools, self.channels, self.profiles = [], [], []
        self.printer, self.message = "", "Enter your Spoolman and Moonraker URLs."
        self.choices, self.reasons = {}, {}
        self.selection_error = ""
        self.refresh_presets()

    def refresh_presets(self):
        bundle = self.bundle()
        self.printer = bundle.current_printer_preset().name
        self.profiles = []
        for index in range(bundle.filaments.size()):
            preset = bundle.filaments.preset(index)
            if preset.is_visible and preset.is_compatible:
                user = getattr(preset, "is_user", None)
                self.profiles.append({"name": preset.name,
                                      "is_user": bool(user()) if callable(user) else False})
        self.choices, self.reasons = resolved_profiles(self.spools, self.profiles,
                                                      self.config["mappings"], self.printer)

    def persist(self):
        if not self.save(json.dumps(self.config)):
            raise ValueError("Settings were not saved. Keep this window open and retry Save connections.")

    def connections(self):
        return (Spoolman(self.server_factory(self.config["spoolman"])),
                self.server_factory(self.config["moonraker"]))

    def refresh(self, *, auto=True):
        errors = []
        if self.config["spoolman"]:
            try:
                self.spools = Spoolman(self.server_factory(self.config["spoolman"])).all_spools()
            except Exception as error:
                errors.append("Spoolman connection failed: " + str(error))
        if self.config["moonraker"]:
            try:
                self.channels = printer_channels(self.server_factory(self.config["moonraker"]))
            except Exception as error:
                # Retain the spool list, but never act on stale printer assignments.
                self.channels = []
                errors.append("Printer connection failed: " + str(error))
        self.refresh_presets()
        if errors:
            self.message = " ".join(errors) + " Saved addresses are retained; allow network access and retry Refresh."
            return
        if not self.config["spoolman"] or not self.config["moonraker"]:
            self.message = "Enter both server URLs, then Save connections."
            return
        self.message = f"Connected. {len(self.spools)} spools loaded."
        if auto and self.config["auto_sync"]:
            try:
                self.sync()
            except Exception as error:
                self.message += " Automatic sync paused: " + str(error)

    def sync(self):
        if self.stopped.is_set():
            return
        if self.selection_error:
            raise ValueError("Selection is paused after a failed or cancelled change. Press Refresh to retry. " + self.selection_error)
        if not any(c["spool_id"] for c in self.channels):
            self.message += " No linked spools are currently loaded at the printer."
            return
        if self.editor is None or not hasattr(self.editor, "begin_update"):
            raise ValueError("This Orca build lacks the filament selection API.")
        bundle = self.bundle()
        if bundle.current_printer_preset().name != self.printer:
            raise ValueError("Printer preset changed; refresh before syncing.")
        names = list(bundle.current_filament_preset_names())
        plan = sync_plan(self.channels, self.spools, self.choices, self.printer,
                         self.config["slots"], [p["name"] for p in self.profiles], len(names))
        changes = [row for row in plan if names[row["slot"] - 1] != row["profile"]]
        if changes:
            _, moonraker = self.connections()
            if self.channels != printer_channels(moonraker):
                raise ValueError("Printer spool assignments changed; refresh before syncing.")
            if self.stopped.is_set():
                return

            def read_names():
                current = self.bundle()
                if self.stopped.is_set() or current.current_printer_preset().name != self.printer:
                    raise ValueError("Sync stopped because the window closed or printer preset changed.")
                return list(current.current_filament_preset_names())

            try:
                apply_plan(self.editor, changes, read_names)
            except Exception as error:
                # Do not reopen Orca's unsaved-edit prompt every five seconds after Cancel.
                self.selection_error = str(error)
                raise
        self.message = "Loaded profiles verified: " + "; ".join(
            f"E{r['extruder']} → slot {r['slot']}: {r['profile']}" for r in plan)

    def handle(self, data):
        action = data.get("action")
        if action in ("ready", "refresh"):
            self.refresh()
            return
        try:
            self.selection_error = ""
            urls = {k: endpoint(data[k]) for k in ("spoolman", "moonraker")}
            if action != "connect" and any(urls[k] != self.config[k] for k in urls):
                raise ValueError("Save connections / refresh before using changed addresses.")
            if action == "connect":
                slots = data["slots"]
                if len(slots) != 4 or any(type(s) is not int for s in slots) or sorted(slots) != [1, 2, 3, 4]:
                    raise ValueError("Assign each extruder to a different filament slot (1–4).")
                if urls["spoolman"] != self.config["spoolman"]:
                    self.config["mappings"] = {}
                    self.spools = []
                if urls["moonraker"] != self.config["moonraker"]:
                    self.channels = []
                self.config.update(urls)
                self.config["slots"] = slots
            self.config["auto_sync"] = bool(data.get("auto_sync", self.config["auto_sync"]))
            # Save addresses before networking: a denied first-run request must not erase them.
            self.persist()
            if action == "owner":
                spoolman, _ = self.connections()
                spoolman.save_owner(data["id"], data["owner"])
            elif action == "repair":
                spoolman, _ = self.connections()
                spoolman.repair_link(data["id"], data["uid"])
            elif action == "mapping":
                if data["profile"] not in {p["name"] for p in self.profiles}:
                    raise ValueError("Choose an available exact profile.")
                self.config["mappings"][mapping_key(self.printer, data["filament"])] = data["profile"]
                self.persist()
            self.refresh(auto=action != "sync")
            if action == "sync":
                # A failed refresh must never use the retained spool cache for selection.
                if "connection failed:" not in self.message:
                    self.sync()
            elif action == "repair":
                self.message = "Tag link verified. Rescan the tag at the printer. " + self.message
            elif action == "owner":
                self.message = "Owner saved and verified. " + self.message
        except Exception as error:
            self.message = str(error) + " Refresh before retrying a failed remote edit; it may have partly completed."

    def page(self):
        return render(self.config, self.spools, self.channels,
                      sorted(p["name"] for p in self.profiles), self.printer,
                      self.message, self.choices, self.reasons)


def run_session(ui, session, stopped, *, poll_seconds=5):
    """Keep one webview alive. Callbacks enqueue work; the Script worker does it."""
    events = Queue(maxsize=32)

    def receive(data):
        if not isinstance(data, dict):
            return
        try:
            events.put_nowait(data)
        except Full:
            pass

    def close():
        stopped.set()

    window = ui.create_window(html=session.page(), title="Shared Spools", width=1180, height=780,
                              style=ui.WINDOW_MODELESS, on_message=receive, on_close=close)
    ready, next_poll = False, monotonic() + poll_seconds
    try:
        while not stopped.is_set():
            try:
                data = events.get(timeout=0.2)
            except Empty:
                if not window.is_open():
                    break
                if not ready or monotonic() < next_poll:
                    continue
                data = {"action": "refresh"}
            if data.get("action") == "ready":
                ready = True
            if not ready:
                continue
            poll = data.get("action") == "refresh"
            if not poll:
                window.post({"busy": True})
            try:
                session.handle(data)
            except Exception as error:
                session.message = "Refresh failed: " + str(error) + " Retry Save connections / refresh."
            if stopped.is_set():
                break
            window.post({"html": page_body(session.page()), "poll": poll})
            next_poll = monotonic() + poll_seconds
    finally:
        stopped.set()
        window.close()


try:
    import orca
except ModuleNotFoundError:
    orca = None  # Allows independent tests without embedding OrcaSlicer.


if orca is not None and hasattr(orca, "script"):
    class SharedSpoolsScript(orca.script.ScriptPluginCapabilityBase):
        def get_name(self):
            return "Shared Spools — owners, links and sync"

        def get_default_config(self):
            return {"spoolman": "", "moonraker": "", "slots": [1, 2, 3, 4],
                    "mappings": {}, "auto_sync": True}

        def execute(self):
            ui = orca.host.ui
            if not callable(getattr(ui, "create_window", None)) or not hasattr(ui, "WINDOW_MODELESS"):
                raise RuntimeError("Automatic Shared Spools needs Orca's create_window API.")
            self._stopped = Event()
            editor = getattr(getattr(orca.host, "preset", None), "filament", None)
            session = SharedSpoolsSession(json.loads(self.get_config()), self.save_config,
                                         orca.host.preset_bundle, editor, stopped=self._stopped)
            run_session(ui, session, self._stopped)
            return orca.ExecutionResult.success("Shared Spools closed. Automatic sync stopped.")

        def on_cancelled(self):
            if hasattr(self, "_stopped"):
                self._stopped.set()

    @orca.plugin
    class SharedSpoolsPlugin(orca.base):
        def register_capabilities(self):
            orca.register_capability(SharedSpoolsScript)
