# Server payload directory

Put files you want to distribute here, then register each one in the root `manifest.yml`.

- `plugins/`: plugin JAR files; target them to `plugins/YourPlugin.jar`.
- `configs/`: config files; target them to the exact destination under the server directory.
- `worlds/`: ZIP archives of new worlds. Use `type: world-zip` and a simple folder name as `target`.

Do not upload secrets, player databases, logs, live server data, or backups to this public repository.
