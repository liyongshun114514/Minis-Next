# Startup repair: provider.db v6 → v5 (1.7.3 / 61)

## Observed on the device

The installed build identifies itself as `6c063a420`, version 1.7.2 (60). Its source restores `ec2caea`, whose ProviderDatabase expects version 5. Builds 58/59 had upgraded provider.db to version 6 by adding the nullable `provider_model_groups.avatar` column. Restoring source does not restore existing device databases.

Read-only device checks found provider.db user_version=6 and minis.db user_version=14. Persistent app logs from 2026-11-16 show:

- 23:55:46.330 and 23:55:46.837: `DAO instanceCount failed: A migration from 6 to 5 was required but not found.`
- 23:55:47.336 and 23:55:47.844: `JSON→DB import failed` for the same missing migration.
- Both failures were followed by a misleading `migrated/synced` message, even though the code only returned a JSON-backed in-memory configuration.

This confirms a persistent database incompatibility and repeated failed startup work; it does not establish that every reported performance symptom or the earlier 6270bd9 bug has this single cause. The two startup load attempts can still race in the baseline. This change fixes their shared failed-open cause without restructuring configuration initialization.

## Change

Register an explicit Room migration 6→5. Rebuild only provider_model_groups, copying all nine v5 columns verbatim. The cancelled avatar column is omitted. All other tables and the JSON mirror are left untouched by the migration. Room executes and validates the migration within its database upgrade/downgrade transaction; no destructive-database fallback is enabled.

Only log JSON→Room sync success when persistence actually succeeds. The error now explicitly identifies the JSON-only in-memory fallback.

Existing ec2caea UI, SOUL and group behavior remain the baseline. Version advances to 1.7.3 / 61.

## Validation

An offline copy of the device provider.db was migrated using the exact production SQL. All five provider instances, 166 model entries, three groups and every v5 value were compared before and after. Every unrelated table was identical. SQLite integrity_check returned `ok`; foreign_key_check returned no rows. Additional synthetic data retained Unicode, quotes, nulls and ordered member IDs. No live database or preferences were edited during diagnosis.

ProviderDowngradeTest exercises production migration SQL and real Room creation→simulated v6→v5 migration→second reopen, including Room schema validation and a DAO call. It is included in the GitHub APK workflow. The native x86_64 CI environment is the integration-test gate; local Python SQL checks do not substitute for Room validation.

Post-install cold-start timing and new device logs remain necessary to confirm the user-visible speed improvement. Do not force-stop the app while it hosts an active agent task. No APK installation, app data reset or live database patch is part of this source repair.
