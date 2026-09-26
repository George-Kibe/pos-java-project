# Deploying to production

A runbook for putting the POS on a single VPS behind your own domain, then bringing each branch
online and testing it. Every command here is meant to be copied; the placeholders are:

| Placeholder | Means | Example |
|---|---|---|
| `pos.example.co.ke` | the address staff will open | your domain or a subdomain of it |
| `203.0.113.10` | the VPS's public IPv4 address | from your VPS provider |
| `198.51.100.x` | a branch's or head office's public internet address | from that site's ISP |
| `deploy` | the Linux user that runs the stack | any non-root user |

Contents:

1. [What production looks like](#1-what-production-looks-like)
2. [Before you start](#2-before-you-start)
3. [Prepare the VPS](#3-prepare-the-vps)
4. [Point the domain at it](#4-point-the-domain-at-it)
5. [Get the code onto the VPS](#5-get-the-code-onto-the-vps)
6. [Configure `.env`](#6-configure-env)
7. [Create the signing key](#7-create-the-signing-key)
8. [First start](#8-first-start)
9. [Check it from the outside](#9-check-it-from-the-outside)
10. [Head office goes first](#10-head-office-goes-first)
11. [Bringing a branch online](#11-bringing-a-branch-online)
12. [The branch acceptance test](#12-the-branch-acceptance-test)
13. [M-Pesa in production](#13-m-pesa-in-production)
14. [Day-to-day operations](#14-day-to-day-operations)
15. [Troubleshooting](#15-troubleshooting)
16. [Not done yet](#16-not-done-yet)

---

## 1. What production looks like

```
 branch / HQ browser ──HTTPS──▶ Traefik :443 ──▶ web (Next.js BFF) ──▶ api-gateway ──▶ services
                                   │                                        ▲
 Safaricom (M-Pesa callbacks) ─────┴── /api/v1/payments/mpesa/callbacks/ ───┘
```

- **Traefik** (`infra/traefik/`) is the only thing listening on the internet: ports 80 and 443. It
  gets a Let's Encrypt certificate for `POS_DOMAIN` by itself.
- **Only listed networks get in.** Traefik answers `403 Forbidden` to any address not in
  `ALLOWED_CLIENT_NETWORKS`; the gateway checks the same list again. The M-Pesa callback path is the
  one exception (Safaricom calls from its own addresses; the path carries a secret token).
- **Only registered devices can sign in** (`DEVICE_REGISTRATION_REQUIRED=true` in the overlay).
  The administrator is the exception, so the first devices can be registered.
- Postgres, Redis, Kafka and the object store listen on `127.0.0.1` only, and the web app and the
  gateway are not published at all.

It is all started by one command, `make prod-up`, which layers `infra/compose/docker-compose.prod.yml`
over the development files.

## 2. Before you start

Have these ready. Nothing below can be invented - a missing value stops the stack from starting.

- [ ] **A VPS.** Ubuntu 24.04 LTS, **4 vCPU, 16 GB RAM** (8 GB is the floor: the stack uses about
      5 GB at rest, and the first build needs more), **80 GB SSD**, a static public IPv4 address.
      A provider with a Nairobi or Johannesburg region keeps the tills' latency low.
- [ ] **The domain**, and access to its DNS.
- [ ] **An email address** for Let's Encrypt expiry warnings (`ACME_EMAIL`).
- [ ] **Every site's public internet address** - head office and each branch, and each one's backup
      line (4G/LTE failover) too. Ask each ISP for a **static** address; a dynamic one changes
      without warning and locks the site out. [Section 11](#11-bringing-a-branch-online) shows how
      to read the address a site really uses.
- [ ] **Outgoing mail**: AWS SES SMTP credentials (or a Gmail app password) and a `MAIL_FROM` on a
      domain you can send from. Password resets and staff invitations depend on it.
- [ ] **M-Pesa production credentials** from the Daraja portal, when you are ready to take M-Pesa
      (see [section 13](#13-m-pesa-in-production)). The POS runs without them - cash and card work.
- [ ] Your **SSH public key**.

## 3. Prepare the VPS

Log in as root once, create the `deploy` user, and lock SSH down:

```bash
ssh root@203.0.113.10

adduser --disabled-password --gecos "" deploy
usermod -aG sudo deploy
mkdir -p /home/deploy/.ssh
cp ~/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh && chmod 700 /home/deploy/.ssh
echo "deploy ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/deploy

# Keys only, no root login.
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/; s/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl restart ssh
```

From here on, work as `deploy`:

```bash
ssh deploy@203.0.113.10

sudo apt update && sudo apt -y upgrade
sudo apt -y install make git python3 openssl curl dnsutils unattended-upgrades

# Firewall: SSH, HTTP (certificate challenges, redirects) and HTTPS.
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
```

> Docker publishes ports around `ufw`. That is safe here only because production publishes nothing
> but Traefik's 80 and 443 - everything else is bound to `127.0.0.1` or not published. Never add a
> `ports:` entry on `0.0.0.0` to the compose files.

Install Docker Engine from Docker's own repository (not Ubuntu's `docker.io`):

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker deploy
exit    # log in again so the group applies
ssh deploy@203.0.113.10
docker version && docker compose version
```

A swap file keeps the first build from running out of memory:

```bash
sudo fallocate -l 8G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Set the clock's zone (logs stay in UTC inside the containers; this is for you reading them):

```bash
sudo timedatectl set-timezone Africa/Nairobi
```

## 4. Point the domain at it

At your DNS provider, create **one A record**:

| Type | Name | Value | TTL |
|---|---|---|---|
| A | `pos` (for `pos.example.co.ke`) | `203.0.113.10` | 300 |

**Do not add an AAAA (IPv6) record.** The allowlist holds your sites' IPv4 addresses; a browser
that reaches the server over IPv6 arrives from an address that is not on it and is refused.

Check it has propagated before going on - Let's Encrypt must be able to find the server:

```bash
dig +short pos.example.co.ke      # must print 203.0.113.10
dig +short AAAA pos.example.co.ke # must print nothing
```

## 5. Get the code onto the VPS

The repository is private, so give the VPS a read-only **deploy key**:

```bash
ssh-keygen -t ed25519 -C "pos-vps" -f ~/.ssh/pos_deploy -N ""
cat ~/.ssh/pos_deploy.pub
```

Add that public key on GitHub: the repository → **Settings → Deploy keys → Add deploy key**
(leave "Allow write access" off). Then:

```bash
cat >> ~/.ssh/config <<'EOF'
Host github-pos
  HostName github.com
  User git
  IdentityFile ~/.ssh/pos_deploy
EOF

sudo mkdir -p /opt/pos && sudo chown deploy:deploy /opt/pos
git clone github-pos:George-Kibe/pos-java-project.git /opt/pos/app
cd /opt/pos/app
git checkout main
```

## 6. Configure `.env`

`make env` copies `.env.example` and generates every password and secret the project makes
itself (database roles, Redis, `WEB_SESSION_SECRET`, `JWT_KEYSTORE_PASSWORD`, the bootstrap
administrator's password, `MPESA_CALLBACK_TOKEN`):

```bash
cd /opt/pos/app
make env
chmod 600 .env
```

Now fill in what only you know. Edit `.env` with `nano .env`, or set each value with the helper
below - it replaces the line and never prints the file:

```bash
setenv() { sed -i "s|^$1=.*|$1=$2|" .env; grep -q "^$1=" .env || echo "$1=$2" >> .env; }

# The site and its certificate
setenv POS_DOMAIN pos.example.co.ke
setenv ACME_EMAIL ops@example.co.ke

# Who may reach it: head office first; branches are added as they come online (section 11).
setenv ALLOWED_CLIENT_NETWORKS 198.51.100.20

# The first administrator: a real mailbox, so a password reset can reach you.
setenv AUTH_BOOTSTRAP_EMAIL you@example.co.ke

# Mail - AWS SES shown; for Gmail use smtp.gmail.com and an app password.
setenv SMTP_HOST email-smtp.af-south-1.amazonaws.com
setenv SMTP_PORT 587
setenv SMTP_USERNAME AKIAxxxxxxxxxxxxxxxx
setenv SMTP_PASSWORD 'the-ses-smtp-password'
setenv MAIL_FROM '"Realhive Group of Supermarkets POS <no-reply@example.co.ke>"'

# HTTPS everywhere, so session cookies are Secure.
setenv WEB_COOKIE_SECURE true
```

What each production value does:

| Key | Required | Notes |
|---|---|---|
| `POS_DOMAIN` | yes | Traefik routes and certifies it; email links use `https://POS_DOMAIN`. |
| `ACME_EMAIL` | yes | Let's Encrypt's expiry notices. |
| `ALLOWED_CLIENT_NETWORKS` | yes | Comma-separated IPv4 addresses or CIDR ranges (`198.51.100.20,198.51.100.32/29`). Everyone else gets 403. |
| `JWT_KEYSTORE_PASSWORD` | yes | Generated by `make env`; used in the next section. |
| `AUTH_BOOTSTRAP_EMAIL` / `_PASSWORD` | yes | The first administrator. The password is generated; you change it at first sign-in. |
| `SMTP_*`, `MAIL_FROM` | for mail | Without them no email is delivered: no password resets, no invitations. |
| `MPESA_*` | for M-Pesa | See [section 13](#13-m-pesa-in-production). |
| `DEVICE_REGISTRATION_REQUIRED` | - | Leave as is: the production overlay turns it on regardless. |
| `S3_*` | no | Product images stay in the bundled object store on this VPS unless you set `S3_ENDPOINT=` (empty), `S3_PATH_STYLE=false`, `S3_CREATE_BUCKET=false`, `S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` for AWS S3. |

Read the bootstrap password once, store it in your password manager, and keep `.env` backed up
somewhere safe (it is the only copy of these secrets):

```bash
grep '^AUTH_BOOTSTRAP_PASSWORD=' .env | cut -d= -f2-
```

> Do not `source .env` in your shell: `BRAND_NAME` and `MAIL_FROM` contain spaces. Compose and the
> Makefile read it correctly; to read one value, `grep` it as above.

## 7. Create the signing key

auth-service signs every access token. Without a key file it would invent a new key at each start
and every signed-in person's token would stop verifying after a restart. Create the key once,
with `keytool` from the official Java image (no Java needed on the VPS):

```bash
cd /opt/pos/app
mkdir -p secrets && chmod 700 secrets
PASS="$(grep '^JWT_KEYSTORE_PASSWORD=' .env | cut -d= -f2-)"

docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/secrets:/out" eclipse-temurin:21-jre \
  keytool -genkeypair -alias "pos-$(date +%Y%m)" -keyalg RSA -keysize 2048 -validity 3650 \
  -storetype PKCS12 -keystore /out/jwt-keystore.p12 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Realhive POS auth"

chmod 644 secrets/jwt-keystore.p12   # the service runs as a non-root user and must read it
unset PASS
```

`secrets/` is git-ignored. Back up `secrets/jwt-keystore.p12` with `.env`. Losing it is not a
disaster - generate a new one and everyone signs in again - but do not swap it casually.

## 8. First start

```bash
cd /opt/pos/app
make prod-up
```

The first run builds every image on the VPS - expect **20 to 40 minutes**. It refuses to start
if `POS_DOMAIN`, `ACME_EMAIL`, `ALLOWED_CLIENT_NETWORKS` or `JWT_KEYSTORE_PASSWORD` is missing,
and it returns once every container is healthy. On an empty server, Postgres creates each
service's schema and role, Kafka's topics are created, every service runs its Flyway migrations,
and auth-service creates the bootstrap administrator.

Confirm:

```bash
alias pos='docker compose --env-file .env -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.services.yml -f infra/compose/docker-compose.prod.yml'
echo "alias pos='$(alias pos | cut -d\' -f2)'" >> ~/.bashrc    # keep it for next time

pos ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
```

Every container should read `healthy`, and only `pos-traefik` should show `0.0.0.0:80` and
`0.0.0.0:443`. Then check the keys and the certificate:

```bash
pos logs auth-service | grep -E "signing key|ephemeral"
# expect: Loaded 1 signing key(s) from /run/secrets/jwt-keystore.p12

pos logs traefik | grep -iE "acme|certificate|error" | tail
# no "unable to obtain ACME certificate"; if there is, check DNS (section 4) and port 80/443
```

## 9. Check it from the outside

The VPS's own address is **not** on the allowlist, which makes it a free test of the refusal:

```bash
# From the VPS - refused at the edge, with a real certificate:
curl -sS -o /dev/null -w '%{http_code} %{ssl_verify_result}\n' https://pos.example.co.ke/login
# expect: 403 0      (403 = allowlist working; 0 = certificate valid)

# Plain HTTP is redirected to HTTPS:
curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' http://pos.example.co.ke/
# expect: 301 https://pos.example.co.ke/

# The M-Pesa callback path is open, but a wrong token is simply not found:
curl -sS -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' \
  https://pos.example.co.ke/api/v1/payments/mpesa/callbacks/stk/not-the-token
# expect: 404

# Nothing but 80 and 443 answers from outside (run from your own computer):
nc -zv -w 3 203.0.113.10 5432 8080 3000 6379 29092   # all must fail
```

## 10. Head office goes first

From a computer **at head office** (its address is the one you put in `ALLOWED_CLIENT_NETWORKS`):

1. Open `https://pos.example.co.ke`. The sign-in page appears, with "This device is not
   registered".
2. Sign in as `AUTH_BOOTSTRAP_EMAIL` with the bootstrap password. You are made to choose your own
   password (12+ characters), then sign in again.
3. **Users** → create a **second administrator** for yourself or a colleague. Two administrators
   means one can always recover the other.
4. **Branches** → create head office's branch record if you trade there, then each branch:
   code, name, time zone, **Open for trading**.
5. **Settings** → receipts, email wording, the expense approval limit. **Catalog setup** → tax
   classes and rates.
6. **Devices** (Alt+3) → register head office's own computers, and type each code on its
   computer (**Register it** on the sign-in page).
7. **Menu → Manual** → the administrator's manual has the rest of the set-up in order.

Send yourself a password reset (sign out → **Forgot your password?**) to prove mail works and that
the link opens `https://pos.example.co.ke/reset-password?...`, not `localhost`.

## 11. Bringing a branch online

Do this per branch, ideally the day before it goes live.

### 11.1 Find the branch's real address

At the branch, on the shop's network (a till or the office PC - not a phone on mobile data):

```bash
curl -4 https://ifconfig.me ; echo
```

Or open `https://ifconfig.me` in the browser. If the branch has a backup line, **unplug the main
line**, let the router fail over, and read the address again - you need both.

If nobody at the branch can run that, let them open the POS and read the address Traefik refused:

```bash
pos logs --since 15m traefik 2>&1 | awk '$9 == 403 {print $1}' | sort | uniq -c | sort -rn
# the count and address of every refused visitor in the last 15 minutes
```

Check with the ISP that the address is **static**. Compare it again the next day if unsure.

### 11.2 Add it to the allowlist

```bash
cd /opt/pos/app
grep '^ALLOWED_CLIENT_NETWORKS=' .env
# append the branch, e.g. main line and backup line:
sed -i 's|^ALLOWED_CLIENT_NETWORKS=.*|&,198.51.100.40,198.51.100.41|' .env
grep '^ALLOWED_CLIENT_NETWORKS=' .env

# Traefik and the gateway read the list at start: recreate just those two.
pos up -d --wait traefik api-gateway
```

Commas only, no spaces needed; a CIDR such as `198.51.100.40/30` covers a small block from one ISP.

### 11.3 Its first device

The branch manager cannot sign in until one device at the branch is registered, so as the
administrator: **Devices** → the branch → name the manager's computer ("Westlands Office PC") →
**Register** → read the code to the manager by phone. On that computer they open the POS, choose
**Register it**, type the code (30 minutes, once) and sign in. The manager then registers the
tills the same way (their manual, **Devices**).

## 12. The branch acceptance test

Run through this at each branch before the first customer. Keep a copy per branch.

| # | Test | Expect | ✓ |
|---|---|---|---|
| 1 | Open the POS on a till | Sign-in page, "This device: Till 1, *branch*" | |
| 2 | On a phone **on mobile data**, open the POS | `Forbidden` - the allowlist works | |
| 3 | On a phone **on the shop wifi**, sign in as a cashier | "This device is not registered for the POS" | |
| 4 | Sign in as a cashier on the till | The till opens | |
| 5 | **Open shift** with the counted float | Shift open, drawer shows the float | |
| 6 | Scan three products, one weighed | Right names and prices, weighed price from the label | |
| 7 | **Cash** sale with change | Change offered from the drawer, receipt prints (**Alt+P** sets up the printer) | |
| 8 | **Card** sale | Terminal reference recorded, receipt prints | |
| 9 | **M-Pesa** sale (KES 1-10 of real money) | Prompt on the phone, sale completes when paid ([section 13](#13-m-pesa-in-production)) | |
| 10 | Supervisor **price override** and **void** | Asks for the supervisor's PIN | |
| 11 | **Return** yesterday's or the test sale | Cash paid out of the open drawer | |
| 12 | Pull the till's network cable, make a cash sale, plug it back | "Offline", then "Syncing", then the sale appears once in reports | |
| 13 | **Close shift** with the handover | Z-report; expected and counted cash agree | |
| 14 | Manager: **Dashboard** and **Reports** for the branch | Today's test sales show | |
| 15 | Manager: **Devices** | Every till listed as Registered, with a last sign-in | |
| 16 | Forgotten password from the till | Email arrives, link opens the POS and works once | |
| 17 | Unplug the main line (backup line takes over), reload | POS still opens (the backup address is listed) | |

Then clean up: void or return the test sales, so they do not sit in the day's figures.

Any failure that says **"The POS can only be used from a branch or head office network"** is
[section 11.1](#111-find-the-branchs-real-address) again: the site is using an address that is not
on the list.

## 13. M-Pesa in production

Only when Safaricom has taken the shortcode live on Daraja and you have the production app's
keys. What differs from the sandbox you tested with in development, setting by setting, is in
[MPESA-CALLBACKS.md section 3](MPESA-CALLBACKS.md#3-from-sandbox-to-production-what-changes). In short:
every `MPESA_*` value is the production one, the callback URL is the domain rather than a tunnel,
and the callback token is this server's own.

```bash
setenv() { sed -i "s|^$1=.*|$1=$2|" .env; grep -q "^$1=" .env || echo "$1=$2" >> .env; }
setenv MPESA_ENV production
setenv MPESA_CONSUMER_KEY 'from-the-production-app'
setenv MPESA_CONSUMER_SECRET 'from-the-production-app'
setenv MPESA_SHORTCODE 1234567
setenv MPESA_PASSKEY 'the-lipa-na-mpesa-passkey'
# The base only: the service adds /api/v1/payments/mpesa/callbacks/stk/<MPESA_CALLBACK_TOKEN>.
setenv MPESA_CALLBACK_URL https://pos.example.co.ke
# Refunds (reversals) only, optional:
setenv MPESA_INITIATOR_NAME 'api-operator-name'
setenv MPESA_SECURITY_CREDENTIAL 'the-encrypted-credential'

pos up -d --wait payment-service
pos logs payment-service | grep -iE "daraja|mpesa" | tail
```

Leave `MPESA_CALLBACK_ALLOWED_IPS` empty for now: the gateway does not yet pass the caller's
address to payment-service, so that check would refuse Safaricom too
([section 16](#16-not-done-yet)). The secret token in the callback path is what authenticates a
callback.

If the first push answers `404.001.03 Invalid Access Token` with fresh keys, the Daraja **app** is
not subscribed to *M-Pesa Express* in the portal - it is not a code or `.env` problem.

Then prove the callbacks reach the service - from outside the branch networks, then with a small
real payment (acceptance test 9) settled by the callback rather than the status sweep. The full
check, with what each answer means, is [MPESA-CALLBACKS.md](MPESA-CALLBACKS.md#4-in-production).
The short version:

```bash
# from outside every branch network (a phone hotspot): expect 404, not 403
python3 scripts/mpesa/callback_probe.py --url https://pos.example.co.ke --wrong-token-only
# on the VPS, while the test payment is made: expect "STK callback for ws_CO_...: result 0"
pos logs -f --since 1m payment-service | grep -iE "stk|callback"
```

## 14. Day-to-day operations

### Logs and status

```bash
pos ps
pos logs -f --since 10m sales-service       # one service
pos logs --since 1h 2>&1 | grep -E " ERROR | WARN " | tail -50
docker stats --no-stream                    # memory per container
df -h /                                     # a full disk stops Docker - keep an eye on it
```

### Updating to a new version

```bash
cd /opt/pos/app
git pull
make prod-up          # rebuilds what changed; Flyway migrates each schema at start-up
docker image prune -f && docker builder prune -f --filter until=168h
```

Services restart one after another as their images change, so expect a minute or two of
interruption. Tills keep selling offline and sync afterwards; update outside trading hours anyway.
If `.env.example` gained a new generated secret, `make env-sync` adds it to `.env`.

### Backups

Nightly database dump, kept 14 days, written to `/opt/pos/backups`:

```bash
sudo mkdir -p /opt/pos/backups && sudo chown deploy:deploy /opt/pos/backups
cat > /opt/pos/backup.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd /opt/pos/app
SU="$(grep '^POSTGRES_SUPERUSER=' .env | cut -d= -f2-)"
PW="$(grep '^POSTGRES_SUPERUSER_PASSWORD=' .env | cut -d= -f2-)"
STAMP="$(date +%F-%H%M)"
# --clean --if-exists: the dump drops and recreates what it restores, so it loads over a fresh
# server whose init scripts have already created the schemas and roles.
docker exec -e PGPASSWORD="$PW" pos-postgres \
  pg_dumpall --clean --if-exists -h localhost -U "$SU" | gzip > "/opt/pos/backups/pos-$STAMP.sql.gz"
cp .env secrets/jwt-keystore.p12 /opt/pos/backups/ 2>/dev/null || true
find /opt/pos/backups -name 'pos-*.sql.gz' -mtime +14 -delete
EOF
chmod 700 /opt/pos/backup.sh
( crontab -l 2>/dev/null; echo "30 2 * * * /opt/pos/backup.sh >> /opt/pos/backups/backup.log 2>&1" ) | crontab -
/opt/pos/backup.sh && ls -lh /opt/pos/backups
```

A backup on the same VPS is not a backup. Copy `/opt/pos/backups` off the server daily - to S3
(`aws s3 sync`), another server (`rsync`), or at least your own machine:

```bash
# from your computer
rsync -avz deploy@203.0.113.10:/opt/pos/backups/ ~/pos-backups/
```

**Rehearse a restore** before you depend on it - on a scratch VPS or your own machine, never on
production:

```bash
# on a fresh copy of the stack, with the same .env, before any service has started:
cd /opt/pos/app
SU="$(grep '^POSTGRES_SUPERUSER=' .env | cut -d= -f2-)"
PW="$(grep '^POSTGRES_SUPERUSER_PASSWORD=' .env | cut -d= -f2-)"
docker compose --env-file .env -f infra/compose/docker-compose.yml up -d --wait postgres
gunzip -c pos-2026-09-26-0230.sql.gz \
  | docker exec -i -e PGPASSWORD="$PW" pos-postgres psql -q -h localhost -U "$SU" -d postgres
make prod-up
```

Two errors are expected and harmless - `current user cannot be dropped` and `role "..." already
exists`, both about the superuser the restore is running as. Anything else is worth reading.
Then compare a few counts with production, e.g.
`SELECT count(*) FROM sales.sales;` in `make psql` on each.

### Changing who may connect

- **A branch's address changed:** [11.1](#111-find-the-branchs-real-address) and
  [11.2](#112-add-it-to-the-allowlist) - replace the old address rather than only adding.
- **A branch closes:** remove its addresses and `pos up -d --wait traefik api-gateway`; then revoke
  its devices and deactivate its staff.
- **A till is lost or stolen:** **Devices** → **Revoke**. Its sessions end at their next refresh
  (within 15 minutes).

### Things not to rotate casually

| Secret | What rotating it does |
|---|---|
| `WEB_SESSION_SECRET` | Signs everyone out **and un-registers every device** - every till needs a new code. |
| `secrets/jwt-keystore.p12` | Signs everyone out once. |
| Database role passwords | Must change in Postgres too - `.env` alone does not; the service can no longer connect. |
| `MPESA_CALLBACK_TOKEN` | Callbacks for pushes already sent are refused; change it between trading days only. |

### The certificate

Traefik renews it on its own, about 30 days before expiry. Let's Encrypt emails `ACME_EMAIL` if
renewal keeps failing. To check:

```bash
echo | openssl s_client -connect pos.example.co.ke:443 -servername pos.example.co.ke 2>/dev/null \
  | openssl x509 -noout -dates
```

## 15. Troubleshooting

| Symptom | Likely cause | Check / fix |
|---|---|---|
| `make prod-up` stops with `POS_DOMAIN is required` (or another key) | That key is empty in `.env` | [Section 6](#6-configure-env) |
| Browser shows the certificate is invalid | Let's Encrypt could not reach the server | `dig +short pos.example.co.ke`; ports 80 and 443 open; `pos logs traefik \| grep -i acme` |
| **Forbidden** at a branch | The branch's current address is not listed | [11.1](#111-find-the-branchs-real-address): read the refused address from Traefik's log |
| "The POS can only be used from a branch or head office network" after signing in | Traefik let it through but the gateway did not: the two lists differ | Both read `ALLOWED_CLIENT_NETWORKS`; recreate both: `pos up -d --wait traefik api-gateway` |
| "This device is not registered for the POS" on a till | Registration revoked, or the browser's data was cleared | **Devices** → revoke the old entry, register again, type the new code |
| Everyone signed out after a restart | auth-service generated a temporary key | `pos logs auth-service \| grep -E "signing key\|ephemeral"`; [section 7](#7-create-the-signing-key) |
| Password reset emails link to `localhost` | `POS_DOMAIN` unset when notification-service started | Set it and `pos up -d --wait notification-service` |
| No emails at all | SMTP settings | `pos logs notification-service \| grep -iE "mail\|smtp" \| tail` |
| M-Pesa sales stay "waiting" | Callback not arriving | `MPESA_CALLBACK_URL` is `https://POS_DOMAIN` exactly; [MPESA-CALLBACKS.md](MPESA-CALLBACKS.md#5-troubleshooting) |
| Containers restarting, `Killed` | Out of memory | `docker stats --no-stream`, add swap or RAM |
| Docker stops answering | Disk full | `df -h /`; `docker builder prune -f`; `docker image prune -f` |

## 16. Not done yet

Honest limits of this deployment, from the roadmap's Phase 16:

- **One server.** No replica, no rolling deploy: an update or a VPS failure stops the back office
  (tills keep selling offline and sync later). Backups are your safety net - rehearse the restore.
- **No monitoring or alerts** (Prometheus, Grafana, Loki). Until then, `pos ps` and the logs are
  it. An outside uptime checker still helps: it calls from an unlisted address, so configure it to
  treat **403 as up** - a 403 from Traefik proves the server, the certificate and Traefik are all
  answering.
- **Secrets live in `.env`**, not Docker secrets (the signing key excepted).
- **The M-Pesa callback IP check** (`MPESA_CALLBACK_ALLOWED_IPS`) cannot be used yet: the gateway
  does not pass the caller's address on to payment-service. The callback's secret token is what
  protects it today.
- **`make admin`, `make demo-seed` and `make api-smoke`** talk to the gateway on `localhost:8080`,
  which production does not publish. They are development tools; never seed demo data in
  production.
