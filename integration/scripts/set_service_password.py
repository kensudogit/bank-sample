"""MuleSoft 連携のサービスアカウント（svc-mule）にパスワードを設定し、有効化する。

.env の MULE_CORE_PASSWORD を PBKDF2-HMAC-SHA256（Liberty の PasswordHasher と同じ形式）でハッシュ化し、
Oracle コンテナ内の SQL*Plus に標準入力で渡して app_user を更新する。パスワードはコマンドライン引数や
ログに出さない（ハッシュだけを送る）。

    python integration/scripts/set_service_password.py            # 設定して有効化
    python integration/scripts/set_service_password.py --disable  # 無効化（ログイン不可に戻す）
"""

from __future__ import annotations

import base64
import hashlib
import os
import secrets
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ITERATIONS = 120_000  # backend の PasswordHasher.ITERATIONS と同じ
USER_ID = "svc-mule"


def read_env(path: Path) -> dict[str, str]:
    """KEY=VALUE 形式の .env を読む（コメント・空行は無視）。"""
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()
    return env


def pbkdf2_hash(password: str) -> str:
    """Liberty の PasswordHasher.hash と同じ形式: pbkdf2_sha256$回数$ソルト(Base64)$ハッシュ(Base64)。"""
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, ITERATIONS, 32)
    return f"pbkdf2_sha256${ITERATIONS}${base64.b64encode(salt).decode()}${base64.b64encode(dk).decode()}"


def run_sql(statement: str) -> str:
    """Oracle コンテナの SQL*Plus（sysdba）で BANK スキーマに対して SQL を実行する。"""
    script = (
        "WHENEVER SQLERROR EXIT FAILURE\n"
        "ALTER SESSION SET CONTAINER = FREEPDB1;\n"
        "ALTER SESSION SET CURRENT_SCHEMA = BANK;\n"
        f"{statement}\n"
        "COMMIT;\n"
        "EXIT\n"
    )
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "oracle", "sqlplus", "-s", "/ as sysdba"],
        input=script, text=True, capture_output=True, cwd=ROOT, encoding="utf-8",
    )
    if result.returncode != 0:
        sys.exit(f"SQL の実行に失敗しました:\n{result.stdout}\n{result.stderr}")
    return result.stdout


def main() -> None:
    if "--disable" in sys.argv:
        out = run_sql(f"UPDATE app_user SET enabled = 0, password_hash = 'disabled' WHERE user_id = '{USER_ID}';")
        print(f"{USER_ID} を無効化しました。{out.strip()}")
        return

    env = read_env(ROOT / ".env")
    password = os.environ.get("MULE_CORE_PASSWORD") or env.get("MULE_CORE_PASSWORD")
    if not password:
        sys.exit(".env に MULE_CORE_PASSWORD を設定してください（10〜64 文字・英字と数字を含む）。")
    if not (10 <= len(password) <= 64) or not any(c.isalpha() for c in password) or not any(c.isdigit() for c in password):
        sys.exit("MULE_CORE_PASSWORD はパスワード規則（10〜64 文字・英字と数字を含む）を満たしていません。")
    if "'" in password:
        sys.exit("MULE_CORE_PASSWORD に ' は使えません。")

    out = run_sql(
        f"UPDATE app_user SET password_hash = '{pbkdf2_hash(password)}', enabled = 1, failed_count = 0, "
        f"locked_until = NULL, must_change_password = 0, password_changed_at = CURRENT_TIMESTAMP "
        f"WHERE user_id = '{USER_ID}' AND role = 'INTEGRATION';"
    )
    if "1 row updated" not in out and "1行が更新されました" not in out:
        sys.exit(f"{USER_ID} が見つかりません。先に db/init/12_integration.sql を適用してください。\n{out}")
    print(f"{USER_ID} のパスワードを設定し、有効化しました。")


if __name__ == "__main__":
    main()
