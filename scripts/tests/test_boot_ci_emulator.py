"""Exercise CI startup with fake SDK tools; never connect to a real device."""

import os
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "boot-ci-emulator.sh"
FAKE_TOOL = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import sys
import time

root = Path(os.environ["TEST_ROOT"])
scenario = os.environ["TEST_SCENARIO"]
tool = Path(sys.argv[0]).name
avd_home = Path(os.environ["ANDROID_AVD_HOME"])
(root / (tool + "-avd-home")).write_text(str(avd_home))
if tool == "avdmanager":
    if scenario == "create-fails":
        print("AVD creation failed", flush=True)
        sys.exit(7)
    if scenario != "missing-definition":
        (avd_home / "persistence-api36.ini").write_text("avd.ini.encoding=UTF-8\n")
elif tool == "emulator":
    if sys.argv[1:] == ["-list-avds"]:
        if scenario != "undiscoverable":
            print("persistence-api36")
    else:
        (root / "emulator-started").touch()
        if scenario == "exits":
            print("Emulator startup exploded", flush=True)
            sys.exit(3)
        print("Emulator is starting", flush=True)
        time.sleep(600)
elif tool == "adb":
    if scenario == "adb-hangs":
        time.sleep(600)
    if scenario in ("exits", "timeout"):
        print("0")
    elif scenario == "offline-first" and not (root / "adb-retried").exists():
        (root / "adb-retried").touch()
        print("device offline", file=sys.stderr)
        sys.exit(1)
    else:
        while not (root / "emulator-started").exists():
            time.sleep(0.01)
        print("1\r")
'''


class BootCiEmulatorTest(unittest.TestCase):
    def run_boot(self, scenario):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / "sdk"
            (sdk / "emulator").mkdir(parents=True)
            tools = root / "bin"
            tools.mkdir()
            for path in (tools / "avdmanager", tools / "adb", sdk / "emulator/emulator"):
                path.write_text(FAKE_TOOL)
                path.chmod(0o755)
            env = {
                **os.environ,
                "PATH": f"{tools}:{os.environ['PATH']}",
                "ANDROID_HOME": str(sdk),
                "ANDROID_AVD_HOME": str(root / "isolated-avds"),
                "ANDROID_SERIAL": "emulator-5554",
                "EMULATOR_BOOT_TIMEOUT_SECONDS": "1" if scenario in ("timeout", "adb-hangs") else "20",
                "TEST_ROOT": str(root),
                "TEST_SCENARIO": scenario,
            }
            process = subprocess.Popen(
                ["bash", str(SCRIPT)],
                cwd=root,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                start_new_session=True,
            )
            try:
                output, _ = process.communicate(timeout=10)
                files = {
                    str(path.relative_to(root)): path.read_text()
                    for path in root.rglob("*")
                    if path.is_file()
                }
                return process.returncode, output, files, env["ANDROID_AVD_HOME"]
            finally:
                # Stop only fake tools in this test's private process group.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()

    def test_success_uses_shared_avd_directory(self):
        code, output, files, avd_home = self.run_boot("success")
        self.assertEqual(code, 0, output)
        for tool in ("avdmanager", "emulator", "adb"):
            self.assertEqual(files[f"{tool}-avd-home"], avd_home)
        self.assertIn("emulator-started", files)
        self.assertIn("ci-diagnostics/avd-list.txt", files)

    def test_creation_failure_stops_before_launch(self):
        code, output, files, _ = self.run_boot("create-fails")
        self.assertNotEqual(code, 0, output)
        self.assertIn("AVD creation failed", output)
        self.assertNotIn("emulator-started", files)

    def test_missing_definition_stops_before_launch(self):
        code, output, files, _ = self.run_boot("missing-definition")
        self.assertNotEqual(code, 0, output)
        self.assertIn("AVD definition is missing", output)
        self.assertNotIn("emulator-started", files)

    def test_undiscoverable_avd_stops_before_launch(self):
        code, output, files, _ = self.run_boot("undiscoverable")
        self.assertNotEqual(code, 0, output)
        self.assertIn("Emulator cannot discover", output)
        self.assertNotIn("emulator-started", files)

    def test_process_exit_reports_log_without_waiting_for_boot_timeout(self):
        code, output, _, _ = self.run_boot("exits")
        self.assertNotEqual(code, 0, output)
        self.assertIn("Emulator exited before boot completed", output)
        self.assertIn("Emulator startup exploded", output)

    def test_boot_timeout_reports_log(self):
        code, output, _, _ = self.run_boot("timeout")
        self.assertNotEqual(code, 0, output)
        self.assertIn("Emulator boot timed out", output)
        self.assertIn("Emulator is starting", output)

    def test_blocking_adb_probe_is_bounded(self):
        code, output, _, _ = self.run_boot("adb-hangs")
        self.assertNotEqual(code, 0, output)
        self.assertIn("Emulator boot timed out", output)

    def test_transient_offline_adb_is_retried(self):
        code, output, files, _ = self.run_boot("offline-first")
        self.assertEqual(code, 0, output)
        self.assertIn("adb-retried", files)


if __name__ == "__main__":
    unittest.main()
