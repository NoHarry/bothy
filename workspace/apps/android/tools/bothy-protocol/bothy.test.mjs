import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import net from "node:net";
import readline from "node:readline";
import { connect, describeItem, describeSnapshot, loadSchema } from "./bothy.mjs";

/** A server that speaks the protocol's lines: answers the handshake, a turn, and asks one approval. */
function fakeServer() {
  const seen = [];
  const server = net.createServer((socket) => {
    const send = (message) => socket.write(`${JSON.stringify(message)}\n`);
    readline.createInterface({ input: socket }).on("line", (line) => {
      const message = JSON.parse(line);
      seen.push(message);
      if (message.method === "initialize") {
        send({
          id: message.id,
          result: {
            protocolVersion: 1,
            serverInfo: { name: "bothy", version: "test" },
            snapshot: {
              threads: [{ sessionId: "t1", title: "去世界之窗", updatedAtEpochMillis: 1, turnCount: 1, compactedSummary: "" }],
              activeThreadId: "t1",
              thread: { threadId: "t1", items: [] },
              sections: [{ type: "run", activity: "idle", isRunning: false }],
            },
          },
        });
      } else if (message.method === "turn/start") {
        send({ id: message.id, result: {} });
        send({ method: "item/started", params: { threadId: "t1", item: { type: "message", id: 2, role: "ASSISTANT", content: "路线" } } });
        send({ id: 99, method: "approval/request", params: { threadId: "t1", itemId: 3, pendingId: "p1", mode: "ASK_EVERY_TIME", toolName: "car_climate" } });
      } else if (message.id === 99) {
        send({ method: "turn/completed", params: { threadId: "t1", requestId: "r", result: { outcome: "completed", durationMs: 1000 } } });
      }
    });
  });
  return { server, seen };
}

test("a turn over the socket: handshake, items, an approval answered, the end", async () => {
  const { server, seen } = fakeServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const notes = [];
  let ended;
  const end = new Promise((resolve) => { ended = resolve; });
  const { connection, result } = await connect({
    port: server.address().port,
    approvals: true,
    onNotification: (method, params) => {
      notes.push(method);
      if (method === "turn/completed") ended(params.result.outcome);
    },
    onRequest: async (method, params) => (method === "approval/request" && params.toolName === "car_climate" ? { decision: "approve" } : null),
  });
  assert.deepEqual(describeSnapshot(result).slice(0, 2), ["bothy test · protocol 1", "threads: 1 · current: 去世界之窗 (0 items)"]);
  await connection.request("turn/start", { text: "去世界之窗" });
  assert.equal(await end, "completed");
  assert.deepEqual(notes, ["item/started", "turn/completed"]);
  assert.deepEqual(seen.find((message) => message.id === 99).result, { decision: "approve" });
  assert.ok(seen.every((message) => !("jsonrpc" in message)));
  connection.close();
  server.close();
});

test("items read as one line each, and quiet ones not at all", () => {
  assert.equal(describeItem({ type: "userMessage", fullText: "开空调" }), "> 开空调");
  assert.equal(describeItem({ type: "toolCall", toolName: "car_climate", status: "DONE" }), "  [done] car_climate");
  assert.equal(describeItem({ type: "message", role: "THINKING", content: "先查" }), null);
});

test("every method the client sends or waits for is in the protocol's schema", () => {
  const { methods } = loadSchema();
  const source = fs.readFileSync(new URL("./bothy.mjs", import.meta.url), "utf8");
  const named = (pattern) => [...source.matchAll(pattern)].map((match) => match[1]);
  const sent = named(/\.request\("([^"]+)"/g);
  const notified = named(/\.notify\("([^"]+)"/g);
  const awaited = named(/method [!=]== "([^"]+)"/g);

  assert.ok(sent.length > 0 && notified.length > 0 && awaited.length > 0);
  sent.forEach((method) => assert.ok(method in methods.clientRequests, `${method} is no client request`));
  notified.forEach((method) => assert.ok(method in methods.clientNotifications, `${method} is no client notification`));
  awaited.forEach((method) => assert.ok(method in methods.serverNotifications || method in methods.serverRequests, `${method} is not sent by the server`));
});
