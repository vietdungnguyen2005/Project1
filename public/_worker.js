const BFF_KEY_HEADER = "X-VCore-Bff-Key";
const USER_EMAIL_HEADER = "X-VCore-User-Email";
const REQUEST_ID_HEADER = "X-Request-Id";

function jsonProblem(status, title, detail) {
  return new Response(JSON.stringify({ type: "about:blank", title, status, detail }), {
    status,
    headers: {
      "Content-Type": "application/problem+json; charset=utf-8",
      "Cache-Control": "no-store"
    }
  });
}

function backendConfiguration(env) {
  const origin = env.BACKEND_ORIGIN?.trim();
  const sharedSecret = env.BFF_SHARED_SECRET?.trim();

  if (!origin || !sharedSecret) {
    return null;
  }

  try {
    return { origin: new URL(origin), sharedSecret };
  } catch {
    return null;
  }
}

function trustedIdentity(request, env) {
  return request.headers.get("Cf-Access-Authenticated-User-Email") ||
    env.DEMO_USER_EMAIL?.trim() ||
    "owner@v-core.local";
}

async function proxyApi(request, env) {
  const backend = backendConfiguration(env);
  if (!backend) {
    return jsonProblem(
      503,
      "Backend unavailable",
      "The BFF backend origin or shared secret has not been configured."
    );
  }

  const incomingUrl = new URL(request.url);
  const backendPath = incomingUrl.pathname === "/api/health"
    ? "/actuator/health"
    : incomingUrl.pathname;
  const targetUrl = new URL(`${backendPath}${incomingUrl.search}`, backend.origin);
  const headers = new Headers(request.headers);

  headers.delete(BFF_KEY_HEADER);
  headers.delete(USER_EMAIL_HEADER);
  headers.delete("Host");
  headers.set(BFF_KEY_HEADER, backend.sharedSecret);
  headers.set(USER_EMAIL_HEADER, trustedIdentity(request, env));
  headers.set(REQUEST_ID_HEADER, request.headers.get(REQUEST_ID_HEADER) || crypto.randomUUID());
  headers.set("X-Forwarded-Host", incomingUrl.host);
  headers.set("X-Forwarded-Proto", incomingUrl.protocol.replace(":", ""));

  const response = await fetch(targetUrl, {
    method: request.method,
    headers,
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
    redirect: "manual"
  });
  const responseHeaders = new Headers(response.headers);
  responseHeaders.set("Cache-Control", "no-store");

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: responseHeaders
  });
}

const worker = {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname.startsWith("/api/")) {
      try {
        return await proxyApi(request, env);
      } catch {
        return jsonProblem(502, "Bad gateway", "The Spring Boot backend could not be reached.");
      }
    }

    return env.ASSETS.fetch(request);
  }
};

export default worker;
