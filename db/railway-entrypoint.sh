#!/bin/bash
# Railway のボリュームは root 所有でマウントされるため、Oracle（oracle ユーザー）がデータファイルを作れない。
# 起動時にマウント先の所有者だけを oracle に直してから、イメージ本来の起動処理（oracle ユーザーのまま）に渡す。
set -euo pipefail
sudo -n chown oracle:oinstall /opt/oracle/oradata
exec container-entrypoint.sh "$@"
