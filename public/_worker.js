const BFF_KEY_HEADER = "X-VCore-Bff-Key";
const USER_EMAIL_HEADER = "X-VCore-User-Email";
const REQUEST_ID_HEADER = "X-Request-Id";
const uuid = "[0-9a-f-]{36}";
const workspace = `/api/workspaces/${uuid}`;
const project = `${workspace}/projects/${uuid}`;
const task = `${project}/tasks/${uuid}`;
const apiRoutes = [
  { path: /^\/api\/(?:session|health|auth\/session)$/, methods: ["GET", "HEAD"] },
  { path: /^\/api\/auth\/(?:login|logout)$/, methods: ["POST"] },
  { path: new RegExp(`^${workspace}/(?:overview|activities|events)$`, "i"), methods: ["GET"] },
  { path: new RegExp(`^${workspace}/(?:invitations|projects)$`, "i"), methods: ["POST"] },
  { path: new RegExp(`^${project}/tasks$`, "i"), methods: ["GET", "POST"] },
  { path: new RegExp(`^${task}$`, "i"), methods: ["PATCH"] },
  { path: new RegExp(`^${task}/moves$`, "i"), methods: ["POST"] },
  { path: new RegExp(`^${task}/assignee$`, "i"), methods: ["PATCH"] },
  { path: new RegExp(`^${task}/comments$`, "i"), methods: ["GET", "POST"] },
  { path: new RegExp(`^${project}/workflow-columns/${uuid}$`, "i"), methods: ["PATCH"] }
];

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

async function proxyApi(request, env) {
  const route = apiRoutes.find((candidate) => candidate.path.test(new URL(request.url).pathname));
  if (!route) return jsonProblem(404, "Not found", "This API route is not exposed.");
  if (!route.methods.includes(request.method)) {
    const response = jsonProblem(405, "Method not allowed", "This API method is not exposed.");
    response.headers.set("Allow", route.methods.join(", "));
    return response;
  }
  if (!["GET", "HEAD", "OPTIONS"].includes(request.method)) {
    const origin = request.headers.get("Origin");
    if ((origin && origin !== new URL(request.url).origin) || request.headers.get("Sec-Fetch-Site") === "cross-site") {
      return jsonProblem(403, "Forbidden", "Cross-origin mutations are not allowed.");
    }
  }
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
  headers.delete("Cf-Access-Authenticated-User-Email");
  headers.delete("Host");
  headers.set(BFF_KEY_HEADER, backend.sharedSecret);
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
