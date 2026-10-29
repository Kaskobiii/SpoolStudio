import copy
import unittest
from shared_spools import (Spoolman, apply_plan, card_uids, endpoint, extra_text,
                           mapping_key, printer_channels, render, sync_plan, uid_hex)

FIRST = "04AABBCCDDEE11"
SECOND = "04AABBCCDDEE22"


def spool(spool_id=1):
    return {"id": spool_id, "remaining_weight": 892, "filament": {
        "id": 5, "name": "White", "material": "PLA", "vendor": {"name": "Elegoo"},
        "extra": {"variant": '"Plus"'}}, "tags": [{"uid": FIRST}],
        "extra": {"card_uids": f'"{FIRST},{SECOND}"', "owner": '"Owner A"', "custom": '"keep"'}}


class Server:
    def __init__(self, records=None):
        self.records = records or [spool()]
        self.calls = []
        self.fields = [{"key": key, "field_type": "text"} for key in ("owner", "card_uids")]
        self.drop_mirror = False

    def call(self, method, path, body=None):
        self.calls.append((method, path, copy.deepcopy(body)))
        if path == "/api/v1/info":
            return {"version": "0.27.0"}
        if path == "/api/v1/field/spool":
            return self.fields
        if path.startswith("/api/v1/spool?"):
            return copy.deepcopy(self.records)
        if path.startswith("/api/v1/spool/"):
            target = next(s for s in self.records if s["id"] == int(path.split("/")[4]))
            if method == "POST" and path.endswith("/tag"):
                target["tags"].append(body)
            elif method == "PATCH" and not self.drop_mirror:
                target.update(copy.deepcopy(body))
            return copy.deepcopy(target)
        raise AssertionError((method, path))


class SharedSpoolTests(unittest.TestCase):
    def test_owner_preserves_two_tags_custom_fields_and_live_weight(self):
        server = Server()
        Spoolman(server).save_owner(1, "Owner B")
        patch = next(c[2] for c in server.calls if c[0] == "PATCH")
        self.assertEqual(set(patch), {"extra"})
        self.assertEqual(patch["extra"]["card_uids"], f'"{FIRST},{SECOND}"')
        self.assertEqual(patch["extra"]["custom"], '"keep"')
        self.assertEqual(extra_text(server.records[0], "owner"), "Owner B")
        self.assertEqual(server.records[0]["remaining_weight"], 892)

    def test_owner_can_be_cleared(self):
        server = Server()
        Spoolman(server).save_owner(1, "")
        self.assertEqual(extra_text(server.records[0], "owner"), "")

    def test_owner_read_failure_never_patches(self):
        server = Server()
        with self.assertRaises(StopIteration):
            Spoolman(server).save_owner(999, "Owner B")
        self.assertFalse(any(c[0] == "PATCH" for c in server.calls))

    def test_repair_restores_mirror_preserving_second_uid_and_owner(self):
        server = Server()
        server.records[0]["extra"]["card_uids"] = f'"{SECOND}"'
        Spoolman(server).repair_link(1, "04:AA:BB:CC:DD:EE:11")
        self.assertEqual(card_uids(server.records[0]), [FIRST, SECOND])
        self.assertEqual(extra_text(server.records[0], "owner"), "Owner A")
        self.assertTrue(all(set(c[2]) == {"extra"} for c in server.calls if c[0] == "PATCH"))

    def test_repair_creates_missing_native_link(self):
        server = Server()
        server.records[0]["tags"] = []
        Spoolman(server).repair_link(1, FIRST)
        self.assertEqual(server.records[0]["tags"], [{"uid": FIRST}])

    def test_repair_does_not_move_another_spools_uid(self):
        server = Server([spool(1), spool(2)])
        with self.assertRaisesRegex(ValueError, "already belongs to spool #2"):
            Spoolman(server).repair_link(1, FIRST)
        self.assertTrue(all(c[0] == "GET" for c in server.calls))

    def test_partial_repair_is_not_reported_successful(self):
        server = Server()
        server.records[0]["extra"]["card_uids"] = f'"{SECOND}"'
        server.drop_mirror = True
        with self.assertRaisesRegex(ValueError, "mirror is missing"):
            Spoolman(server).repair_link(1, FIRST)

    def test_existing_non_text_owner_is_not_redefined(self):
        server = Server()
        server.fields = [{"key": "owner", "field_type": "integer"}]
        with self.assertRaisesRegex(ValueError, "different type"):
            Spoolman(server).save_owner(1, "Owner B")
        self.assertTrue(all(c[0] == "GET" for c in server.calls))

    def test_uid_validation_and_normalization(self):
        self.assertEqual(uid_hex("04:aa:bb:cc:dd:ee:11"), FIRST)
        for uid in ("", "not-a-UID", "04aa", "00000000"):
            with self.assertRaises(ValueError):
                uid_hex(uid)

    def test_four_channel_query_keeps_uid_separate_from_spool_id(self):
        class Printer:
            def call(self, *_):
                return {"result": {"status": {"print_task_config": {"filament_spool_id": [0, 0, 1, 0]},
                                              "filament_detect": {"info": [{}, {}, {"CARD_UID": [4, 170, 187, 204, 221, 238, 17]}, {}]}}}}
        channels = printer_channels(Printer())
        self.assertEqual(channels[2], {"extruder": 3, "spool_id": 1, "uid": FIRST})

    def plan(self, mappings=None, slots=None, available=None, count=4):
        return sync_plan([{"extruder": 3, "spool_id": 1, "uid": FIRST}], [spool()],
                         mappings if mappings is not None else {mapping_key("U1", 5): "Elegoo PLA Plus"},
                         "U1", slots or [1, 2, 3, 4], available or ["Elegoo Galaxy PLA", "Elegoo PLA Plus"], count)

    def test_exact_mapping_ignores_vendor_list_order(self):
        plan = self.plan()
        self.assertEqual(plan[0]["profile"], "Elegoo PLA Plus")
        self.assertEqual(plan[0]["slot"], 3)

    def test_missing_mapping_refuses_fallback(self):
        with self.assertRaisesRegex(ValueError, "exact profile"):
            self.plan(mappings={})

    def test_mismatched_printer_uid_refuses_stale_spool_assignment(self):
        with self.assertRaisesRegex(ValueError, "Repair the link"):
            sync_plan([{"extruder": 3, "spool_id": 1, "uid": "04AABBCCDDEE33"}],
                      [spool()], {mapping_key("U1", 5): "Elegoo PLA Plus"},
                      "U1", [1, 2, 3, 4], ["Elegoo PLA Plus"], 4)

    def test_wrong_printer_mapping_is_not_used(self):
        with self.assertRaisesRegex(ValueError, "exact profile"):
            self.plan(mappings={mapping_key("Other printer", 5): "Elegoo PLA Plus"})

    def test_slot_mapping_and_insufficient_slots(self):
        self.assertEqual(self.plan(slots=[2, 3, 4, 1])[0]["slot"], 4)
        for slots, count in (([1, 1, 3, 4], 4), ([1, 2, 3, 4], 1)):
            with self.assertRaises(ValueError):
                self.plan(slots=slots, count=count)

    def test_apply_selects_exact_name_without_mutating_settings(self):
        calls = []
        class Editor:
            def begin_update(self, slot, name):
                calls.append((slot, name))
                return self
            def commit(self):
                calls.append("commit")
        apply_plan(Editor(), self.plan())
        self.assertEqual(calls, [(3, "Elegoo PLA Plus"), "commit"])

    def test_partial_sync_names_completed_slots(self):
        class Editor:
            def begin_update(self, slot, name):
                if slot == 4:
                    raise RuntimeError("cancelled")
                return self
            def commit(self):
                pass
        plan = self.plan()
        with self.assertRaisesRegex(RuntimeError, "Applied: slot 3: Elegoo PLA Plus"):
            apply_plan(Editor(), plan + [{**plan[0], "slot": 4}])

    def test_html_escapes_server_names_and_owners(self):
        record = spool()
        record["extra"]["owner"] = '"<script>alert(1)</script>"'
        page = render({"slots": [1, 2, 3, 4]}, [record], [], ["PLA <Plus>"], "U1")
        self.assertNotIn("<script>alert(1)</script>", page)
        self.assertIn("PLA &lt;Plus&gt;", page)

    def test_endpoint_validation(self):
        self.assertEqual(endpoint(" http://printer:7125/ "), "http://printer:7125")
        for url in ("file:///tmp/test", "http://user:pass@printer", "http://printer?secret=value"):
            with self.assertRaises(ValueError):
                endpoint(url)


if __name__ == "__main__":
    unittest.main()
