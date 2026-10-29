"""Host-facing regression coverage, including the API without show_dialog."""
import importlib.util
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from shared_spools import show_plugin_dialog


class Window:
    def __init__(self):
        self.closed = False

    def close(self):
        self.closed = True

    def is_open(self):
        return not self.closed


class CallbackUI:
    WINDOW_MODAL = 1

    def __init__(self, action=None, silent_close=False):
        self.action = action
        self.silent_close = silent_close
        self.window = Window()
        self.calls = []

    # Match the public C++ binding's signature; no show_dialog attribute exists.
    def create_window(self, html, title="OrcaSlicer", width=820, height=600,
                      on_message=None, on_close=None, style=0, on_submit=None):
        self.calls.append({"html": html, "title": title, "width": width,
                           "height": height, "style": style})
        if self.silent_close:
            self.window.close()
        elif self.action is None:
            on_close()
        else:
            on_submit(self.action)
            on_close()  # A later close must not discard an already submitted action.
        return self.window


class HostUiTests(unittest.TestCase):
    def open(self, ui):
        return show_plugin_dialog(ui, html="<p>Form</p>", title="Shared Spools", width=1180, height=780)

    def test_callback_window_returns_submitted_form_and_closes(self):
        action = {"action": "owner", "id": 1, "owner": "Owner A"}
        ui = CallbackUI(action)
        self.assertEqual(self.open(ui), action)
        self.assertTrue(ui.window.closed)
        self.assertEqual(ui.calls[0]["style"], ui.WINDOW_MODAL)
        self.assertEqual((ui.calls[0]["width"], ui.calls[0]["height"]), (1180, 780))

    def test_cancel_closes_without_a_form_action(self):
        ui = CallbackUI()
        self.assertIsNone(self.open(ui))
        self.assertTrue(ui.window.closed)

    def test_worker_waits_for_a_later_ui_callback(self):
        ready = Event()
        class DelayedUI(CallbackUI):
            def create_window(self, html, title="OrcaSlicer", width=820, height=600,
                              on_message=None, on_close=None, style=0, on_submit=None):
                self.submit = on_submit
                ready.set()
                return self.window
        ui = DelayedUI()
        with ThreadPoolExecutor(max_workers=1) as executor:
            result = executor.submit(self.open, ui)
            self.assertTrue(ready.wait(2))
            self.assertFalse(result.done())
            ui.submit({"action": "connect"})
            self.assertEqual(result.result(timeout=2), {"action": "connect"})
        self.assertTrue(ui.window.closed)

    def test_host_teardown_without_callback_does_not_leave_worker_waiting(self):
        self.assertIsNone(self.open(CallbackUI(silent_close=True)))

    def test_legacy_dialog_remains_supported(self):
        class LegacyUI:
            def show_dialog(self, *, html, title, width, height):
                return {"action": "connect", "title": title}
        self.assertEqual(self.open(LegacyUI()), {"action": "connect", "title": "Shared Spools"})

    def test_unsupported_ui_has_an_actionable_error(self):
        with self.assertRaisesRegex(RuntimeError, "supported plugin window API"):
            self.open(SimpleNamespace())

    def test_real_script_entry_opens_with_only_create_window_available(self):
        ui = CallbackUI()
        preset = SimpleNamespace(name="Example PLA", is_visible=True, is_compatible=True)
        collection = SimpleNamespace(size=lambda: 1, preset=lambda i: preset)
        bundle = SimpleNamespace(filaments=collection,
                                 current_printer_preset=lambda: SimpleNamespace(name="Snapmaker U1"))

        class ScriptBase:
            def get_config(self):
                return "{}"

        fake_orca = SimpleNamespace(
            script=SimpleNamespace(ScriptPluginCapabilityBase=ScriptBase),
            base=object, plugin=lambda cls: cls,
            host=SimpleNamespace(ui=ui, preset_bundle=lambda: bundle),
            ExecutionResult=SimpleNamespace(success=lambda message: message),
        )
        spec = importlib.util.spec_from_file_location("shared_spools_host_smoke", Path(__file__).with_name("shared_spools.py"))
        module = importlib.util.module_from_spec(spec)
        with patch.dict("sys.modules", {"orca": fake_orca}):
            spec.loader.exec_module(module)
        self.assertEqual(module.SharedSpoolsScript().execute(), "Shared Spools closed.")
        self.assertEqual(len(ui.calls), 1)
        self.assertIn("Spoolman URL", ui.calls[0]["html"])


if __name__ == "__main__":
    unittest.main()
