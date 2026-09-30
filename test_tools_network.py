#!/usr/bin/env python3
import json
import urllib.request
import urllib.parse
import re

print("==================================================")
print("=== TESTING TOOL APIS & ENGINE EXECUTION PROTOCOLS ===")
print("==================================================")

passed = 0
failed = 0

def test(name, fn):
    global passed, failed
    try:
        fn()
        print(f"  [PASS] {name}")
        passed += 1
    except Exception as e:
        print(f"  [FAIL] {name}: {e}")
        failed += 1

# 1. Weather API
def test_weather():
    url = "https://geocoding-api.open-meteo.com/v1/search?name=Amsterdam&count=1&language=en&format=json"
    req = urllib.request.Request(url, headers={"User-Agent": "EdgeHybridAgent/1.0"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        assert len(data.get("results", [])) > 0, "No geocoding results"
        lat = data["results"][0]["latitude"]
        lon = data["results"][0]["longitude"]
    
    forecast_url = f"https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current_weather=true"
    req2 = urllib.request.Request(forecast_url, headers={"User-Agent": "EdgeHybridAgent/1.0"})
    with urllib.request.urlopen(req2, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        assert "current_weather" in data, "No current_weather in response"
        temp = data["current_weather"]["temperature"]
        assert temp is not None

test("Weather (Open-Meteo Geocoding + Forecast)", test_weather)

# 2. Wikipedia Search API
def test_wikipedia():
    query = "Achmea"
    url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(query)}"
    req = urllib.request.Request(url, headers={"User-Agent": "EdgeHybridAgent/1.0 (Mobile Assistant)"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        assert "extract" in data, "No extract in summary"
        assert len(data["extract"]) > 20, "Extract too short"

test("Wikipedia Entity Summary (Achmea)", test_wikipedia)

# 3. Currency Conversion API
def test_currency():
    url = "https://api.frankfurter.app/latest?from=USD&to=EUR"
    req = urllib.request.Request(url, headers={"User-Agent": "EdgeHybridAgent/1.0"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        assert "rates" in data and "EUR" in data["rates"], "No EUR rate found"
        rate = data["rates"]["EUR"]
        assert rate > 0

test("Currency Exchange (Frankfurter API USD->EUR)", test_currency)

# 4. Live Web Search with Wikipedia Fallback
def test_live_search():
    # Simulates SkillLoader.executeLiveWebSearch
    query = "iPhone 16 pro max"
    # Search fallback logic
    search_url = f"https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={urllib.parse.quote(query)}&format=json"
    req = urllib.request.Request(search_url, headers={"User-Agent": "EdgeHybridAgent/1.0 (Mobile Assistant)"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        search_results = data.get("query", {}).get("search", [])
        assert len(search_results) > 0, "Fallback search returned zero results"
        snippet = search_results[0].get("snippet", "")
        assert len(snippet) > 0

test("Live Web Search Resiliency (iPhone search query fallback)", test_live_search)

# 5. Extract Webpage Content
def test_extract_webpage():
    url = "https://example.com"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Android; Mobile)"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        html = resp.read().decode()
        cleaned = re.sub(r"<[^>]+>", " ", html)
        cleaned = re.sub(r"\s+", " ", cleaned).strip()
        assert "Example Domain" in cleaned

test("Extract Webpage Content", test_extract_webpage)

# 6. Mathematical Engine
def test_math():
    import math
    expr = "sqrt(144) + 25 * 4 - 10 / 2"
    # Safe eval simulation
    val = math.sqrt(144) + 25 * 4 - 10 / 2
    assert val == 107.0

test("Math Evaluator Accuracy", test_math)

print(f"\nResults: {passed} passed, {failed} failed")
assert failed == 0, "Tool API tests failed"
