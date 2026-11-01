# CKTV PWA (iPhone / web)

Offline-capable port of the Android TV app: same catalogs, filters, search, favorites, playback
history, saved resume position, last-working-source memory and automatic source failover.
Library data is stored on the device (localStorage); the app shell works offline and data
still comes from the CK TV site online.

## Run

From the repo root (Windows PowerShell):

```
.\pwa-service.ps1 start     # also: stop | restart | status
```

Or manually:

```
cd pwa
npm run cert     # once; self-signed HTTPS cert for localhost + LAN IPs (needs openssl, e.g. Git for Windows)
npm start        # http://localhost:8080 and https://<LAN-IP>:8443
```

No dependencies are needed (Node 20+). `npm run icons` regenerates the PWA icons.

## Install on iPhone (iOS only enables offline service workers over trusted HTTPS)

1. On the iPhone (same Wi-Fi) open `http://<LAN-IP>:8080/cktv-local.crt` in Safari and allow the profile download.
2. Settings > General > VPN & Device Management > install the "CKTV Local" profile.
3. Settings > General > About > Certificate Trust Settings > enable full trust for "CKTV Local".
4. Open `https://<LAN-IP>:8443/` in Safari, then Share > Add to Home Screen.

If the LAN IP changes, run `npm run cert` again and reinstall the profile.

## Notes

- The server relays `/api/*` to CK TV (handling its access gate), proxies HLS streams with the
  Referer/Origin headers the Android app sends, and fetches posters directly (some providers use
  self-signed IP hosts). Private/LAN targets are blocked.
- Settings > Export/Import moves favorites and history between devices.
- Search is scoped to the current section in 18+專區 and AI黃果; other sections search all sources.
- Android/TV-only parts (D-pad focus, exit prompt, live-platform external intents) are replaced by touch and the browser/PWA back gesture.

## Running without localhost

After the first visit the app shell is cached and opens with no server running (cached catalog data and viewed posters also work offline). Live browsing needs a gateway because CK TV has no CORS headers: run `server.js` on any always-on host and enter its URL under Settings > ????. The server now sends CORS headers so the installed PWA can use it from any origin.

### Free always-on gateway (no PC needed): Cloudflare Worker

1. `cd worker; npx wrangler login; npx wrangler deploy` (free account) - prints `https://cktv-gateway.<you>.workers.dev`
2. Open the app on the iPhone > Settings > ???? > paste that URL > ??.

Note: Settings are stored per install, so set the gateway again after adding the app to the Home Screen. The iPhone must load the app once over HTTPS (or the localhost server) while online so it can cache itself.

### Auto-deploy on every push

`.github/workflows/deploy-cloudflare.yml` runs `wrangler deploy` on each push to `main` that touches `pwa/public` or `pwa/worker`. The Worker also serves the PWA itself (`[assets]` in `wrangler.toml`), so the Worker URL is both the app and the gateway - open it in Safari, Add to Home Screen, and leave the gateway field empty.

One-time setup in GitHub > repo Settings > Secrets and variables > Actions:
- `CLOUDFLARE_API_TOKEN`: Cloudflare dashboard > My Profile > API Tokens > Create Token > template *Edit Cloudflare Workers*.
- `CLOUDFLARE_ACCOUNT_ID`: shown on the Workers & Pages overview page.

