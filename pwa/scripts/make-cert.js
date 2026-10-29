'use strict';
// Creates a self-signed HTTPS certificate covering localhost and this machine's LAN addresses.
// iOS only registers service workers (offline mode) on HTTPS, so the iPhone must trust this cert.
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const dir = path.join(__dirname, '..', 'certs');
fs.mkdirSync(dir, { recursive: true });
const ips = Object.values(os.networkInterfaces()).flat()
  .filter((entry) => entry && entry.family === 'IPv4' && !entry.internal).map((entry) => entry.address);
const san = ['DNS:localhost', 'IP:127.0.0.1', ...ips.map((ip) => 'IP:' + ip)].join(',');

const candidates = ['openssl', 'C:\\Program Files\\Git\\usr\\bin\\openssl.exe',
  'C:\\Program Files (x86)\\Git\\usr\\bin\\openssl.exe'];
let done = false;
for (const exe of candidates) {
  try {
    execFileSync(exe, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '800',
      '-keyout', path.join(dir, 'key.pem'), '-out', path.join(dir, 'cert.pem'),
      '-subj', '/CN=CKTV Local', '-addext', 'subjectAltName=' + san,
      '-addext', 'extendedKeyUsage=serverAuth', '-addext', 'basicConstraints=CA:FALSE'],
    { stdio: 'pipe' });
    done = true;
    break;
  } catch (error) {
    if (error.code !== 'ENOENT') { console.error(String(error.stderr || error)); process.exit(1); }
  }
}
if (!done) {
  console.error('openssl was not found. Install Git for Windows (includes openssl) or OpenSSL and retry.');
  process.exit(1);
}
console.log('Certificate valid for:', san);
console.log('Next: npm start, open http://<LAN-IP>:8080/cktv-local.crt in iPhone Safari, install the profile,');
console.log('then Settings > General > About > Certificate Trust Settings > enable "CKTV Local".');
console.log('Finally open https://<LAN-IP>:8443/ and Share > Add to Home Screen.');
