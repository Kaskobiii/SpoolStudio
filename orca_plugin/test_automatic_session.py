import copy
import json
import unittest
from threading import Event
from types import SimpleNamespace
from unittest.mock import patch

from shared_spools import (SharedSpoolsSession, automatic_profile, mapping_key,
                           page_body, run_session)
from test_shared_spools import Server, FIRST, spool


PROFILES = [{"name": "Elegoo Galaxy PLA @System"},
            {"name": "Elegoo PLA+ @System"},
            {"name": "Elegoo PLA+ Black", "is_user": True},
            {"name": "Elegoo PLA+ White", "is_user": True}]


class Fixture:
    def __init__(self, config=None):
        self.server = Server()
        self.channels = [{"extruder": i, "spool_id": 1 if i == 3 else 0,
                          "uid": FIRST if i == 3 else ""} for i in range(1, 5)]
        self.names = ["Elegoo Galaxy PLA @System"] * 4
        self.applied = []
        self.saved = []
        self.fail_spoolman = False
        self.fail_printer = False
        self.printer = "U1"
        self.profiles = PROFILES
        self.stop = Event()
        self.session = SharedSpoolsSession(config or self.config(), self.save,
                                          self.bundle, self, self.factory, self.stop)

    @staticmethod
    def config():
        return {"spoolman": "http://spoolman:7912", "moonraker": "http://printer:7125"}

    def save(self, data):
        self.saved.append(json.loads(data))
        return True

    def factory(self, url):
        if url.startswith("http://spoolman"):
            if self.fail_spoolman:
                raise OSError("network permission denied")
            return self.server
        if self.fail_printer:
            raise OSError("printer unreachable")
        return SimpleNamespace(channels=copy.deepcopy(self.channels))

    def bundle(self):
        records = [SimpleNamespace(name=p["name"], is_visible=True, is_compatible=True,
                                   is_user=lambda p=p: p.get("is_user", False)) for p in self.profiles]
        return SimpleNamespace(
            current_printer_preset=lambda: SimpleNamespace(name=self.printer),
            current_filament_preset_names=lambda: self.names[:],
            filaments=SimpleNamespace(size=lambda: len(records), preset=lambda i: records[i]))

    def begin_update(self, slot, name):
        self.applied.append((slot, name))
        self.names[slot - 1] = name
        return self

    def commit(self):
        pass

    @staticmethod
    def channels_from(server):
        return copy.deepcopy(server.channels)

    def form(self, action="connect", **values):
        return {"action": action, **self.config(), "slots": [1, 2, 3, 4],
                "auto_sync": True, **values}


class AutomaticMatchTests(unittest.TestCase):
    def test_plus_aliases_and_color_specific_user_profile_beat_first_vendor_profile(self):
        record = spool()["filament"]
        for variant in ("+", "Plus", "PLUS"):
            record["extra"]["variant"] = json.dumps(variant)
            name, _ = automatic_profile(record, PROFILES)
            self.assertEqual(name, "Elegoo PLA+ White")

    def test_product_order_does_not_change_match(self):
        self.assertEqual(automatic_profile(spool()["filament"], list(reversed(PROFILES)))[0],
                         "Elegoo PLA+ White")

    def test_never_falls_back_to_galaxy_or_wrong_color(self):
        self.assertEqual(automatic_profile(spool()["filament"], [PROFILES[0], PROFILES[2]])[0], "")

    def test_colorless_plus_is_available_when_white_profile_is_absent(self):
        self.assertEqual(automatic_profile(spool()["filament"], PROFILES[:3])[0], "Elegoo PLA+ @System")

    def test_ambiguous_profiles_require_one_saved_choice(self):
        variants = [dict(PROFILES[3]), {"name": "Elegoo PLA Plus White", "is_user": True}]
        name, reason = automatic_profile(spool()["filament"], variants)
        self.assertEqual(name, "")
        self.assertIn("Several", reason)

    def test_basic_material_does_not_select_a_specialty_variant(self):
        filament = spool()["filament"]
        filament["extra"]["variant"] = '"Basic"'
        self.assertEqual(automatic_profile(filament, PROFILES)[0], "")
        self.assertEqual(automatic_profile(filament, PROFILES + [{"name": "Elegoo PLA White"}])[0],
                         "Elegoo PLA White")


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.channels = patch("shared_spools.printer_channels", Fixture.channels_from)
        self.channels.start()
        self.addCleanup(self.channels.stop)

    def test_reopening_loads_saved_connections_without_saving_again_and_selects_plus(self):
        f = Fixture()
        f.session.handle({"action": "ready"})
        self.assertEqual(len(f.session.spools), 1)
        self.assertEqual(f.names[2], "Elegoo PLA+ White")
        self.assertEqual(f.applied, [(3, "Elegoo PLA+ White")])
        self.assertEqual(f.saved, [])

    def test_connection_addresses_survive_first_run_permission_denial_and_reopen(self):
        f = Fixture({"spoolman": "", "moonraker": ""})
        f.fail_spoolman = True
        f.session.handle(f.form())
        self.assertEqual(f.saved[-1]["spoolman"], "http://spoolman:7912")
        self.assertIn("connection failed", f.session.message)
        self.assertEqual(f.applied, [])
        reopened = Fixture(f.saved[-1])
        reopened.session.handle({"action": "ready"})
        self.assertEqual(len(reopened.session.spools), 1)
        self.assertEqual(reopened.names[2], "Elegoo PLA+ White")

    def test_refresh_does_not_repeat_selections_or_touch_empty_extruders(self):
        f = Fixture()
        f.session.refresh()
        f.session.refresh()
        self.assertEqual(f.applied, [(3, "Elegoo PLA+ White")])
        self.assertEqual(f.names[0], "Elegoo Galaxy PLA @System")

    def test_automatic_follow_recovers_native_sync_overwrite_with_window_open(self):
        f = Fixture()
        f.session.refresh()
        f.names[2] = "Elegoo Galaxy PLA @System"
        f.session.refresh()
        self.assertEqual(f.names[2], "Elegoo PLA+ White")
        self.assertEqual(len(f.applied), 2)

    def test_pause_prevents_automatic_selection_but_explicit_sync_works(self):
        f = Fixture({**Fixture.config(), "auto_sync": False})
        f.session.refresh()
        self.assertEqual(f.applied, [])
        f.session.handle(f.form("sync", auto_sync=False))
        self.assertEqual(f.names[2], "Elegoo PLA+ White")

    def test_saved_choice_is_retained_across_reopen(self):
        f = Fixture()
        f.session.handle(f.form("mapping", filament=5, profile="Elegoo PLA+ @System"))
        reopened = Fixture(f.saved[-1])
        reopened.session.handle({"action": "ready"})
        self.assertEqual(reopened.names[2], "Elegoo PLA+ @System")

    def test_missing_saved_profile_stops_instead_of_using_automatic_fallback(self):
        f = Fixture({**Fixture.config(), "mappings": {mapping_key("U1", 5): "Deleted profile"}})
        f.session.refresh()
        self.assertEqual(f.applied, [])
        self.assertIn("unavailable", f.session.page())

    def test_connection_failure_retains_visible_spools_without_syncing_stale_data(self):
        f = Fixture()
        f.session.refresh()
        f.applied.clear()
        f.fail_spoolman = True
        f.names[2] = "Elegoo Galaxy PLA @System"
        f.session.handle(f.form("sync"))
        self.assertEqual(len(f.session.spools), 1)
        self.assertEqual(f.applied, [])
        self.assertIn("connection failed", f.session.message)

    def test_printer_failure_retains_spool_list_and_clears_assignments(self):
        f = Fixture()
        f.session.refresh()
        f.fail_printer = True
        f.session.refresh()
        self.assertEqual(len(f.session.spools), 1)
        self.assertEqual(f.session.channels, [])

    def test_preset_readback_detects_silent_selection_failure(self):
        f = Fixture()
        f.begin_update = lambda *_: f
        f.session.refresh()
        self.assertIn("did not retain", f.session.message)
        self.assertNotIn("Loaded profiles verified", f.session.message)

    def test_changed_printer_assignments_block_selection(self):
        f = Fixture()
        with patch("shared_spools.printer_channels", side_effect=[f.channels, []]):
            f.session.refresh()
        self.assertEqual(f.applied, [])
        self.assertIn("assignments changed", f.session.message)

    def test_stop_prevents_late_profile_selection(self):
        f = Fixture()
        f.stop.set()
        f.session.refresh()
        self.assertEqual(f.applied, [])

    def test_single_window_stays_open_across_ready_save_and_network_error(self):
        f = Fixture()
        stop = f.stop
        messages, calls = [], []

        class Window:
            def post(self, data):
                messages.append(data)
                if data.get("html"):
                    if len([m for m in messages if m.get("html")]) == 1:
                        f.fail_spoolman = True
                        calls[0]["on_message"](f.form())
                    else:
                        calls[0]["on_close"]()
            def is_open(self):
                return not stop.is_set()
            def close(self):
                stop.set()

        def create_window(**kwargs):
            calls.append(kwargs)
            kwargs["on_message"]({"action": "ready"})
            return Window()

        run_session(SimpleNamespace(create_window=create_window, WINDOW_MODELESS=0), f.session, stop)
        self.assertEqual(len(calls), 1)
        self.assertIn("orca.postMessage", calls[0]["html"])
        self.assertNotIn("orca.submit", calls[0]["html"])
        pages = [m["html"] for m in messages if m.get("html")]
        self.assertEqual(len(pages), 2)
        self.assertIn("Spoolman connection failed", pages[-1])
        self.assertIn("White", pages[-1])
        self.assertNotIn("<script>", page_body(f.session.page()))

    def test_poll_follows_a_later_change_without_another_button_press_or_window(self):
        f = Fixture()
        calls, pages = [], []

        class Window:
            def post(self, data):
                if data.get("html"):
                    pages.append(data)
                    if len(pages) == 1:
                        f.names[2] = "Elegoo Galaxy PLA @System"
                    else:
                        calls[0]["on_close"]()
            def is_open(self):
                return not f.stop.is_set()
            def close(self):
                f.stop.set()

        def create_window(**kwargs):
            calls.append(kwargs)
            kwargs["on_message"]({"action": "ready"})
            return Window()

        run_session(SimpleNamespace(create_window=create_window, WINDOW_MODELESS=0),
                    f.session, f.stop, poll_seconds=0.01)
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(f.applied), 2)
        self.assertTrue(pages[-1]["poll"])

    def test_ambiguous_auto_match_pauses_without_changing_any_slot(self):
        f = Fixture()
        f.profiles = [{"name": "Elegoo PLA Plus White", "is_user": True}, PROFILES[3]]
        f.session.refresh()
        self.assertEqual(f.applied, [])
        self.assertIn("Several exact profiles", f.session.page())

    def test_refresh_escapes_network_error_details(self):
        f = Fixture()
        def fail(_):
            raise OSError('<img src=x onerror="alert(1)">')
        f.session.server_factory = fail
        f.session.refresh()
        self.assertNotIn('<img src=x', f.session.page())
        self.assertIn('&lt;img', f.session.page())

    def test_owner_save_refreshes_without_requiring_save_connections_again(self):
        f = Fixture()
        f.session.handle(f.form("owner", id=1, owner="Friend"))
        self.assertIn("Owner saved and verified", f.session.message)
        self.assertEqual(json.loads(f.session.spools[0]["extra"]["owner"]), "Friend")

    def test_url_change_discards_previous_server_mapping(self):
        f = Fixture({**Fixture.config(), "mappings": {mapping_key("U1", 5): "Old server"}})
        f.session.handle(f.form(spoolman="http://spoolman-new:7912"))
        self.assertEqual(f.saved[-1]["mappings"], {})

    def test_configuration_save_failure_never_starts_network_or_sync(self):
        f = Fixture()
        f.session.save = lambda _: False
        with patch.object(f.session, "refresh") as refresh:
            f.session.handle(f.form())
        refresh.assert_not_called()
        self.assertIn("not saved", f.session.message)
        self.assertEqual(f.applied, [])

    def test_cancelled_selection_is_not_prompted_again_on_every_poll(self):
        f = Fixture()
        attempts = []
        def cancelled(*args):
            attempts.append(args)
            raise RuntimeError("User cancelled")
        f.begin_update = cancelled
        f.session.refresh()
        f.session.refresh()
        self.assertEqual(len(attempts), 1)
        self.assertIn("Selection is paused", f.session.message)
        f.session.handle(f.form())
        self.assertEqual(len(attempts), 2)


if __name__ == "__main__":
    unittest.main()
