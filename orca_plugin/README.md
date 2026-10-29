# Shared Spools for OrcaSlicer 0.2.0

Experimental plugin for standard OrcaSlicer 2.5 beta. Run **Shared Spools — owners,
links and sync** with the green Play button in Plugins. This version reduces the
manual steps and keeps one window alive instead of closing and recreating it for
every action.

## What is automatic

- Saved Spoolman and Moonraker connections reload when you reopen the plugin.
- Addresses are saved before the first network request, so a network permission
  prompt or failure does not discard them.
- One modeless window displays loading, errors and results without reopening.
- With **Automatically follow loaded spools** enabled (the default), the plugin
  refreshes about every five seconds while its window is open and selects the
  profiles for the currently loaded, linked spools. It checks the selected names
  afterward. Closing the window stops automatic updates.
- Automatic matching requires an exact product family: vendor, material and
  variant, with `+` and `Plus` treated alike. A matching color-specific profile is
  preferred, then a user profile; a black-specific profile is not used for a white
  spool. A colorless exact-product profile can be used if no color-specific match
  exists. Galaxy PLA is not a fallback for PLA Plus. Display-list order is ignored.
- Ambiguous or missing matches pause selection. Choose **and remember** a profile
  once for those products. Saved choices take priority and stay local to each PC,
  Spoolman server and Orca printer preset. A missing saved profile is reported.

**Orca's normal Sync button is unchanged.** Orca's script API does not expose a
replacement for its native matching routine. Keep the plugin window open for its
automatic follow mode; there is no need to press the native Sync button. If another
action overwrites a loaded slot, the next successful refresh restores the matched
profile, provided the four-slot layout remains intact. Pause automatic follow to
make your own manual profile selections.

## Install or update

Download `shared_spools.py`. For an existing installation, deactivate Shared
Spools, replace only its installed script, click **Refresh** or restart Orca, then
activate and press Play. Keep the existing folder name to retain configuration.
Update each PC separately. The Android APK is unchanged.

For a new manual installation, put the entry file in its own folder:
`<Orca data directory>/orca_plugins/shared_spools/shared_spools.py`.
Keep test files outside the installed folder. The plugin uses Python's standard
library and Orca's callback-based `create_window` API.

1. Enter the full Spoolman URL, including any port, and the printer's Moonraker URL
   (commonly port 7125). Save connections once. Allow network access to these
   addresses when Orca asks. Servers requiring authentication headers are not
   supported in this version.
2. Have four filament slots in Prepare. Defaults map Extruder 1 → slot 1 through
   Extruder 4 → slot 4. Empty printer extruders keep their current Orca profile.
3. Leave automatic follow enabled and keep this modeless window open. Unambiguous
   products need no remembered mapping or separate Sync click.
4. Owner editing uses Spoolman's per-spool extra field `owner`, shared with Spool
   Studio Shared. Owner filtering changes the list, not loaded-spool assignments.

## Tag links and data

Expand **Repair a tag link**, choose the physical spool and enter the Card UID
reported by the printer or Spool Studio. Repair verifies the native Spoolman tag
on 0.27+ and `card_uids`, the compatibility mirror used by Paxx12 2.0.0. Other tags
and extra fields are retained; remaining weight is never sent in these edits.
A UID belonging to another spool is refused. A failed request may have partly
completed: refresh before retrying. Rescan a repaired tag at the printer. Repair
does not write NFC contents; use the Android app for that.

Loaded spool IDs come from Paxx12's `print_task_config.filament_spool_id`. Reported
UIDs must match those spools. Changes to printer assignments are checked again
before selecting profiles. The plugin changes filament slot selections through
`orca.host.preset.filament.begin_update(slot, preset_name)`; it stages no preset
setting changes, uploads no G-code and changes no printer spool assignments.
Orca may prompt about unsaved edits. A cancelled or failed later selection leaves
earlier successful selections in place and reports them. The window can be closed
to stop automatic follow.

## Validation and limitations

Run `python3 -m unittest discover -s orca_plugin -v` from the repository root.
Tests cover automatic PLA+/Plus matching, Galaxy rejection, ambiguity, persistence,
reopening, network denial and recovery, one-window message processing, polling,
read-back verification, two-tag repairs, owners and stale assignments. The browser
preview exercises the rendered controls with simulated servers. These checks do
not replace testing the release in a real Orca webview and on the printer.

Source bindings checked against Orca's tagged beta:

- [Preset selection](https://github.com/OrcaSlicer/OrcaSlicer/blob/v2.5.0-beta/src/slic3r/plugin/host/PluginHostPresets.cpp)
- [Window callbacks](https://github.com/OrcaSlicer/OrcaSlicer/blob/v2.5.0-beta/src/slic3r/plugin/host/PluginHostUi.cpp)
- [Page messaging](https://github.com/OrcaSlicer/OrcaSlicer/blob/v2.5.0-beta/src/slic3r/GUI/Widgets/WebHosting.cpp)
- [Native Sync matching](https://github.com/OrcaSlicer/OrcaSlicer/blob/v2.5.0-beta/src/libslic3r/PresetBundle.cpp)
