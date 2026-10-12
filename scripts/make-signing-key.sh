#!/usr/bin/env bash
# Creates TagMacro's release signing key on THIS computer and (optionally) stores it as
# GitHub Actions secrets, so the Release workflow can sign APKs. Run once:
#
#   bash scripts/make-signing-key.sh
#
# The password is random and is written only to a private file next to the key; it is never
# shown on screen. BACK UP BOTH FILES. If they are lost, nobody can publish updates that
# install over the existing app.
set -euo pipefail

DIR="${KEY_DIR:-$HOME/keystores}"
KEY="$DIR/tagmacro-release.jks"
PASS="$DIR/tagmacro-release.password"
ALIAS="tagmacro"
REPO="${REPO:-Unpiloted0852/TagMacro}"

keytool_bin=""
for k in "${JAVA_HOME:-}/bin/keytool" "$HOME/android-studio/jbr/bin/keytool" /opt/android-studio/jbr/bin/keytool "$(command -v keytool || true)"; do
  if [ -n "$k" ] && [ -x "$k" ]; then keytool_bin="$k"; break; fi
done
[ -n "$keytool_bin" ] || { echo "keytool not found. Install a JDK or set JAVA_HOME."; exit 1; }

mkdir -p "$DIR"
chmod 700 "$DIR"
if [ -e "$KEY" ]; then
  echo "A key already exists at $KEY. Not overwriting it."
else
  umask 077
  head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32 > "$PASS"
  "$keytool_bin" -genkeypair -v -keystore "$KEY" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=TagMacro, O=Unpiloted0852" \
    -storepass:file "$PASS" -keypass:file "$PASS" >/dev/null
  chmod 600 "$KEY" "$PASS"
  echo "Created $KEY"
  echo "Password saved in $PASS"
fi

# Local builds: keystore.properties in the project root (git-ignored).
root="$(cd "$(dirname "$0")/.." && pwd)"
pw="$(cat "$PASS")"
cat > "$root/keystore.properties" <<PROPS
storeFile=$KEY
storePassword=$pw
keyAlias=$ALIAS
keyPassword=$pw
PROPS
chmod 600 "$root/keystore.properties"
echo "Wrote keystore.properties for local signed builds."

echo
echo "Signing certificate SHA-256 (safe to share, used to verify downloads):"
"$keytool_bin" -list -v -keystore "$KEY" -storepass:file "$PASS" | grep -m1 "SHA256:" | sed 's/.*SHA256: //'

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo
  echo "Storing the key as GitHub Actions secrets in $REPO ..."
  base64 -w0 "$KEY" | gh secret set TAGMACRO_KEYSTORE_BASE64 --repo "$REPO"
  gh secret set TAGMACRO_KEYSTORE_PASSWORD --repo "$REPO" < "$PASS"
  printf '%s' "$ALIAS" | gh secret set TAGMACRO_KEY_ALIAS --repo "$REPO"
  gh secret set TAGMACRO_KEY_PASSWORD --repo "$REPO" < "$PASS"
  echo "Done. Publishing a GitHub release will now attach a signed APK."
else
  echo
  echo "GitHub CLI (gh) isn't installed or signed in, so add these four repository secrets by hand:"
  echo "  github.com/$REPO → Settings → Secrets and variables → Actions → New repository secret"
  echo "  TAGMACRO_KEYSTORE_BASE64 = output of: base64 -w0 \"$KEY\""
  echo "  TAGMACRO_KEYSTORE_PASSWORD = contents of $PASS"
  echo "  TAGMACRO_KEY_ALIAS = $ALIAS"
  echo "  TAGMACRO_KEY_PASSWORD = contents of $PASS"
fi
