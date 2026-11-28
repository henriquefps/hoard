Answers of hoard-server 0.15.0 (`origin/main` fa7ce45, `/v1` with a Hoard device key), recorded from its own test
harness (the set-up of `TestTrashBackupOnlyIsWebOnlyForDevices`: a backup-only item freed on its phone and a NAS shot,
both moved to the trash by the device): `GET /v1/trash`, `POST /v1/trash/delete` of the backup-only unit, and
`POST /v1/trash/empty` with stale figures (409). `ServerTrashRealJsonTest` replays them through the client.
