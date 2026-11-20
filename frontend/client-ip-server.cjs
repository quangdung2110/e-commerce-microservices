"use strict";

/**
 * Entry point of the production image (Dockerfile CMD): names the real client
 * before Next handles the request, then starts the standalone server.
 *
 * The rewrite of /api/* to the API Gateway (next.config.ts) forwards whatever
 * X-Forwarded-For / X-Real-Ip the browser sent and adds nothing of its own, so
 * the gateway saw every browser as the frontend container and per-IP rate
 * limits were one bucket for everybody (security review 2026-10-22, H2). The
 * gateway trusts these two headers from this container only (mesh ADR-004),
 * so they are overwritten here with the TCP peer: a client cannot pick its own
 * rate-limit bucket by sending them. If a proxy is ever put in front of Next,
 * this is the place that has to learn to trust it.
 */
const http = require("node:http");

function clientAddress(socket) {
  const address = (socket && socket.remoteAddress) || "";
  // IPv4 peers of a dual-stack socket arrive as ::ffff:a.b.c.d.
  return address.startsWith("::ffff:") ? address.slice(7) : address;
}

const emit = http.Server.prototype.emit;
http.Server.prototype.emit = function emitWithClient(event, request, ...rest) {
  if (event === "request" && request && request.headers) {
    const address = clientAddress(request.socket);
    request.headers["x-forwarded-for"] = address;
    request.headers["x-real-ip"] = address;
  }
  return emit.call(this, event, request, ...rest);
};

require("./server.js");
