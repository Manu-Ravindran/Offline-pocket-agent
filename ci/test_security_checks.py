import base64
import contextlib
import io
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock
from check_apk_security import inspect_manifest
from prepare_signing import valid_password
import prepare_signing as signing


BASE = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.offline.pocketagent">
<application android:allowBackup="false" android:usesCleartextTraffic="false">
<activity android:name="com.offline.pocketagent.MainActivity" android:exported="true"/>
<service android:name="com.offline.pocketagent.security.PdfSandboxService" android:isolatedProcess="true" android:exported="false"/>
</application></manifest>'''


class SecurityChecksTest(unittest.TestCase):
    def test_safe_manifest_is_accepted(self):
        self.assertEqual([], inspect_manifest(BASE, release=True))

    def test_library_network_permission_is_rejected(self):
        text = BASE.replace('<application', '<uses-permission android:name="android.permission.INTERNET"/><application')
        self.assertTrue(any('permission' in x for x in inspect_manifest(text)))
        sdk23 = text.replace('<uses-permission ', '<uses-permission-sdk-23 ')
        self.assertTrue(any('permission' in x for x in inspect_manifest(sdk23)))

    def test_debuggable_release_is_rejected(self):
        text = BASE.replace('<application ', '<application android:debuggable="true" ')
        self.assertTrue(any('debuggable' in x for x in inspect_manifest(text, release=True)))

    def test_exported_provider_and_nonisolated_pdf_are_rejected(self):
        text = BASE.replace('</application>', '<provider android:name="bad.Provider" android:exported="true"/></application>')
        self.assertTrue(any('exported' in x for x in inspect_manifest(text)))
        self.assertTrue(any('PDF' in x for x in inspect_manifest(BASE.replace('isolatedProcess="true"', 'isolatedProcess="false"'))))

    def test_signing_password_must_be_long_and_single_line(self):
        self.assertFalse(valid_password('short'))
        self.assertFalse(valid_password('a' * 32 + '\n'))
        self.assertTrue(valid_password('example-test-only-' + 'a' * 32))

    @unittest.skipUnless(shutil.which('gpg') and shutil.which('keytool'), 'Signing tools are unavailable')
    def test_encrypted_release_key_roundtrip_and_wrong_password_failure(self):
        # Public test fixture; this is never used as an application signing key.
        password = 'test-fixture-only-' + 'Z' * 40
        captured = {}

        def upload(method='GET', payload=None, history=False):
            if history:
                return []
            self.assertEqual('PUT', method)
            self.assertEqual('main', payload['branch'])
            captured['encrypted'] = base64.b64decode(payload['content'])
            return {}

        with contextlib.redirect_stdout(io.StringIO()):
            with mock.patch.object(signing, 'get_ciphertext', return_value=None), mock.patch.object(signing, 'api_request', side_effect=upload):
                signing.bootstrap(password)
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'github-env'
                with mock.patch.dict(os.environ, {'RUNNER_TEMP': directory, 'GITHUB_ENV': str(output)}), mock.patch.object(signing, 'get_ciphertext', return_value=captured['encrypted']):
                    signing.prepare(password)
                    settings = dict(line.split('=', 1) for line in output.read_text().splitlines())
                    self.assertEqual('true', settings['POCKET_SIGNING_READY'])
                    self.assertTrue(Path(settings['POCKET_SIGNING_STORE_FILE']).is_file())
                    with self.assertRaises(RuntimeError):
                        signing.prepare('incorrect-test-password-' + 'X' * 40)

    def test_deleted_signing_key_is_never_silently_replaced(self):
        with mock.patch.object(signing, 'get_ciphertext', return_value=None), mock.patch.object(signing, 'api_request', return_value=[{'sha': 'old-key-commit'}]), mock.patch.object(signing, 'run') as commands:
            with self.assertRaises(RuntimeError):
                signing.bootstrap('test-only-' + 'X' * 40)
            commands.assert_not_called()


if __name__ == '__main__':
    unittest.main()
