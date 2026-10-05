import { z } from "zod";

const RpcId = z.union([z.number().int(), z.string()]);

export const HelloParams = z.object({
  token: z.string().min(1),
  agentKind: z.enum(["client", "server"]),
  agentVersion: z.string(),
  protocolVersion: z.number().int(),
  mcVersion: z.string(),
  instanceName: z.string(),
  serverDir: z.string().optional(),
  pid: z.number().int().positive().optional(),
});
export type HelloParams = z.infer<typeof HelloParams>;

export const HelloRequest = z.object({
  jsonrpc: z.literal("2.0"),
  id: RpcId,
  method: z.literal("hello"),
  params: HelloParams,
});

export const StatusRequest = z.object({
  jsonrpc: z.literal("2.0"),
  id: RpcId,
  method: z.literal("status"),
  params: z.object({ token: z.string().min(1) }),
});
export type StatusRequest = z.infer<typeof StatusRequest>;

export const RpcErrorObject = z.object({
  code: z.number().int(),
  message: z.string(),
  data: z.object({ code: z.string(), hint: z.string().optional() }).partial().optional(),
});

export const RpcResponse = z.union([
  z.object({ jsonrpc: z.literal("2.0"), id: RpcId, result: z.unknown() }).refine((m) => "result" in m),
  z.object({ jsonrpc: z.literal("2.0"), id: RpcId, error: RpcErrorObject }),
]);

export const EventParams = z.object({
  type: z.string().min(1),
  time: z.number(),
  data: z.record(z.string(), z.unknown()),
});
export type AgentEvent = z.infer<typeof EventParams>;

export const EventNotification = z.object({
  jsonrpc: z.literal("2.0"),
  method: z.literal("event"),
  params: EventParams,
});

export const AgentMessage = z.union([HelloRequest, StatusRequest, RpcResponse, EventNotification]);
export type AgentMessage = z.infer<typeof AgentMessage>;
