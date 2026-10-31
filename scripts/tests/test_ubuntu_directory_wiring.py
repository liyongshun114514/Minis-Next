"""Current Android Ubuntu directory wiring; legacy names only identify old data."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'src/android/app'
JAVA = APP / 'src/main/java/com/openminis/app'


class UbuntuDirectoryWiringTest(unittest.TestCase):
    def test_new_root_and_staging_are_ubuntu_named(self):
        text = (JAVA / 'sandbox/SandboxDistribution.kt').read_text()
        self.assertIn('const val ROOTFS_DIR_NAME = "Ubuntu-rootfs"', text)
        self.assertIn('const val STAGING_DIR_NAME = "Ubuntu-rootfs.staging"', text)
        self.assertIn('const val LEGACY_BACKUP_PREFIX = "Ubuntu-rootfs.previous-"', text)

    def test_file_provider_only_shares_the_new_rootfs(self):
        tree = E.parse(APP / 'src/main/res/xml/file_provider_paths.xml')
        paths = [n.get('path') for n in tree.getroot() if n.get('name') == 'rootfs']
        self.assertEqual(['Ubuntu-rootfs/'], paths)
        self.assertFalse(any(n.get('path') in ('alpine-rootfs/', '.', './', '/')
                             for n in tree.getroot() if n.tag == 'files-path'))

    def test_network_and_storage_do_not_hardcode_root_name(self):
        for path in ('network/ShellTransparentNet.kt', 'ui/settings/StorageUsageViewModel.kt'):
            text = (JAVA / path).read_text()
            self.assertNotRegex(text, r'File\([^\n]*"(?:alpine|Ubuntu)-rootfs')
            self.assertTrue('rootfsDir' in text or 'ROOTFS_DIR_NAME' in text, path)

    def test_current_about_description_is_ubuntu(self):
        text = (JAVA / 'ui/sandbox/RootfsManagementScreen.kt').read_text()
        self.assertNotIn('Alpine Linux filesystem', text)
        self.assertIn('Ubuntu', text)

    def test_rootfs_screen_omits_legacy_guest_notice(self):
        text = (JAVA / 'ui/sandbox/RootfsManagementScreen.kt').read_text()
        for removed in ('迁移保留的旧环境', '原客体数据已保留', 'legacyBackupPath'):
            self.assertNotIn(removed, text)
        # Real migration failures must remain visible, not be hidden with the notice.
        self.assertIn('state.migrationError', text)

    def test_rootfs_ui_state_no_longer_tracks_legacy_notice_path(self):
        text = (JAVA / 'ui/sandbox/RootfsManagementViewModel.kt').read_text()
        self.assertNotIn('legacyBackupPath', text)
        self.assertNotIn('manager.legacyBackupDirs', text)
        self.assertIn('migrationError = manager.rootfsMigrationError', text)

    def test_removing_notice_does_not_auto_delete_migration_backups(self):
        # This is source wiring validation; actual migration behavior is covered
        # by RootfsDirectoryMigration{,Integration}Test in the Android CI job.
        manager = (JAVA / 'sandbox/RootfsManager.kt').read_text()
        self.assertIn('val preserved = preserveExistingRootfs()', manager)
        self.assertIn('lastLegacyBackup = preserved', manager)
        storage = (JAVA / 'ui/settings/StorageUsageViewModel.kt').read_text()
        self.assertIn('manager.legacyBackupDirs', storage)

    def test_cloud_requires_real_migration_reports(self):
        text = (ROOT / '.github/workflows/android-ubuntu.yml').read_text()
        for name in ('RootfsDirectoryMigrationTest', 'RootfsDirectoryMigrationIntegrationTest'):
            self.assertIn("--tests '*" + name + "'", text)
            self.assertIn('sandbox.' + name, text)

    def test_normal_android_runtime_uses_no_apk_install_commands(self):
        paths = [APP / 'src/main/assets/default_mount/usr/local/bin/minis-mcp-cli',
                 APP / 'src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/utils/deps.py',
                 JAVA / 'agent/shell/OnDemandBash.kt']
        for path in paths:
            text = path.read_text()
            self.assertNotIn('apk add', text, str(path))
            self.assertIn('apt-get', text, str(path))


if __name__ == '__main__':
    unittest.main()
