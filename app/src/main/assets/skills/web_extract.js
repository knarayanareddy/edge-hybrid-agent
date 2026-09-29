/**
 * Safe Web Content Extraction Skill.
 * Fetches content from explicitly allowlisted HTTPS endpoints and parses basic readable text.
 */
window.__edgeRun = function(input, networkOrigins) {
    try {
        const url = (typeof input === 'object' && input.url) ? input.url : String(input);
        if (!url.startsWith("https://")) {
            window.__edgeHost.fail("Insecure scheme: Only https:// URLs are permitted");
            return;
        }

        const parsedUrl = new URL(url);
        const host = parsedUrl.hostname.toLowerCase();

        // Validate that origin is permitted
        const isPermitted = (networkOrigins || []).some(function(origin) {
            try {
                return new URL(origin).hostname.toLowerCase() === host;
            } catch (e) {
                return false;
            }
        });

        if (!isPermitted) {
            window.__edgeHost.fail("Origin not allowlisted in networkOrigins: " + host);
            return;
        }

        fetch(url, { method: "GET", headers: { "Accept": "text/html, text/plain" } })
            .then(function(response) {
                if (!response.ok) {
                    throw new Error("HTTP error " + response.status + ": " + response.statusText);
                }
                return response.text();
            })
            .then(function(html) {
                // Strip scripts, styles, and extract plain text
                const text = html
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
            .catch(function(err) {
                window.__edgeHost.fail("Network fetch failed: " + err.message);
            });
    } catch (err) {
        window.__edgeHost.fail("Web extract parameter error: " + err.message);
    }
};
