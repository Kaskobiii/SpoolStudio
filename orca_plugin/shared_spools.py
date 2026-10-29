# /// script
# requires-python = ">=3.12"
# dependencies = []
# [tool.orcaslicer.plugin]
# name = "Shared Spools"
# description = "Spool owners, Paxx12 tag-link repair and explicit filament-profile sync."
# author = "Kaskobiii"
# version = "0.1.0"
# ///
"""Standalone Orca plugin; networking and mapping logic can also be unit tested."""
import html
import json
import re
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


def apply_plan(editor, plan):
    done = []
    for row in plan:
        try:
            # An empty transaction selects the named profile; no preset settings are changed.
            editor.begin_update(row["slot"], row["profile"]).commit()
        except Exception as error:
            completed = ", ".join(f"slot {r['slot']}: {r['profile']}" for r in done) or "none"
            raise RuntimeError(f"Sync stopped. Applied: {completed}. {error}") from error
        done.append(row)
    return done


def escaped(value):
    return html.escape(str(value), quote=True)


def profile_options(profiles, selected):
    options = '<option value="">Choose exact profile…</option>'
    for name in profiles:
        checked = " selected" if name == selected else ""
        options += f'<option value="{escaped(name)}"{checked}>{escaped(name)}</option>'
    return options


def render(config, spools, channels, profiles, printer, message=""):
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
<td><select id="p{spool['id']}" aria-label="Profile for spool {spool['id']}">{profile_options(profiles, config.get('mappings', {}).get(key))}</select>
<button onclick="send('mapping',{{filament:{filament['id']},profile:document.getElementById('p{spool['id']}').value}})">Remember profile</button></td></tr>''')
    channel_rows = "".join(f"<li>Extruder {c['extruder']}: spool #{c['spool_id'] or '—'} · UID {escaped(c['uid'] or 'not reported')}</li>" for c in channels)
    slot_inputs = " ".join(f'<label>E{i+1} → slot <input class="slot" type="number" min="1" max="4" value="{s}" aria-label="Slot for extruder {i+1}"></label>' for i, s in enumerate(config.get("slots", [1, 2, 3, 4])))
    spool_options = "".join(f'<option value="{s["id"]}">Spool #{s["id"]} · {escaped(extra_text(s, "owner") or "Unassigned")}</option>' for s in spools)
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>
body{{font-family:system-ui;margin:24px}}input,select,button{{padding:8px;margin:4px;max-width:350px}}button{{cursor:pointer}}table{{width:100%;border-collapse:collapse}}td,th{{text-align:left;border-bottom:1px solid var(--orca-border,#777);padding:10px}}small{{display:block;color:var(--orca-muted,#888);overflow-wrap:anywhere}}.slot{{width:45px}}.notice{{padding:12px;border:1px solid var(--orca-border,#777)}}details{{margin-top:22px}}.servers{{display:flex;gap:12px;flex-wrap:wrap}}label{{display:inline-block}}</style></head><body>
<h2>Shared Spools</h2><p class="notice">{escaped(message or 'Choose each product’s exact profile, then use Sync shared spools. Empty extruders keep their current Orca profile.')}</p>
<form onsubmit="return false"><div class="servers"><label>Spoolman URL<br><input id="spoolman" aria-label="Spoolman URL" value="{escaped(config.get('spoolman',''))}" placeholder="http://spoolman:8000"></label>
<label>Moonraker URL<br><input id="moonraker" aria-label="Moonraker URL" value="{escaped(config.get('moonraker',''))}" placeholder="http://printer:7125"></label></div>
<p>{slot_inputs}</p><button onclick="send('connect')">Save connections / refresh</button>
<button onclick="send('sync')">Sync shared spools</button><button onclick="orca.close()">Close</button>
<p>Printer profile: <strong>{escaped(printer)}</strong>. Slot numbers above refer to the filament list in Prepare.</p><ul>{channel_rows}</ul>
<label>Filter by owner <input aria-label="Filter by owner" placeholder="All owners" oninput="filterOwners(this.value)"></label>
<table><thead><tr><th>Spool / product / tags</th><th>Owner</th><th>Remaining</th><th>Exact Orca profile</th></tr></thead><tbody>{''.join(rows)}</tbody></table>
<details><summary>Repair a tag link</summary><p>Choose the physical spool and copy the Card UID shown by your printer. Other tags and remaining weight are retained. A UID linked to another spool is refused.</p>
<select id="repairSpool" aria-label="Spool to repair">{spool_options}</select><input id="repairUid" aria-label="Card UID to repair" placeholder="04:AA:BB:CC:DD:EE:11">
<button onclick="send('repair',{{id:Number(document.getElementById('repairSpool').value),uid:document.getElementById('repairUid').value}})">Repair this link</button></details>
<p><small>Remember profile applies to all spools of that Spoolman filament product for this printer preset. This plugin’s Sync action is separate from Orca’s built-in sync button. Rescan a repaired tag to refresh the printer.</small></p></form>
<script>function send(action,extra={{}}){{orca.submit({{action,spoolman:document.getElementById('spoolman').value,moonraker:document.getElementById('moonraker').value,slots:Array.from(document.querySelectorAll('.slot'),x=>Number(x.value)),...extra}})}}
function filterOwners(value){{for(const row of document.querySelectorAll('tr[data-owner]'))row.hidden=!row.dataset.owner.includes(value.trim().toLowerCase())}}</script></body></html>'''


try:
    import orca
except ModuleNotFoundError:
    orca = None  # Allows independent tests without embedding OrcaSlicer.


if orca is not None and hasattr(orca, "script"):
    class SharedSpoolsScript(orca.script.ScriptPluginCapabilityBase):
        def get_name(self):
            return "Shared Spools — owners, links and sync"

        def get_default_config(self):
            return {"spoolman": "", "moonraker": "", "slots": [1, 2, 3, 4], "mappings": {}}

        def execute(self):
            config = {**self.get_default_config(), **json.loads(self.get_config())}
            spools, channels, message = [], [], ""
            while True:
                bundle = orca.host.preset_bundle()
                printer = bundle.current_printer_preset().name
                presets = bundle.filaments
                profiles = sorted(presets.preset(i).name for i in range(presets.size())
                                  if presets.preset(i).is_visible and presets.preset(i).is_compatible)
                data = orca.host.ui.show_dialog(html=render(config, spools, channels, profiles, printer, message),
                                               title="Shared Spools", width=1180, height=780)
                if not data:
                    return orca.ExecutionResult.success("Shared Spools closed.")
                try:
                    urls = {k: endpoint(data[k]) for k in ("spoolman", "moonraker")}
                    action = data["action"]
                    if action != "connect" and any(urls[k] != config[k] for k in urls):
                        raise ValueError("Save connections / refresh before changing spools on another server.")
                    if urls["spoolman"] != config["spoolman"]:
                        config["mappings"] = {}  # IDs belong to a particular Spoolman server.
                        spools = []
                    if urls["moonraker"] != config["moonraker"]:
                        channels = []
                    config.update(urls)
                    config["slots"] = data["slots"]
                    spoolman = Spoolman(JsonServer(config["spoolman"]))
                    moonraker = JsonServer(config["moonraker"])
                    if action == "owner":
                        spoolman.save_owner(data["id"], data["owner"])
                        message = "Owner saved and verified."
                    elif action == "repair":
                        spoolman.repair_link(data["id"], data["uid"])
                        message = "Native/compatibility link verified. Rescan the tag on the printer."
                    elif action == "mapping":
                        if data["profile"] not in profiles:
                            raise ValueError("Choose an available exact profile.")
                        config["mappings"][mapping_key(printer, data["filament"])] = data["profile"]
                        message = "Exact profile choice remembered."
                    elif action == "sync":
                        editor = getattr(getattr(orca.host, "preset", None), "filament", None)
                        if editor is None or not hasattr(editor, "begin_update"):
                            raise ValueError("This Orca build lacks the filament selection API. Owners and link repair are still available.")
                        current = printer_channels(moonraker)
                        records = spoolman.all_spools()
                        bundle = orca.host.preset_bundle()
                        if bundle.current_printer_preset().name != printer:
                            raise ValueError("Printer preset changed. Refresh and review the mappings.")
                        plan = sync_plan(current, records, config["mappings"], printer,
                                         config["slots"], profiles, len(bundle.current_filament_preset_names()))
                        preview = "\n".join(f"Extruder {r['extruder']} → slot {r['slot']}: {r['profile']}" for r in plan)
                        if orca.host.ui.message(preview, title="Sync shared spools", buttons="ok_cancel", icon="question") == "ok":
                            if current != printer_channels(moonraker):
                                raise ValueError("Printer assignments changed during preview. Refresh and retry.")
                            if orca.host.preset_bundle().current_printer_preset().name != printer:
                                raise ValueError("Printer preset changed during preview. Refresh and retry.")
                            apply_plan(editor, plan)
                            message = "Exact profiles selected: " + "; ".join(r["profile"] for r in plan)
                        else:
                            message = "Sync cancelled."
                    if not self.save_config(json.dumps(config)):
                        raise ValueError("Settings were not saved; any completed remote edits remain applied.")
                    spools = spoolman.all_spools()
                    if action == "connect":
                        message = "Connected. Choose profiles and save them before syncing."
                    try:
                        channels = printer_channels(moonraker)
                    except Exception as error:
                        channels = []
                        message += " Printer refresh failed: " + str(error)
                except Exception as error:
                    message = str(error) + " Reload before retrying a failed repair; it may have partially completed."

    @orca.plugin
    class SharedSpoolsPlugin(orca.base):
        def register_capabilities(self):
            orca.register_capability(SharedSpoolsScript)
