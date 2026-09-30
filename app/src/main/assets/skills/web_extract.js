/**
 * Safe Web Content Extraction Skill.
 *
 * Fetches content from explicitly allowlisted HTTPS origins and extracts readable text.
 *
 * The origin list is supplied by the host sandbox, which has already normalized each
 * entry to an exact `https://host:port` and discarded private/reserved hosts. This
 * script re-checks that the requested URL's own origin appears in that list before
 * fetching, so a mismatch between the script's idea of the allowlist and the host's
 * cannot produce a request the host would not also permit.
 */
window.__edgeRun = function (input, networkOrigins) {
    try {
        var url = (input && typeof input === 'object' && input.url) ? input.url : String(input);
        url = (url || '').trim();

        if (url.indexOf('https://') !== 0) {
            window.__edgeHost.fail("Insecure scheme: Only https:// URLs are permitted");
            return;
        }

        var parsed;
        try {
            parsed = new URL(url);
        } catch (e) {
            window.__edgeHost.fail("Malformed URL");
            return;
        }

        // Match on the full origin (scheme + host + port), not just the hostname, so a
        // non-standard port cannot ride in on a hostname that is allowlisted.
        var requestOrigin = parsed.protocol + '//' + parsed.hostname +
            (parsed.port ? ':' + parsed.port : '');

        var allowed = (networkOrigins || []).map(function (origin) {
            try {
                var o = new URL(origin);
                return o.protocol + '//' + o.hostname + (o.port ? ':' + o.port : '');
            } catch (e) {
                return null;
            }
        });

        if (allowed.indexOf(requestOrigin) === -1) {
            window.__edgeHost.fail("Origin not allowlisted by the sandbox: " + requestOrigin);
            return;
        }

        fetch(url, { method: "GET", headers: { "Accept": "text/html, text/plain" } })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("HTTP error " + response.status + ": " + response.statusText);
                }
                return response.text();
            })
            .then(function (html) {
                // Strip scripts and styles, then tags, leaving plain text.
                var text = String(html)
                    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, "")
                    .replace(/<style\b[^<]*(?:(?!<\/style>)<[^<]*)*<\/style>/gi, "")
                    .replace(/<[^>]+>/g, " ")
                    .replace(/\s+/g, " ")
                    .trim();

                window.__edgeHost.complete(JSON.stringify({
                    status: "success",
                    url: url,
                    extractedLength: text.length,
                    textSnippet: text.substring(0, 2000)
                }));
            })
            .catch(function (err) {
                window.__edgeHost.fail("Network fetch failed: " + err.message);
            });
    } catch (err) {
        window.__edgeHost.fail("Web extract parameter error: " + err.message);
    }
};
