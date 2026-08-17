# Google Play declaration notes

## Installed app inventory

Autostarts requests `QUERY_ALL_PACKAGES` because its core purpose is to display broadcast receivers across all installed applications. A narrower `<queries>` declaration cannot implement this package-management function: the user must be able to inspect packages and disabled receiver components regardless of their intent actions.

Suggested Permissions Declaration Form wording:

> Autostarts is an on-device application-management tool. Its primary, user-visible purpose is to enumerate installed apps and inspect their declared broadcast receivers. Package inventory data never leaves the device. Full package visibility is required because receiver actions are arbitrary and disabled components cannot be discovered through intent queries.

The store listing and privacy policy must remain consistent with this behavior. Root access is optional for viewing and required only when the user asks to change receiver state.
