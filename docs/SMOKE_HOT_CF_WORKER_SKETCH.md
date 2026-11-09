# Optional: Cloudflare Worker sketch for smokeHot relay

Not required if `scripts/hot-smoke-drive-relay.py` is enough.

```js
// Pseudocode — store DRIVE_TOKEN or use a Google SA JWT in Worker secrets.
export default {
  async fetch(req, env) {
    if (req.method !== "POST" && req.method !== "PUT") {
      return new Response("method", { status: 405 });
    }
    const name = req.headers.get("X-Smoke-Filename") || "smoke-unknown.log";
    const body = await req.arrayBuffer();
    // multipart upload to Drive folder env.DRIVE_FOLDER_ID with Bearer env.DRIVE_TOKEN
    // then list+trash older smoke-*.log (same as Python relay)
    return Response.json({ ok: true, name });
  },
};
```

Device: `uploadMode=https`, `uploadUrl=https://<worker>/upload/%s`.
