# Shared Spools for OrcaSlicer

Experimental local Python plugin for standard OrcaSlicer 2.5 beta. Run **Shared
Spools — owners, links and sync** from the Plugins dialog. This has automated
tests, but has not been run inside a real Orca installation or against physical
tags. The Android app is built separately.

Version 0.1.1 fixes startup on Orca builds that expose the callback-based
`create_window` API instead of `show_dialog`. Script startup, submit, cancel and
host teardown are covered with a host-interface test double. A real Orca window
test is still required.

Install `shared_spools.py` using the Plugins dialog's local install option. For
manual installation, put just that entry file in its own folder:
`<Orca data directory>/orca_plugins/shared_spools/shared_spools.py`, then reload
plugins/restart Orca. Keep the tests outside that installed folder. The plugin
uses the standard library and has no dependencies.

To update 0.1.0, deactivate the existing Shared Spools plugin, replace its
installed `shared_spools.py` with the 0.1.1 file, click **Refresh** (or restart
Orca), then activate it and press the green play button. Keep the installed
folder name unchanged so the existing plugin configuration remains associated.
If Orca is running on another PC, update that installation too.

1. Open the plugin, enter your Spoolman and Moonraker URLs, and save connections.
   Use the actual Moonraker URL, commonly the printer's port 7125. Allow access
   to those endpoints when Orca asks. This first version supports servers that
   do not need an authentication header.
2. Save an Owner on each physical spool. The same `spool.extra.owner` custom
   field is used by Spool Studio Shared. Search/filter by owner in the dialog.
3. Choose **and remember** an exact Orca profile for each Spoolman filament
   product. Choices belong to the configured Spoolman server and current Orca
   printer preset. Owners do not change print settings.
4. Add four filament slots in Prepare. Review the extruder-to-slot numbers in
   the plugin. Defaults are Extruder 1 → slot 1 through Extruder 4 → slot 4.
5. Use **Sync shared spools** and review the proposed names. Loaded Spoolman IDs
   come from Paxx12's `print_task_config.filament_spool_id`. Empty extruders
   leave their corresponding Orca slots unchanged. Missing mappings stop sync;
   the plugin never guesses from a vendor's first profile.

The plugin uses `orca.host.preset.filament.begin_update(slot, preset_name)` to
select a profile through Orca's normal selection flow. It stages no setting
changes. Builds without that API show an explanatory error; Owner and repair
functions still work. If Orca cancels/rejects a later selection, earlier
successful slot selections remain and are listed in the error. Review them.

This is a separate plugin action. Orca's built-in Sync button retains its own
matching behavior. The plugin does not replace printer communication, upload
G-code, change the printer's spool assignments, or intercept that built-in
button. Unique filament IDs are not required for this name-based action;
Filament ID Fixer remains useful for Orca's native matching workflow.

## Repairing links

Expand **Repair a tag link**, choose the physical spool, and enter the Card UID
reported by the printer or read in Spool Studio. Repair adds/verifies the native
Spoolman tag on 0.27+ and the `card_uids` compatibility mirror used by Paxx12
2.0.0. Earlier Spoolman versions use the mirror only. Other tags and extra fields
are retained; remaining weight is never sent in these updates.

A UID attached to another spool is refused; review and move it explicitly in
Spool Studio. A failed network operation may leave a partial link. Refresh and
retry the same spool. Rescan the tag at the printer after a successful repair.
Repair does not write NFC tag contents; use the Android app for that.

## Validation

```sh
cd orca_plugin
python3 -m unittest -v
```

Tests cover two-tag spools, owner edits, refused conflicts, partial verification,
exact mappings, empty/missing slots and partial selections. Hardware testing and
a real Orca plugin-load test are still required before relying on this version.

API references checked against the tagged Orca beta source:

- [Preset selection implementation](https://github.com/OrcaSlicer/OrcaSlicer/blob/v2.5.0-beta/src/slic3r/plugin/host/PluginHostPresets.cpp)
- [Plugin packaging](https://github.com/OrcaSlicer/OrcaSlicer_WIKI/blob/main/developer_reference/plugin_development/plugin_development.md)
- [Host window bindings](https://github.com/OrcaSlicer/OrcaSlicer/blob/v2.5.0-beta/src/slic3r/plugin/host/PluginHostUi.cpp)
