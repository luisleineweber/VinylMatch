const baseUrl = process.env.BASE_URL || "http://127.0.0.1:8888";
const concurrency = Number(process.env.LOAD_CONCURRENCY || 20);
const requests = Number(process.env.LOAD_REQUESTS || 200);
const p95LimitMs = Number(process.env.LOAD_P95_MS || 500);
const durations = [];
let failures = 0;

async function request() {
  const start = performance.now();
  try {
    const response = await fetch(`${baseUrl}/api/health/simple`);
    if (!response.ok) failures += 1;
  } catch (_) {
    failures += 1;
  } finally {
    durations.push(performance.now() - start);
  }
}

for (let offset = 0; offset < requests; offset += concurrency) {
  await Promise.all(Array.from({ length: Math.min(concurrency, requests - offset) }, request));
}

durations.sort((a, b) => a - b);
const p95 = durations[Math.max(0, Math.ceil(durations.length * 0.95) - 1)] || 0;
const failureRate = failures / Math.max(1, requests);
console.log(JSON.stringify({ requests, concurrency, p95Ms: Math.round(p95), failures, failureRate }, null, 2));
if (failureRate > 0.01 || p95 > p95LimitMs) process.exit(1);
