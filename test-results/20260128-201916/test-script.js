import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom metrics
const rateLimitAllowed = new Counter('rate_limit_allowed');
const rateLimitRejected = new Counter('rate_limit_rejected');
const idempotencyNew = new Counter('idempotency_new');
const idempotencyDuplicate = new Counter('idempotency_duplicate');
const latencyTrend = new Trend('custom_latency');

// Test configuration from environment
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_TYPE = __ENV.TEST_TYPE || 'baseline';

// Warmup scenario - runs before main test
// Increased duration to reduce cold starts in local Docker/LocalStack environment
const warmupScenario = {
    executor: 'constant-vus',
    vus: 5,
    duration: '30s', // Increased from 10s to better warm up LocalStack/DynamoDB
    startTime: '0s',
};

// Test scenarios
const scenarios = {
    quick: {
        executor: 'constant-vus',
        vus: 10,
        duration: '30s',
        startTime: '30s', // Start after warmup
    },
    smoke: {
        executor: 'constant-vus',
        vus: 5,
        duration: '1m',
        startTime: '30s', // Start after warmup
    },
    baseline: {
        executor: 'ramping-vus',
        startVUs: 0,
        startTime: '30s', // Start after warmup
        stages: [
            { duration: '2m', target: 50 },   // Ramp up
            { duration: '5m', target: 50 },   // Stay at 50 users
            { duration: '2m', target: 100 },  // Ramp to 100
            { duration: '5m', target: 100 },  // Stay at 100
            { duration: '2m', target: 0 },    // Ramp down
        ],
    },
    stress: {
        executor: 'ramping-vus',
        startVUs: 0,
        startTime: '30s', // Start after warmup
        stages: [
            { duration: '2m', target: 100 },
            { duration: '5m', target: 100 },
            { duration: '2m', target: 200 },
            { duration: '5m', target: 200 },
            { duration: '2m', target: 300 },
            { duration: '5m', target: 300 },
            { duration: '2m', target: 0 },
        ],
    },
    spike: {
        executor: 'ramping-vus',
        startVUs: 0,
        startTime: '30s', // Start after warmup
        stages: [
            { duration: '10s', target: 100 },
            { duration: '1m', target: 100 },
            { duration: '10s', target: 1000 }, // Spike!
            { duration: '3m', target: 1000 },
            { duration: '10s', target: 100 },
            { duration: '3m', target: 100 },
            { duration: '10s', target: 0 },
        ],
    },
    soak: {
        executor: 'constant-vus',
        vus: 100,
        duration: '30m', // Long-running
        startTime: '30s', // Start after warmup
    },
};

export const options = {
    scenarios: {
        warmup: warmupScenario,
        [TEST_TYPE]: scenarios[TEST_TYPE],
    },
    thresholds: {
        // Apply thresholds only to the main test scenario (not warmup)
        // Realistic thresholds for local Docker/LocalStack environment:
        // - Accounts for Docker networking overhead
        // - Allows for occasional LocalStack/DynamoDB cold starts
        // - More lenient than production but still catches real issues
        [`http_req_duration{scenario:${TEST_TYPE}}`]: ['p(95)<500', 'p(99)<2000'], // 95% < 500ms, 99% < 2s
        [`http_req_failed{scenario:${TEST_TYPE}}`]: ['rate<0.01'],  // Error rate < 1%
        [`http_req_duration{endpoint:ratelimit,scenario:${TEST_TYPE}}`]: ['p(99)<200'],  // Rate limit checks < 200ms
        [`http_req_duration{endpoint:idempotency,scenario:${TEST_TYPE}}`]: ['p(99)<200'], // Idempotency checks < 200ms
    },
    summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Test data generators
function randomUserId() {
    return `user:${Math.floor(Math.random() * 1000)}`;
}

function randomIdempotencyKey() {
    return `operation:${Math.random().toString(36).substring(7)}`;
}

// Rate limit check
export function rateLimitCheck() {
    const payload = JSON.stringify({
        key: randomUserId(),
        cost: 1,
        algorithm: 'token_bucket',
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer test-api-key',
        },
        tags: { endpoint: 'ratelimit' },
    };

    const startTime = Date.now();
    const res = http.post(`${BASE_URL}/v1/ratelimit/check`, payload, params);
    const duration = Date.now() - startTime;

    latencyTrend.add(duration);

    const checks = check(res, {
        'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
        'has allowed field': (r) => {
            try {
                const body = JSON.parse(r.body);
                return 'allowed' in body;
            } catch {
                return false;
            }
        },
    });
    
    // Latency check is tracked separately via thresholds, not as a hard failure
    // Only log if there's an actual HTTP error, not just slow requests
    if (!checks && res.status >= 400) {
        console.error(`HTTP error: ${res.status} - ${res.body.substring(0, 100)}`);
    }

    // Track allowed vs rejected
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        if (body.allowed) {
            rateLimitAllowed.add(1);
        } else {
            rateLimitRejected.add(1);
        }
    }

    return res;
}

// Idempotency check
export function idempotencyCheck() {
    const key = randomIdempotencyKey();
    
    const payload = JSON.stringify({
        idempotencyKey: key,
        ttl: 3600,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer test-api-key',
        },
        tags: { endpoint: 'idempotency' },
    };

    const res = http.post(`${BASE_URL}/v1/idempotency/check`, payload, params);

    const checks = check(res, {
        'status is 200': (r) => r.status === 200,
        'has status field': (r) => {
            try {
                const body = JSON.parse(r.body);
                return 'status' in body;
            } catch {
                return false;
            }
        },
    });
    
    // Only log actual HTTP errors, not validation failures
    if (!checks && res.status >= 400) {
        console.error(`HTTP error: ${res.status} - ${res.body.substring(0, 100)}`);
    }

    // Track new vs duplicate
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        if (body.status === 'new') {
            idempotencyNew.add(1);
        } else if (body.status === 'duplicate') {
            idempotencyDuplicate.add(1);
        }
    }

    return res;
}

// Main test function
export default function() {
    // Mix of rate limit and idempotency checks (80/20 split)
    const rand = Math.random();
    
    if (rand < 0.8) {
        rateLimitCheck();
    } else {
        idempotencyCheck();
    }

    // Think time (simulate realistic usage)
    sleep(Math.random() * 2);
}

// Setup function (runs once per VU)
export function setup() {
    // Warmup request
    const res = http.get(`${BASE_URL}/health`);
    check(res, {
        'warmup successful': (r) => r.status === 200,
    });
    
    return { startTime: Date.now() };
}

// Teardown function (runs once at end)
export function teardown(data) {
    const duration = (Date.now() - data.startTime) / 1000;
    console.log(`Test completed in ${duration.toFixed(2)} seconds`);
}
