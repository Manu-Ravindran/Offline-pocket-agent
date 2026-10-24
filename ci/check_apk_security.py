"""Check the merged APK manifest, including permissions contributed by libraries."""
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def inspect_manifest(text, release=False):
    root = ET.fromstring(text)
    package = root.attrib["package"]
    application = root.find("application")
    assert application is not None, "APK application is missing"
    errors = []
    for permission in root.findall("uses-permission"):
        name = permission.get(ANDROID + "name", "")
        if name.startswith("android.permission."):
            errors.append("Unexpected platform permission: " + name)
    if application.get(ANDROID + "allowBackup") != "false":
        errors.append("Application backups must be disabled")
    if application.get(ANDROID + "usesCleartextTraffic") != "false":
        errors.append("Cleartext traffic must be disabled")
    if release and application.get(ANDROID + "debuggable", "false") != "false":
        errors.append("Release APK must not be debuggable")
    sandbox = None
    for component in application:
        name = component.get(ANDROID + "name", "")
        if name.endswith("PdfSandboxService"):
            sandbox = component
        if component.get(ANDROID + "exported") == "true":
            launcher = component.tag == "activity" and name in (".MainActivity", "com.offline.pocketagent.MainActivity", package + ".MainActivity")
            if not launcher:
                errors.append("Unexpected exported component: " + name)
    if sandbox is None or sandbox.get(ANDROID + "isolatedProcess") != "true" or sandbox.get(ANDROID + "exported") != "false":
        errors.append("PDF service must be isolated and private")
    return errors


if __name__ == "__main__":
    errors = inspect_manifest(open(sys.argv[1]).read(), "--release" in sys.argv[2:])
    for error in errors:
        print(error)
    if errors:
        raise SystemExit(1)
    print("Merged APK security checks passed.")
