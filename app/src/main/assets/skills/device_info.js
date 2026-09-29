/**
 * Device Environment & Diagnostics Starter Skill.
 */
window.__edgeRun = function(input, networkOrigins) {
    try {
        const info = {
            userAgent: navigator.userAgent || "Unknown",
            language: navigator.language || "en",
            languages: navigator.languages || [],
            cookieEnabled: navigator.cookieEnabled || false,
            platform: navigator.platform || "Android",
            online: navigator.onLine !== undefined ? navigator.onLine : true,
            timezoneOffset: new Date().getTimezoneOffset(),
            timestamp: new Date().toISOString()
        };

        window.__edgeHost.complete(JSON.stringify({
            status: "success",
            deviceInfo: info
        }));
    } catch (err) {
        window.__edgeHost.fail("Device info extraction error: " + err.message);
    }
};
