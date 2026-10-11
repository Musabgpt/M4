# Optional Termux status

Embedded Alpine/PRoot/PT​Y remains the only implemented terminal backend.
Termux RUN_COMMAND and Termux:API are not registered or advertised as available.
They require a compatible installed app, its official permission and external
command configuration, and a verified result adapter. This environment has no
Android device on which to discover those prerequisites. No new Termux permission
or shared-storage access was added. Missing Termux does not affect Alpine.

The next optional connector must label official background results as batch
output unless it can independently prove streaming/interactive support. Android
UID isolation is not bypassed and private paths are not shared across apps.
